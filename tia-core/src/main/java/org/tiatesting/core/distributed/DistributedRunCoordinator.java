package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.persistence.DataStore;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The runner-side half of a distributed run, and the mirror of {@link DistributedRunPlanner}: the
 * planner decides how the build is split, this decides which slice the calling runner owns and
 * therefore which suites it must skip. Both build tools call {@link #claim} once before the test
 * JVM forks and then {@link #deriveTestsToIgnore} to produce the set they write to the ignore
 * file. Nothing in this class touches a filesystem or a build-tool API, which is what keeps it
 * unit-testable against a real datastore without either.
 *
 * <p><b>Why two of the three claim outcomes are exceptions.</b> A runner is a CI job that reports
 * pass or fail; it has no way to say "I could not tell whether I was supposed to run anything".
 * So the only outcome allowed to be quiet is the one where quiet is the truth - the run exists, is
 * valid for this workspace, and simply has no group left, which is what a surplus runner outside
 * the plan's fan-out legitimately finds. The other two mean this runner cannot know whether its
 * share of the suite ran, and exiting successfully would report a green build for untested code:
 * <ul>
 *   <li><b>No run row.</b> Writing a plan clears the previous run's rows, so a job from a
 *       superseded build finds nothing. Its tests were never going to run under this id.</li>
 *   <li><b>Commit mismatch.</b> The plan's suite lists were chosen by diffing one commit; a
 *       workspace on another commit would run a selection made for different code.</li>
 * </ul>
 *
 * <p>The run is looked up by the configured run id, never by "the only run in the table". On H2
 * the plan write's retention clear is a {@code DELETE FROM} that locks nothing on an empty table,
 * so two planners racing can both leave a row behind, and a coordinator that assumed a single row
 * would then attach the wrong runner to the wrong build.
 *
 * <p>See the "Group assignment" chapter in {@code WIKI.md} for the claim protocol this wraps.
 */
public final class DistributedRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DistributedRunCoordinator.class);

    private final DataStore dataStore;
    private final DistributedRunConfig config;

    /**
     * Build a coordinator bound to one datastore and one validated run configuration.
     *
     * @param dataStore the shared datastore holding the plan; must be the same store every other
     *                  runner in this distributed run reads and claims from
     * @param config the validated run configuration supplying the run id every operation here is
     *               keyed by, and the optional runner key {@link #resolveRunnerKey} falls back from
     */
    public DistributedRunCoordinator(DataStore dataStore, DistributedRunConfig config) {
        this.dataStore = dataStore;
        this.config = config;
    }

    /**
     * Claim this runner's slice of the planned run, after verifying the run exists and was planned
     * against the commit this runner has checked out.
     *
     * <p>Runs, in order: (1) reads the run by the configured run id, throwing if it is absent;
     * (2) compares the plan's commit with the workspace's, throwing if they differ; (3) resolves
     * the runner key via {@link #resolveRunnerKey}; and (4) claims one {@code PENDING} group via
     * {@link DataStore#claimNextPendingGroup}, which is where two runners racing for the same group
     * are serialised.
     *
     * @param workspaceCommitValue the VCS commit this runner's workspace is on; must equal the
     *                             commit the plan was built against, since the plan's suite lists
     *                             were chosen by diffing that commit
     * @param claimedAtMs the UTC epoch millis to record as the claim time; supplied by the caller
     *                    rather than read from the clock here, so tests can assert the persisted
     *                    value exactly
     * @return a claimed outcome carrying this runner's group, or a no-op outcome when every group
     *         was already claimed - a legitimate surplus runner, not a failure
     * @throws IllegalStateException if no run is planned under the configured run id (this build
     *                                was superseded, or never planned), or if the plan's commit
     *                                differs from {@code workspaceCommitValue}; both messages name
     *                                the run id, and the commit one names both commits
     */
    public ClaimOutcome claim(final String workspaceCommitValue, final long claimedAtMs) {
        DistributedRun run = readRunOrThrow();

        String planCommitValue = run.getCommitValue();
        if (planCommitValue == null ? workspaceCommitValue != null
                : !planCommitValue.equals(workspaceCommitValue)) {
            throw new IllegalStateException("Distributed run '" + config.getRunId()
                    + "' was planned against commit '" + planCommitValue
                    + "' but this runner's workspace is on commit '" + workspaceCommitValue
                    + "'. The plan's test selection was made by diffing '" + planCommitValue
                    + "', so running it here would test different code than it was chosen for. "
                    + "Check out the planned commit on every runner, or re-run tia-dist-plan for "
                    + "the commit the runners are actually on.");
        }

        String runnerKey = resolveRunnerKey();
        DistributedRunGroup group = dataStore.claimNextPendingGroup(config.getRunId(), runnerKey,
                claimedAtMs);

        if (group == null) {
            log.info("Distributed run '{}' has no group left for runner '{}' - all {} group(s) "
                            + "were already claimed. This runner has nothing to run, which is "
                            + "expected when the pipeline fans out to more jobs than the plan has "
                            + "groups.",
                    config.getRunId(), runnerKey, run.getGroupCount());
            return ClaimOutcome.nothingToClaim(runnerKey);
        }

        log.info("Runner '{}' claimed group {} of {} in distributed run '{}'.",
                runnerKey, group.getGroupNumber(), run.getGroupCount(), config.getRunId());
        return ClaimOutcome.claimed(group, runnerKey);
    }

    /**
     * Work out which test suites the runner holding a given group must skip, so that between them
     * the run's runners execute each suite exactly once.
     *
     * <p>The rule is {@code (trackedSuiteNames union every suite in the plan) minus this group's
     * suites}. The union with the plan's own suites is not redundant: a brand-new test class has no
     * mapping yet, so it appears in the plan but not in the tracked set, and without the union it
     * would be absent from every runner's ignore list and every runner would run it - turning one
     * new suite into as many duplicate executions as there are groups.
     *
     * <p>A seed run needs no special case. One group, no suites in the plan, nothing tracked: the
     * union is empty and so is the result, so the single runner runs the whole suite and records
     * the mapping the next build plans from.
     *
     * @param groupNumber the group this runner claimed, whose suites are the ones it will run
     * @param trackedSuiteNames every suite Tia currently has a mapping for; not modified
     * @return the suite names this runner must not execute; a new mutable set owned by the caller
     * @throws IllegalStateException if no run is planned under the configured run id - with no plan
     *                                to read, the union would be empty and the runner would run
     *                                every tracked suite, duplicating the whole build
     * @throws IllegalArgumentException if the plan has no group with that number - left unchecked
     *                                   it would subtract nothing, so the runner would ignore every
     *                                   suite in the plan and run none of them while still
     *                                   reporting success
     */
    public Set<String> deriveTestsToIgnore(final int groupNumber, final Set<String> trackedSuiteNames) {
        readRunOrThrow();

        List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups(config.getRunId());
        boolean groupExists = false;
        Set<String> testsToIgnore = new HashSet<>(trackedSuiteNames);
        for (DistributedRunGroup group : groups) {
            if (group.getGroupNumber() == groupNumber) {
                groupExists = true;
            }
            testsToIgnore.addAll(dataStore.readDistributedRunGroupSuites(config.getRunId(),
                    group.getGroupNumber()));
        }

        if (!groupExists) {
            throw new IllegalArgumentException("Distributed run '" + config.getRunId()
                    + "' has no group " + groupNumber + " - it was planned with " + groups.size()
                    + " group(s). A runner cannot derive an ignore list for a group that is not "
                    + "in the plan.");
        }

        testsToIgnore.removeAll(dataStore.readDistributedRunGroupSuites(config.getRunId(), groupNumber));
        return testsToIgnore;
    }

    /**
     * Read the run this coordinator is configured for, treating its absence as a hard failure
     * rather than an empty result. Shared by {@link #claim} and {@link #deriveTestsToIgnore} so
     * neither can be the one that quietly tolerates a missing run: a runner whose plan has been
     * cleared by a superseding build must fail, in both operations, rather than run nothing and
     * report success.
     *
     * @return the run planned under the configured run id
     * @throws IllegalStateException if no run is planned under that id
     */
    private DistributedRun readRunOrThrow() {
        DistributedRun run = dataStore.readDistributedRun(config.getRunId());
        if (run == null) {
            throw new IllegalStateException("No distributed run is planned under tiaRunId '"
                    + config.getRunId() + "'. Either this build was superseded by a later one, "
                    + "whose plan cleared these rows, or tia-dist-plan was never run for this id. "
                    + "This runner cannot know which tests it was meant to run, so it fails rather "
                    + "than reporting a passing build that ran nothing.");
        }
        return run;
    }

    /**
     * Resolve the identity this runner claims under: the configured {@code tiaDistributedRunnerKey}
     * when set, otherwise one derived from the run id, hostname and process id. The fallback lives
     * here rather than in each build tool so Maven and Gradle runners derive it identically.
     *
     * <p>The derived key is unique per runner but <b>not stable across CI job attempts</b>, since a
     * retried job is a new process. A retry therefore does not re-claim the group its first attempt
     * held; it finds no {@code PENDING} group and exits as a no-op. That is safe - it never runs
     * another runner's suites - but it does mean retries cannot rescue a run. Setting
     * {@code tiaDistributedRunnerKey} to a value the CI system keeps stable across attempts (a job
     * or matrix index rather than a build number) is what enables retry to re-claim.
     *
     * @return the runner identity to claim with; never null or blank
     */
    private String resolveRunnerKey() {
        String configuredKey = config.getRunnerKey();
        if (configuredKey != null && !configuredKey.isEmpty()) {
            return configuredKey;
        }
        return config.getRunId() + "-" + hostname() + "-" + processId();
    }

    /**
     * Read this host's name for the derived runner key, degrading to a constant rather than
     * failing when the name cannot be resolved - a runner key only has to be unique among the
     * runners of one build, and the process id already carries most of that uniqueness, so an
     * unresolvable hostname is not worth failing a build over.
     *
     * @return the local hostname, or {@code "unknown-host"} if it cannot be resolved
     */
    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.debug("Could not resolve the local hostname for the derived distributed-run runner "
                    + "key; using 'unknown-host'.", e);
            return "unknown-host";
        }
    }

    /**
     * Read this JVM's process id for the derived runner key. Uses the {@code pid@host} form of
     * {@link java.lang.management.RuntimeMXBean#getName()} because {@code ProcessHandle.current()}
     * is Java 9 and this module targets Java 8; the format is not specified by the JLS, so an
     * unexpected shape degrades to the whole name rather than throwing.
     *
     * @return this JVM's process id, or the whole runtime name if it does not carry one
     */
    private static String processId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int atIndex = runtimeName.indexOf('@');
        return atIndex > 0 ? runtimeName.substring(0, atIndex) : runtimeName;
    }
}
