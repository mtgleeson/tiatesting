package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how {@link TestRunContributionTotals} adds up the contributions of a run's test JVMs.
 */
class TestRunContributionTotalsTest {

    private static Set<String> names(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static TestRunContribution first(String jvmId, int ran, Set<String> failed, long timeMs,
                                             long suitesTimeMs, long startMs, long endMs) {
        return new TestRunContribution("run-1", jvmId, false, null, ran, failed,
                Collections.<String>emptySet(), timeMs, suitesTimeMs, startMs, endMs);
    }

    private static TestRunContribution rerun(String jvmId, Set<String> rerunSuites, Set<String> failed,
                                             long timeMs, long startMs, long endMs) {
        return new TestRunContribution("run-1", jvmId, true, null, rerunSuites.size(), failed,
                rerunSuites, timeMs, 0L, startMs, endMs);
    }

    @Test
    void aJvmThatRanNothingDoesNotStretchTheWallClock() {
        // given two forks that ran side by side, and a test-retry worker handed no class, which
        // started long after them
        List<TestRunContribution> contributions = Arrays.asList(
                first("a", 2, Collections.<String>emptySet(), 60L, 0L, 1000L, 1060L),
                first("b", 1, Collections.<String>emptySet(), 70L, 0L, 1005L, 1075L),
                first("idle", 0, Collections.<String>emptySet(), 30L, 0L, 2600L, 2630L));

        // when
        TestRunContributionTotals totals = TestRunContributionTotals.of(contributions);

        // then the elapsed time is the working forks', while their time still adds up
        assertEquals(75L, totals.getWallClockMs());
        assertEquals(1000L, totals.getFirstStartMs());
        assertEquals(160L, totals.getTestTimeMs());
    }

    @Test
    void aRunWhereNothingRanStillHasAWallClock() {
        // given a run that selected nothing, so its one JVM ran nothing
        List<TestRunContribution> contributions = Collections.singletonList(
                first("a", 0, Collections.<String>emptySet(), 50L, 0L, 1000L, 1050L));

        // when
        TestRunContributionTotals totals = TestRunContributionTotals.of(contributions);

        // then
        assertEquals(50L, totals.getWallClockMs());
        assertEquals(1000L, totals.getFirstStartMs());
    }

    @Test
    void addsUpSeveralJvmsOfOneRun() {
        // given three forks that ran side by side
        TestRunContribution a = first("jvm-a", 3, names("A"), 3000L, 2500L, 1000L, 4000L);
        TestRunContribution b = first("jvm-b", 2, names(), 2000L, 1800L, 1100L, 3100L);
        TestRunContribution c = first("jvm-c", 2, names("C"), 2500L, 2000L, 1200L, 3700L);

        // when
        TestRunContributionTotals totals = TestRunContributionTotals.of(Arrays.asList(a, b, c));

        // then
        assertEquals(7, totals.getSuitesRan());
        assertEquals(names("A", "C"), totals.getFailedSuites());
        assertEquals(7500L, totals.getTestTimeMs());
        assertEquals(6300L, totals.getSuitesTimeMs());
        assertEquals(3000L, totals.getWallClockMs());
        assertEquals(3000L, totals.getLongestJvmTestTimeMs());
    }

    @Test
    void aPassingRetryInAnotherJvmClearsTheLatestOutcomeFailure() {
        // given a failure in the first JVM that passes in a Gradle retry JVM
        TestRunContribution firstAttempt = first("jvm-a", 2, names("A", "B"), 2000L, 0L, 1000L, 3000L);
        TestRunContribution retry = rerun("jvm-r", names("A", "B"), names("B"), 500L, 3500L, 4000L);

        // when
        TestRunContributionTotals totals = TestRunContributionTotals.of(Arrays.asList(firstAttempt, retry));

        // then
        assertEquals(names("B"), totals.getLatestOutcomeFailedSuites());
        assertEquals(names("A", "B"), totals.getFailedSuites());
    }

    @Test
    void theLongestJvmCountsAllOfThatJvmsKinds() {
        // given one JVM with a first attempt and a same-JVM rerun, and a shorter second JVM
        TestRunContribution firstAttempt = first("jvm-a", 2, names("A"), 2000L, 0L, 1000L, 3000L);
        TestRunContribution sameJvmRerun = rerun("jvm-a", names("A"), names(), 400L, 3000L, 3400L);
        TestRunContribution other = first("jvm-b", 1, names(), 2200L, 0L, 1000L, 3200L);

        // when
        TestRunContributionTotals totals = TestRunContributionTotals.of(
                Arrays.asList(firstAttempt, sameJvmRerun, other));

        // then
        assertEquals(2400L, totals.getLongestJvmTestTimeMs());
    }

    @Test
    void noContributionsAreEmptyTotals() {
        // given
        // when
        TestRunContributionTotals totals = TestRunContributionTotals.of(Collections.<TestRunContribution>emptyList());

        // then
        assertTrue(totals.isEmpty());
        assertEquals(0, totals.getSuitesRan());
        assertEquals(0L, totals.getWallClockMs());
    }
}
