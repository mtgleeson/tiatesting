package org.tiatesting.gradle.plugin;

import org.gradle.api.invocation.Gradle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The daemon's record of every distributed-run claim made during one build invocation, keyed by
 * the {@link Gradle} instance so a long-lived daemon serving many builds never lets one build's
 * claim leak into another's.
 *
 * <p>A distributed run supports exactly one test task per runner. The derived runner key
 * ({@code runId + hostname + pid}) is the daemon's, shared by every test task in the build, so a
 * second test task claiming in the same build would find its own key already holding a group -
 * {@code claimNextPendingGroup}'s step 0 hands back whatever group that key already holds rather
 * than claiming a fresh one - and the two test tasks would silently run and persist the same
 * group's suites while a different group sat {@code PENDING} forever, and the run would never
 * seal. Splitting a runner across two test tasks cannot be made to work even if the key collision
 * were fixed: the plan groups suites across the whole project, so a group can hold suites that
 * belong to a source set a different test task owns and could never run, and that task's claim
 * would never satisfy the completeness guard either way. This registry is what lets the first
 * claim in a build refuse every claim attempted after it, loudly, instead of the two test tasks'
 * claims silently colliding.
 *
 * <p>It is also the daemon's only surviving record of a claim once the test task's forked JVM(s)
 * have run and exited: the {@code tia-dist-complete} finalizer reads it back by test task path
 * to know which run, runner key, group and update-DB flags to seal with, since none of that
 * survives in the forked JVM once it exits.
 *
 * <p>See the "Distributed test runs" chapter in {@code WIKI.md} for the claim protocol this sits on.
 */
public final class DistributedClaimRegistry {

    private static final Map<Gradle, DistributedClaimRegistry> REGISTRIES = new WeakHashMap<>();

    private final Map<String, Claim> claimsByTaskPath = new LinkedHashMap<>();

    /**
     * Private: instances are only ever obtained through {@link #forBuild}, which is what keeps
     * one build's registry from being constructed twice under the same {@link Gradle} instance.
     */
    private DistributedClaimRegistry() {
    }

    /**
     * Resolve the single registry for one build invocation, creating it on first use.
     *
     * <p>Keyed by identity on the {@link Gradle} instance, which Gradle creates fresh per build
     * invocation even inside a long-lived daemon, so nothing about one build's claims is visible
     * to the next. Backed by a {@link WeakHashMap} so a finished build's registry becomes
     * reclaimable once nothing else references its {@code Gradle} instance, rather than
     * accumulating for the daemon's lifetime. The lookup and creation are synchronized on the map
     * itself so two test tasks whose {@code doFirst} actions run concurrently (a parallel build)
     * cannot each create and use their own registry for what must be one build's claims.
     *
     * @param gradle the current build invocation's {@link Gradle} instance
     * @return this build's claim registry, shared by every test task's claim attempt in it
     */
    public static DistributedClaimRegistry forBuild(final Gradle gradle) {
        synchronized (REGISTRIES) {
            DistributedClaimRegistry registry = REGISTRIES.get(gradle);
            if (registry == null) {
                registry = new DistributedClaimRegistry();
                REGISTRIES.put(gradle, registry);
            }
            return registry;
        }
    }

