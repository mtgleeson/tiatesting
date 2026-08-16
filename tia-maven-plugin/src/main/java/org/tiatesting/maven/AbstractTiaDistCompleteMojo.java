package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.tiatesting.core.agent.ForkSystemProperties;
import org.tiatesting.core.distributed.DistributedForkProperties;
import org.tiatesting.core.distributed.DistributedRunSealer;
import org.tiatesting.core.distributed.DistributedRunnerContext;
import org.tiatesting.core.distributed.DistributedRunnerPersist;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

/**
 * Mojo that closes out one runner's share of a distributed test run: completes its claimed group
 * and, if this runner happens to be the last one to finish, seals the build.
 *
 * <p>This goal exists because only the build tool knows when Surefire's retries are finished. The
 * forked test JVM cannot tell - it sees one test plan at a time, and a JVM can be forked more than
 * once per Surefire invocation. So the group-completion and sealer-election that used to happen
 * from a JVM shutdown hook inside the fork move here, to a step the build binds once, after
 * Surefire itself has finished retrying.
 *
 * <p>Everything this goal needs about the claim it is closing out - the run id, the resolved
 * runner key and the claimed group number - was already decided when {@code prepare-agent} claimed
 * the group, and travelled to the forked JVM via {@code ${tiaBuildDir}/fork.properties}. This goal
 * reads that same file back rather than re-deriving any of the three values: a runner key it
 * derived for itself (a different process id, say) would not match the claimed row, and the
 * guarded completion write would match no row and the group would never close. The mapping/stats/
 * history update flags are read from the same file for the matching reason - so the seal uses the
 * flags the runner actually ran under even if this goal's own {@code -D} arguments disagree.
 *
 * <p>The goal is safe to run unconditionally in a pipeline: with no {@code fork.properties} file,
 * or a file carrying no distributed handoff, there is nothing to complete, and the goal logs that
 * and exits successfully. This is not an aggregator - see rule 4 in {@link
 * org.tiatesting.core.distributed.DistributedRunPreconditions} for why neither the planning step
 * nor a runner's claim (nor, by the same reasoning, this goal's completion of one) is bound to run
 * once across a multi-module build - so this goal runs per project, reads that project's own fork
 * properties file, and skips projects that have none.
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for the lifecycle this goal closes.
 */
public abstract class AbstractTiaDistCompleteMojo extends AbstractTiaMojo {

