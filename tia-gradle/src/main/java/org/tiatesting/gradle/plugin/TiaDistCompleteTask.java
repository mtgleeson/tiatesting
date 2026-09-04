package org.tiatesting.gradle.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.TaskAction;
import org.slf4j.Logger;
import org.tiatesting.core.distributed.DistributedRunCompleter;
import org.tiatesting.core.distributed.DistributedRunnerContext;
import org.tiatesting.core.persistence.DataStore;

/**
 * Gradle task that closes out one runner's share of a distributed test run: completes its claimed
 * group and, if this runner happens to be the last one to finish, seals the build. The Gradle
 * equivalent of the Maven {@code dist-complete} goal ({@code AbstractTiaDistCompleteMojo}).
 *
 * <p>This task exists because only the build tool knows when a test task's retries are finished.
 * Gradle wires it as a {@code finalizedBy} finalizer of the distributed test task, which runs once
 * the test task's workers are all done - even when the test task itself failed, since a runner
 * whose tests failed still has to complete its group or the run never seals.
 *
 * <p>Everything this task needs about the claim it is closing out - the run id, the resolved runner
 * key, the claimed group number and the update-DB flags - was already decided when the test task's
 * {@code doFirst} action claimed the group in the daemon (see {@code
 * TiaSpockGitGradlePluginTestExtension#claimDistributedRun}), and recorded in this build's {@link
 * DistributedClaimRegistry}. This task reads that record back by test task path rather than
 * re-deriving any of its values: a runner key it derived for itself would not match the claimed row,
 * and the guarded completion write would match no row and the group would never close.
 *
 * <p>Registered only for a distributed build, so a non-distributed Gradle build gains no task and no
 * finalizer - see {@link TiaBasePlugin#createDistCompleteTask(String)}, called from the build-tool
 * bridge that resolves the merged {@code tia { distributed = ... } } flag at configuration time.
 * With no claim recorded for its test task, this task's action logs at INFO and exits successfully:
 * that is what a build that turned out not to be distributed looks like from here.
 *
 * <p>Carries no shared-database precondition check of its own, unlike the Maven {@code
 * tia-dist-complete} goal, and the asymmetry is deliberate rather than an omission. Maven needs one
 * because its goal can be a separate {@code mvn} invocation with its own {@code -D} properties, so
 * nothing stops a pipeline pointing the completion at a different database from the one the claim
 * used. Gradle structurally cannot reach that state: the claim already enforces {@link
 * org.tiatesting.core.distributed.DistributedRunPreconditions#check} against the owning {@link
 * TiaBasePlugin}'s connection settings, this task reads its connection from that same plugin in
 * that same daemon, and running it standalone finds no {@link DistributedClaimRegistry} entry and
 * exits at the no-claim branch above. Adding a check here would guard a case that cannot arise.
 *
 * <p>Implemented as a {@link DefaultTask} subclass, like {@link TiaDistPlanTask}, with its
 * dependencies - the owning {@link TiaBasePlugin} and the test task path whose claim it completes -
 * injected at registration time via {@link #setPlugin(TiaBasePlugin)} and {@link
 * #setTestTaskPath(String)} rather than resolved when the plugin is applied.
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for the lifecycle this task closes.
 */
public class TiaDistCompleteTask extends DefaultTask {

    private static final Logger LOGGER = Logging.getLogger(TiaDistCompleteTask.class);

    private TiaBasePlugin plugin;
    private String testTaskPath;