    /**
     * Record a test task's distributed-run claim, refusing a second test task's claim in the same
     * build.
     *
     * <p>Synchronized so two test tasks' {@code doFirst} actions racing in a parallel build cannot
     * both observe an empty registry and both record a claim: the second caller in, whichever task
     * that turns out to be, always sees the first one's entry and throws.
     *
     * @param testTaskPath the {@link org.gradle.api.Task#getPath()} of the test task making the
     *                      claim
     * @param runId the distributed run id the claim was made against
     * @param runnerKey the runner identity the claim was recorded under
     * @param groupNumber the group this test task claimed, or null for a surplus runner
     * @param updateDBMapping whether this test task updates the mapping database - the seal needs
     *                        this to decide whether the completed group's coverage should be
     *                        persisted
@@DROP1@@     *                       the same reason
     * @param updateDBTestRunHistory whether this test task records test-run history - the seal
     *                               needs this for the same reason
     * @return the recorded claim
     * @throws IllegalStateException if this build already recorded a claim under a different test
     *                                task path, naming both test tasks and stating that a
     *                                distributed run supports exactly one test task per runner
     */
    public synchronized Claim recordClaim(final String testTaskPath, final String runId,
            final String runnerKey, final Integer groupNumber, final boolean updateDBMapping,
            final boolean updateDBTestRunHistory) {
        for (Map.Entry<String, Claim> existing : claimsByTaskPath.entrySet()) {
            if (!existing.getKey().equals(testTaskPath)) {
                throw new IllegalStateException("Distributed test run '" + existing.getValue().getRunId()
                        + "' was already claimed by test task '" + existing.getKey() + "' in this build; "
                        + "test task '" + testTaskPath + "' attempted a second claim. A distributed run "
                        + "supports exactly one test task per runner: the plan groups suites across the "
                        + "whole project, so a second test task's group could hold suites the first task "
                        + "cannot run, the completeness guard would never be satisfied, and the run would "
                        + "never seal. Configure only one test task as distributed per runner - run the "
                        + "other test task's share of the plan as a separate runner (a separate CI "
                        + "job/process) instead.");
            }
        }

        Claim claim = new Claim(runId, runnerKey, groupNumber, updateDBMapping,
                updateDBTestRunHistory);
        claimsByTaskPath.put(testTaskPath, claim);
        return claim;
    }

    /**
     * Look up the claim recorded for a test task path, for the daemon-side {@code tia-dist-complete} finalizer to read back after that test task's forked JVM(s) have run and
     * exited.
     *
     * @param testTaskPath the {@link org.gradle.api.Task#getPath()} of the test task whose claim
     *                      is wanted
     * @return the recorded claim, or null if no claim was recorded under that path in this build
     */
    public synchronized Claim claimFor(final String testTaskPath) {
        return claimsByTaskPath.get(testTaskPath);
    }

    /**
     * One test task's recorded distributed-run claim: everything the {@code tia-dist-complete} finalizer needs to seal
     * with, since none of it survives in the forked test JVM once it exits.
     */
    public static final class Claim {
        private final String runId;
        private final String runnerKey;
        private final Integer groupNumber;
        private final boolean updateDBMapping;
        private final boolean updateDBTestRunHistory;

        /**
         * Store the claim's fields. Private so instances only come from {@link
         * DistributedClaimRegistry#recordClaim}, the one place a claim is recorded.
         *
         * @param runId the distributed run id the claim was made against
         * @param runnerKey the runner identity the claim was recorded under
         * @param groupNumber the claimed group, or null for a surplus runner
         * @param updateDBMapping whether this test task updates the mapping database
         * @param updateDBTestRunHistory whether this test task records test-run history
         */
        private Claim(final String runId, final String runnerKey, final Integer groupNumber,
                final boolean updateDBMapping,
                final boolean updateDBTestRunHistory) {
            this.runId = runId;
            this.runnerKey = runnerKey;
            this.groupNumber = groupNumber;
            this.updateDBMapping = updateDBMapping;
            this.updateDBTestRunHistory = updateDBTestRunHistory;
        }

        /** @return the distributed run id the claim was made against */
        public String getRunId() {
            return runId;
        }

        /** @return the runner identity the claim was recorded under */
        public String getRunnerKey() {
            return runnerKey;
        }

        /** @return the claimed group number, or null when this test task claimed a surplus (no group) */
        public Integer getGroupNumber() {
            return groupNumber;
        }

        /** @return whether this test task updates the mapping database */
        public boolean isUpdateDBMapping() {
            return updateDBMapping;
        }

        /** @return whether this test task updates run statistics */
        /** @return whether this test task records test-run history */
        public boolean isUpdateDBTestRunHistory() {
            return updateDBTestRunHistory;
        }
    }
}