    /**
     * Complete this runner's claimed group and, if elected, seal the distributed build.
     *
     * <p>When Tia is disabled, this returns immediately without reading {@code fork.properties} at
     * all - a build with Tia switched off must never act on a stale handoff file left over from an
     * earlier distributed build, and must never fail on connection settings ({@code tiaDBUrl}) it
     * was never going to use. Otherwise, reads {@code ${tiaBuildDir}/fork.properties}, the same
     * file {@code prepare-agent} wrote for the forked test JVM, and reconstructs the runner's
     * {@link DistributedRunnerContext} from its values directly - never from this mojo's own
     * {@code -D} parameters, and never by publishing the file into this build JVM's own system
     * properties, which would leak test-fork configuration into a JVM that is not a fork. A missing
     * file, or one with no distributed handoff in it, means this was not a distributed run (or this
     * runner claimed nothing), and the goal is a no-op.
     *
     * <p>Before opening the datastore for a claimed runner, this goal also verifies it was pointed
     * at the same shared database the runner's {@code prepare-agent} execution used: a pipeline
     * that invokes this goal as a separate {@code mvn} command and omits the DB connection settings
     * would otherwise silently open a private, empty embedded database, find no claimed row, and
     * exit as if the group were already completed - leaving the run unsealed with nothing telling
     * the user. Once past that guard, it completes the claimed group and, when that succeeds,
     * stands for election to seal the build - see {@link DistributedRunnerPersist#completeGroup}
     * and {@link DistributedRunSealer#sealIfElected} for what each step does and why a rejected
     * completion (superseded, or already completed) is a normal outcome rather than a failure here.
     *
     * @throws MojoExecutionException if the fork properties file exists but cannot be read, if its
     *                                 distributed handoff is malformed, if this goal is not pointed
     *                                 at the shared database a claimed run needs, or if completing
     *                                 the group or sealing the build fails unexpectedly (for
     *                                 example, the datastore is unreachable) - in every case naming
     *                                 that this build was NOT sealed and the next build will redo
     *                                 the work
     * @throws MojoFailureException never thrown directly; declared by the mojo contract
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!isTiaEnabled()) {
            getLog().info("Tia is disabled (tiaEnabled=false) - not acting on any distributed run "
                    + "handoff, even a stale fork.properties file left over from an earlier "
                    + "distributed build.");
            return;
        }

        File forkPropertiesFile = new File(getForkPropertiesFilename());
        if (!forkPropertiesFile.exists()) {
            getLog().info("No fork properties file found at " + forkPropertiesFile
                    + " - this was not a distributed run, so there is nothing to complete.");
            return;
        }

        Properties forkProperties;
        try {
            forkProperties = ForkSystemProperties.read(forkPropertiesFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not read the fork properties file at "
                    + forkPropertiesFile + " - this build will NOT be sealed, and the next build "
                    + "will redo the work: " + e.getMessage(), e);
        }

        if (!Boolean.parseBoolean(forkProperties.getProperty(DistributedForkProperties.PROP_DISTRIBUTED))) {
            getLog().info("The fork properties file at " + forkPropertiesFile + " carries no "
                    + "distributed run handoff - this was not a distributed run, so there is "
                    + "nothing to complete.");
            return;
        }

        DistributedRunnerContext context;
        try {
            context = DistributedForkProperties.contextFromProperties(forkProperties);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("The distributed run handoff in " + forkPropertiesFile
                    + " is malformed - this build will NOT be sealed, and the next build will redo "
                    + "the work: " + e.getMessage(), e);
        }

        if (!context.isClaimed()) {
            getLog().info("Distributed run '" + context.getRunId() + "': runner '"
                    + context.getRunnerKey() + "' claimed no group, so it has nothing to complete "
                    + "and nothing to seal.");
            return;
        }

        if (!DataStoreFactory.isSharedDatabase(getTiaDBUrl(), getTiaDBDialect())) {
            throw new MojoExecutionException("Distributed run '" + context.getRunId() + "': this "
                    + "goal was pointed at a private, embedded H2 database rather than the shared "
                    + "datastore the run's runners coordinate through - this build will NOT be "
                    + "sealed. Set tiaDBUrl (and tiaDBDialect, if needed) to the same connection "
                    + "settings the runner's prepare-agent execution used.");
        }

        boolean updateDBMapping =
                Boolean.parseBoolean(forkProperties.getProperty(ForkSystemProperties.PROP_UPDATE_DB_MAPPING));
        boolean updateDBStats =
                Boolean.parseBoolean(forkProperties.getProperty(ForkSystemProperties.PROP_UPDATE_DB_STATS));
        boolean updateDBTestRunHistory = Boolean.parseBoolean(
                forkProperties.getProperty(ForkSystemProperties.PROP_UPDATE_DB_TEST_RUN_HISTORY));

        completeAndSeal(context, updateDBMapping, updateDBStats, updateDBTestRunHistory);
    }

    /**
     * Open the shared datastore and perform the completion and, when elected, the seal - in the
     * order the run's correctness depends on: the group must be marked complete, releasing the
     * barrier the sealer's catalogue rebuild waits on, before this runner may stand for election.
     *
     * <p>A failure is reported differently depending on which side of that barrier it happened on:
     * a failure while completing the group (or opening the datastore) leaves the group exactly as
     * it was, safe for the next build to redo; a failure while sealing happens only after the group
     * already flipped to {@code COMPLETED}, so the message must say the group was completed rather
     * than imply completion itself failed - an operator reading "could not complete group N" would
     * otherwise go looking for a row that is, in fact, already marked completed.
     *
     * @param context the claimed runner context read from the fork properties file
     * @param updateDBMapping whether this run updates the mapping DB, as recorded in the fork
     *                        properties file
     * @param updateDBStats whether this run updates the stats DB, as recorded in the fork
     *                       properties file
     * @param updateDBTestRunHistory whether this run logs a history row, as recorded in the fork
     *                               properties file
     * @throws MojoExecutionException if completing the group or sealing the build fails; the
     *                                 message distinguishes which of the two happened
     */
    private void completeAndSeal(final DistributedRunnerContext context, final boolean updateDBMapping,
                                 final boolean updateDBStats, final boolean updateDBTestRunHistory)
            throws MojoExecutionException {
        boolean groupCompleted = false;
        try (DataStore dataStore = buildDataStore(getVCSReader().getBranchName())) {
            DistributedRunnerPersist persist = new DistributedRunnerPersist(dataStore, context);
            DistributedRunGroup completed = persist.completeGroup(System.currentTimeMillis());

            if (completed == null) {
                // A rejected completion is either "superseded" or "already completed", both of
                // which DistributedRunnerPersist already logged the reason for. Neither has a
                // group left for this runner to seal from, so there is nothing further to do.
                return;
            }
            groupCompleted = true;

            new DistributedRunSealer(dataStore, context).sealIfElected(updateDBMapping, updateDBStats,
                    updateDBTestRunHistory, System.currentTimeMillis());
        } catch (RuntimeException e) {
            if (groupCompleted) {
                throw new MojoExecutionException("Distributed run '" + context.getRunId()
                        + "': runner '" + context.getRunnerKey() + "' completed group "
                        + context.getGroupNumber() + ", but sealing the build failed - the group is "
                        + "now marked COMPLETED even though the run was NOT sealed: "
                        + e.getMessage(), e);
            }
            throw new MojoExecutionException("Distributed run '" + context.getRunId() + "': runner '"
                    + context.getRunnerKey() + "' could not complete group " + context.getGroupNumber()
                    + " - this build will NOT be sealed, and the next build will redo the work: "
                    + e.getMessage(), e);
        }
    }
}
