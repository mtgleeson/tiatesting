package org.tiatesting.core.distributed;

import org.tiatesting.core.persistence.DataStore;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One runner's share of an already-planned distributed run: the group it claimed, the identity it
 * claimed under, and the two suite lists that follow from those - the suites it must execute and
 * the suites it must skip.
 *
 * <p>This is the whole of the runner-side decision, and it lives here rather than in either build
 * tool because both make it. Maven claims in the build JVM before surefire forks; Gradle claims in
 * the daemon's test-task action before the test task forks. Both build tools therefore call
 * {@link #claim} in their build JVM and forward the resolved runner key and group number to the
 * forked test JVM, which calls {@link #forClaimedRunner} to re-derive the same two suite lists
 * without claiming again. The entry points differ, but the answer they need must not: if the Maven
 * and Gradle runners disagreed by even one suite about who ignores what, a suite would run twice or
 * not at all while both builds reported success.
 *
 * <p>Deliberately absent from this class is any repeat of the planning work. The plan already ran
 * the VCS diff, the static rules and the library-impact drain once, and its output is in the shared
 * database; a runner that re-ran the selection would pay for the diff again and, worse, drain the
 * pending library rows a second time, which races with every other runner doing the same. So the
 * runner reads the plan and claims - it never selects.
 *
 * <p>See the "Group assignment" chapter in {@code WIKI.md} for the claim protocol this sits on.
 */
public final class DistributedRunnerAssignment {

    private final String runnerKey;
    private final Integer groupNumber;
    private final Set<String> testsToIgnore;
    private final Set<String> testsToRun;

    /**
     * Store the resolved assignment. Private so instances can only come from {@link #claim} or
     * {@link #forClaimedRunner}, the only two callers that derive the two suite lists from the plan
     * rather than accepting them from elsewhere.
     *
     * @param runnerKey the identity the claim was made under
     * @param groupNumber the claimed group, or null when no group was left to claim
     * @param testsToIgnore the suite names this runner must not execute
     * @param testsToRun the suite names this runner is responsible for
     */
    private DistributedRunnerAssignment(String runnerKey, Integer groupNumber,
                                         Set<String> testsToIgnore, Set<String> testsToRun) {
        this.runnerKey = runnerKey;
        this.groupNumber = groupNumber;
        this.testsToIgnore = testsToIgnore;
        this.testsToRun = testsToRun;
    }

    /**
     * Claim this runner's group from the planned run and resolve the two suite lists that follow.
     *
     * <p>A claimed runner ignores every tracked or planned suite outside its own group and selects
     * its group's suites. A runner that finds every group already claimed - a surplus runner, which
     * a pipeline whose fan-out is wider than the plan's group count legitimately produces - ignores
     * every suite and selects none, so it runs nothing rather than duplicating another runner's
     * work.
     *
     * @param dataStore the shared datastore holding the plan; must be the same store every other
     *                  runner in this distributed run reads and claims from
     * @param config the validated run configuration naming the run to claim from
     * @param workspaceCommitValue the VCS commit this runner's workspace is on, checked against the
     *                             commit the plan was built by diffing
     * @param claimedAtMs the UTC epoch millis to record as the claim time
     * @return this runner's assignment, claimed or surplus
     * @throws IllegalStateException if no run is planned under the configured run id, or if the
     *                                plan was built against a different commit than this runner's
     *                                workspace is on; both are propagated rather than turned into
     *                                an empty assignment, because a runner that cannot tell whether
     *                                its share of the suite ran must fail rather than report a
     *                                green build for untested code
     */
    public static DistributedRunnerAssignment claim(final DataStore dataStore,
                                                     final DistributedRunConfig config,
                                                     final String workspaceCommitValue,
                                                     final long claimedAtMs) {
        DistributedRunCoordinator coordinator = new DistributedRunCoordinator(dataStore, config);
        ClaimOutcome outcome = coordinator.claim(workspaceCommitValue, claimedAtMs);

        Integer groupNumber = outcome.isClaimed()
                ? Integer.valueOf(outcome.getGroup().getGroupNumber()) : null;
        return forClaimedRunner(dataStore, config, outcome.getRunnerKey(), groupNumber);
    }

    /**
     * Build the assignment for a runner identity and group number a claim already resolved,
     * deriving the two suite lists from the plan and the tracked mapping rather than repeating the
     * claim itself.
     *
     * <p>This is the one place both build tools' entry points meet. Maven's {@link #claim} calls it
     * directly, right after claiming, because it claims and derives in the same call. The Gradle
     * daemon claims separately - it must, since the claim happens before the test task forks and
     * the derivation the fork needs happens after - so its fork calls this factory instead of
     * {@link #claim}, with the runner key and group number the daemon already resolved and forwarded
     * as system properties. Either caller lands on the identical derivation: a hand-written second
     * copy of it is exactly what would let the two build tools silently disagree about which suites
     * a runner skips.
     *
     * @param dataStore the shared datastore holding the plan; must be the same store the claim was
     *                  made against
     * @param config the validated run configuration naming the run the group was claimed from
     * @param runnerKey the identity the claim was recorded under; must be the exact value the claim
     *                  returned, not one re-derived here, or a later completion write would match no
     *                  row
     * @param groupNumber the group this runner claimed, or null when it claimed none - a surplus
     *                    runner, which runs nothing and ignores everything
     * @return the assignment for this runner key and group, claimed or surplus
     * @throws IllegalStateException if no run is planned under the configured run id
     * @throws IllegalArgumentException if a non-null {@code groupNumber} is not in the plan
     */
    public static DistributedRunnerAssignment forClaimedRunner(final DataStore dataStore,
                                                                final DistributedRunConfig config,
                                                                final String runnerKey,
                                                                final Integer groupNumber) {
        DistributedRunCoordinator coordinator = new DistributedRunCoordinator(dataStore, config);
        Set<String> testsToIgnore = coordinator.deriveTestsToIgnore(groupNumber,
                dataStore.getTestSuitesTracked().keySet());
        Set<String> testsToRun = groupNumber != null
                ? new LinkedHashSet<>(dataStore.readDistributedRunGroupSuites(config.getRunId(),
                        groupNumber))
                : Collections.<String>emptySet();

        return new DistributedRunnerAssignment(runnerKey, groupNumber, testsToIgnore, testsToRun);
    }

    /**
     * Report whether this runner holds a group, and therefore whether it has anything to run.
     * Callers branch on this rather than null-checking {@link #getGroupNumber()}.
     *
     * @return true if a group was claimed
     */
    public boolean isClaimed() { return groupNumber != null; }

    /**
     * The claimed group number, which a build tool must hand to the forked test JVM: the JVM that
     * runs the tests is the one that later reports the group complete, and it cannot work out which
     * group it was given from anything it can see itself.
     *
     * @return the claimed group number, or null when no group was left to claim
     */
    public Integer getGroupNumber() { return groupNumber; }

    /**
     * The identity the claim was recorded under, which may be one the coordinator derived rather
     * than one the user configured. A build tool must hand this exact value to the forked test JVM
     * rather than let it derive its own: a re-derived key would not match the one on the claimed
     * row, and the group would never be completed.
     *
     * @return the runner identity the claim was made under; never null or blank
     */
    public String getRunnerKey() { return runnerKey; }

    /** @return the suite names this runner must not execute; every suite when it claimed no group */
    public Set<String> getTestsToIgnore() { return testsToIgnore; }

    /** @return the suite names this runner is responsible for; empty when it claimed no group */
    public Set<String> getTestsToRun() { return testsToRun; }

    /**
     * Diagnostic rendering naming the runner, its group, and the size of each suite list.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "DistributedRunnerAssignment{runnerKey=" + runnerKey
                + ", group=" + (groupNumber == null ? "none" : String.valueOf(groupNumber))
                + ", testsToRun=" + testsToRun.size()
                + ", testsToIgnore=" + testsToIgnore.size() + "}";
    }
}
