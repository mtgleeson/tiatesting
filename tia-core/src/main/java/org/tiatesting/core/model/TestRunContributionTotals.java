package org.tiatesting.core.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The figures a set of {@link TestRunContribution}s adds up to - a whole run, one distributed group,
 * or one attempt kind of either. Each figure is defined so a set holding one JVM's contributions
 * gives that JVM's own figures. See the "Test run history" chapter in {@code WIKI.md}.
 */
public final class TestRunContributionTotals {

    private static final TestRunContributionTotals EMPTY = new TestRunContributionTotals(true, 0,
            Collections.<String>emptySet(), Collections.<String>emptySet(), 0L, 0L, 0L, 0L, 0L);

    private final boolean empty;
    private final int suitesRan;
    private final Set<String> failedSuites;
    private final Set<String> latestOutcomeFailedSuites;
    private final long testTimeMs;
    private final long suitesTimeMs;
    private final long wallClockMs;
    private final long longestJvmTestTimeMs;
    private final long firstStartMs;

    /**
     * Store computed totals; use {@link #of(Collection)}.
     *
     * @param empty whether no contribution went in
     * @param suitesRan the summed suites ran
     * @param failedSuites the union of every contribution's failed suites
     * @param latestOutcomeFailedSuites the suites whose most recent execution failed
     * @param testTimeMs the summed plan time
     * @param suitesTimeMs the summed suite-attributable time
     * @param wallClockMs the latest end minus the earliest start, over contributions that ran a
     *                    suite when any did
     * @param longestJvmTestTimeMs the largest per-JVM sum of plan time
     * @param firstStartMs the earliest plan start, over contributions that ran a suite when any did
     */
    private TestRunContributionTotals(boolean empty, int suitesRan, Set<String> failedSuites,
                                      Set<String> latestOutcomeFailedSuites, long testTimeMs,
                                      long suitesTimeMs, long wallClockMs, long longestJvmTestTimeMs,
                                      long firstStartMs) {
        this.empty = empty;
        this.suitesRan = suitesRan;
        this.failedSuites = Collections.unmodifiableSet(failedSuites);
        this.latestOutcomeFailedSuites = Collections.unmodifiableSet(latestOutcomeFailedSuites);
        this.testTimeMs = testTimeMs;
        this.suitesTimeMs = suitesTimeMs;
        this.wallClockMs = wallClockMs;
        this.longestJvmTestTimeMs = longestJvmTestTimeMs;
        this.firstStartMs = firstStartMs;
    }

    /**
     * Add up a set of contributions.
     *
     * <p>Latest-outcome failures start from every first-attempt row's failures; rerun rows are then
     * applied in {@code last_end_ms} order, so a suite a later rerun ran and passed leaves the set
     * and one it ran and failed stays in it. A suite runs once in a first attempt, so first-attempt
     * rows never disagree with each other.
     *
     * <p>The wall clock and first start are measured over the contributions that ran at least one
     * suite, falling back to every contribution when none did. A JVM that ran nothing - a Gradle
     * test-retry round's worker that was handed no class, or a fork that only skipped classes Tia
     * ignored - can start long after the work it sits beside, and would otherwise stretch the
     * elapsed time by the gap. Its plan time still counts in the summed test time, since the build
     * really spent it.
     *
     * @param contributions the contributions to add up; null or empty gives the empty totals
     * @return the totals
     */
    public static TestRunContributionTotals of(final Collection<TestRunContribution> contributions) {
        if (contributions == null || contributions.isEmpty()) {
            return EMPTY;
        }

        int suitesRan = 0;
        long testTimeMs = 0L;
        long suitesTimeMs = 0L;
        long earliestStartMs = Long.MAX_VALUE;
        long latestEndMs = Long.MIN_VALUE;
        long earliestRanStartMs = Long.MAX_VALUE;
        long latestRanEndMs = Long.MIN_VALUE;
        Set<String> failedSuites = new LinkedHashSet<>();
        Set<String> latestOutcomeFailed = new LinkedHashSet<>();
        Map<String, Long> testTimeByJvm = new HashMap<>();
        List<TestRunContribution> reruns = new ArrayList<>();

        for (TestRunContribution contribution : contributions) {
            suitesRan += contribution.getSuitesRan();
            testTimeMs += contribution.getTestTimeMs();
            suitesTimeMs += contribution.getSuitesTimeMs();
            earliestStartMs = Math.min(earliestStartMs, contribution.getFirstStartMs());
            latestEndMs = Math.max(latestEndMs, contribution.getLastEndMs());
            if (contribution.getSuitesRan() > 0) {
                earliestRanStartMs = Math.min(earliestRanStartMs, contribution.getFirstStartMs());
                latestRanEndMs = Math.max(latestRanEndMs, contribution.getLastEndMs());
            }
            failedSuites.addAll(contribution.getSuitesFailed());
            testTimeByJvm.merge(contribution.getJvmId(), contribution.getTestTimeMs(), Long::sum);
            if (contribution.isRerun()) {
                reruns.add(contribution);
            } else {
                latestOutcomeFailed.addAll(contribution.getSuitesFailed());
            }
        }

        reruns.sort(Comparator.comparingLong(TestRunContribution::getLastEndMs));
        for (TestRunContribution rerun : reruns) {
            for (String suite : rerun.getRerunSuites()) {
                if (rerun.getSuitesFailed().contains(suite)) {
                    latestOutcomeFailed.add(suite);
                } else {
                    latestOutcomeFailed.remove(suite);
                }
            }
        }

        long longestJvmTestTimeMs = 0L;
        for (Long jvmTestTimeMs : testTimeByJvm.values()) {
            longestJvmTestTimeMs = Math.max(longestJvmTestTimeMs, jvmTestTimeMs.longValue());
        }

        if (earliestRanStartMs != Long.MAX_VALUE) {
            earliestStartMs = earliestRanStartMs;
            latestEndMs = latestRanEndMs;
        }
        return new TestRunContributionTotals(false, suitesRan, failedSuites, latestOutcomeFailed,
                testTimeMs, suitesTimeMs, Math.max(0L, latestEndMs - earliestStartMs),
                longestJvmTestTimeMs, earliestStartMs);
    }

    /** @return true when no contribution went in */
    public boolean isEmpty() { return empty; }

    /** @return the summed suites ran: each suite once for first-attempt rows */
    public int getSuitesRan() { return suitesRan; }

    /** @return the union of every contribution's failed suites */
    public Set<String> getFailedSuites() { return failedSuites; }

    /** @return the suites whose most recent execution failed, across first attempt and reruns */
    public Set<String> getLatestOutcomeFailedSuites() { return latestOutcomeFailedSuites; }

    /** @return the summed plan time: the serial-equivalent test time */
    public long getTestTimeMs() { return testTimeMs; }

    /** @return the summed suite-attributable time */
    public long getSuitesTimeMs() { return suitesTimeMs; }

    /**
     * @return the latest end minus the earliest start: the real elapsed time, over the contributions
     *         that ran a suite when any did
     */
    public long getWallClockMs() { return wallClockMs; }

    /** @return the largest per-JVM sum of plan time, both attempt kinds of a JVM together */
    public long getLongestJvmTestTimeMs() { return longestJvmTestTimeMs; }

    /**
     * @return the earliest plan start, epoch millis, over the contributions that ran a suite when any
     *         did; zero for the empty totals
     */
    public long getFirstStartMs() { return firstStartMs; }
}