    /**
     * Inject the owning plugin; called from {@link TiaBasePlugin#createDistCompleteTask(String)} at
     * task registration so every configuration getter and datastore/VCS helper this task needs is
     * resolved lazily at execution time rather than at plugin-apply time.
     *
     * @param plugin the {@link TiaBasePlugin} instance that registered this task
     */
    public void setPlugin(TiaBasePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Inject the path of the test task whose distributed-run claim this task completes; called from
     * {@link TiaBasePlugin#createDistCompleteTask(String)} at task registration.
     *
     * @param testTaskPath the {@link org.gradle.api.Task#getPath()} of the finalized test task
     */
    public void setTestTaskPath(String testTaskPath) {
        this.testTaskPath = testTaskPath;
    }

    /**
     * Complete this runner's claimed group and, if elected, seal the distributed build.
     *
     * <p>Reads this build's {@link DistributedClaimRegistry} for the claim recorded under {@link
     * #testTaskPath}. No claim, or a claim with no group number (a surplus runner that claimed
     * nothing because the pipeline fanned out wider than the plan's group count), both mean there is
     * nothing to complete and nothing to seal - logged at INFO and treated as success, never as a
     * failure. Otherwise the claimed group is completed and, on success, this runner stands for
     * election to seal the build - see {@link DistributedRunCompleter#completeAndSeal} for what each
     * step does and why a rejected completion (superseded, or already completed) is a normal
     * outcome rather than a failure here.
     *
     * @throws GradleException if completing the group or sealing the build fails unexpectedly (for
     *                          example, the datastore is unreachable), naming that this build was
     *                          NOT sealed and the next build will redo the run's work
     */
    @TaskAction
    public void run() {
        DistributedClaimRegistry.Claim claim =
                DistributedClaimRegistry.forBuild(getProject().getGradle()).claimFor(testTaskPath);

        if (claim == null) {
            LOGGER.info("No distributed run claim was recorded for test task '" + testTaskPath
                    + "' in this build - this was not a distributed run, so there is nothing to "
                    + "complete.");
            return;
        }

        if (claim.getGroupNumber() == null) {
            LOGGER.info("Distributed run '" + claim.getRunId() + "': test task '" + testTaskPath
                    + "' claimed no group, so it has nothing to complete and nothing to seal.");
            return;
        }

        DistributedRunnerContext context = DistributedRunnerContext.forClaimedGroup(claim.getRunId(),
                claim.getRunnerKey(), claim.getGroupNumber().intValue());
        completeAndSeal(context, claim);
    }

    /**
     * Open the shared datastore and hand off to {@link DistributedRunCompleter#completeAndSeal} for
     * the completion and, when elected, the seal.
     *
     * <p>A failure is reported differently depending on which side of the completion/seal barrier it
     * happened on, mirroring {@code AbstractTiaDistCompleteMojo#completeAndSeal}, which is why this
     * catches {@link DistributedRunCompleter.SealFailedAfterCompletionException} separately from any
     * other {@link RuntimeException}: a failure while completing the group (or opening the
     * datastore) leaves the group exactly as it was, safe for the next build to redo; a failure
     * while sealing happens only after the group already flipped to {@code COMPLETED}, so the
     * message must say the group was completed rather than imply completion itself failed. A third
     * case falls into the generic catch too: {@link DistributedRunCompleter#completeAndSeal}'s return
     * value is tracked in {@code groupCompleted} so that a failure closing the datastore - part of
     * this method's try-with-resources - after that call already returned successfully is told apart
     * from a genuine completion failure, since the group has, in that case, already completed (and
     * possibly sealed) despite the close failure.
     *
     * @param context the claimed runner context built from the claim record
     * @param claim the claim record whose update-DB flags the seal uses - never the flags read
     *              afresh from the extension, which may not agree with what the claimed test task
     *              actually ran under
     * @throws GradleException if completing the group or sealing the build fails, or if the
     *                          datastore fails to close afterwards; the message distinguishes which
     *                          of the three happened
     */
    private void completeAndSeal(final DistributedRunnerContext context,
                                 final DistributedClaimRegistry.Claim claim) {
        boolean groupCompleted = false;
        try (DataStore dataStore = plugin.buildDataStore(plugin.getVCSReader().getBranchName())) {
            groupCompleted = DistributedRunCompleter.completeAndSeal(dataStore, context,
                    claim.isUpdateDBMapping(), claim.isUpdateDBTestRunHistory(),
                    System.currentTimeMillis());
        } catch (DistributedRunCompleter.SealFailedAfterCompletionException e) {
            throw new GradleException("Distributed run '" + context.getRunId() + "': runner '"
                    + context.getRunnerKey() + "' completed group " + context.getGroupNumber()
                    + ", but sealing the build failed - the group is now marked COMPLETED even "
                    + "though the run was NOT sealed: " + e.getCause().getMessage(), e.getCause());
        } catch (RuntimeException e) {
            if (groupCompleted) {
                throw new GradleException("Distributed run '" + context.getRunId() + "': runner '"
                        + context.getRunnerKey() + "' completed group " + context.getGroupNumber()
                        + ", but closing the datastore afterwards failed - the group's completion "
                        + "(and any resulting seal) already took effect despite this failure: "
                        + e.getMessage(), e);
            }
            throw new GradleException("Distributed run '" + context.getRunId() + "': runner '"
                    + context.getRunnerKey() + "' could not complete group " + context.getGroupNumber()
                    + " - this build will NOT be sealed, and the next build will redo the work: "
                    + e.getMessage(), e);
        }
    }
}
