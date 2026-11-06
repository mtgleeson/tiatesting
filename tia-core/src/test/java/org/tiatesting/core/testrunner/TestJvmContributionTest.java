package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunContribution;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how a test JVM accumulates its running totals across test plans.
 */
class TestJvmContributionTest {

    private static Set<String> names(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static TestPlanOutcome plan(Set<String> ran, Set<String> failed) {
        return new TestPlanOutcome(ran, failed, ran, Collections.<String>emptySet());
    }

    @Test
    void surefirePerClassPlansAddUpIntoOneFirstAttemptRow() {
        // given a fork that runs one class per test plan
        TestJvmContribution jvm = new TestJvmContribution("jvm-a");

        // when
        jvm.record(false, plan(names("A"), names()), 1000L, 1400L, 0L);
        jvm.record(false, plan(names("B"), names("B")), 1400L, 1700L, 0L);
        List<TestRunContribution> rows = jvm.snapshot("run-1", null);

        // then
        assertEquals(1, rows.size());
        TestRunContribution row = rows.get(0);
        assertFalse(row.isRerun());
        assertEquals(2, row.getSuitesRan());
        assertEquals(names("B"), row.getSuitesFailed());
        assertEquals(700L, row.getTestTimeMs());
        assertEquals(1000L, row.getFirstStartMs());
        assertEquals(1700L, row.getLastEndMs());
    }

    @Test
    void aPassingSameJvmRerunLeavesTheFailedSuites() {
        // given a fork whose class failed and then passed on a Surefire rerun
        TestJvmContribution jvm = new TestJvmContribution("jvm-a");
        jvm.record(false, plan(names("A"), names("A")), 1000L, 1400L, 0L);

        // when
        jvm.record(true, plan(names("A"), names()), 1400L, 1500L, 0L);

        // then
        assertTrue(jvm.failedSuites().isEmpty());
        List<TestRunContribution> rows = jvm.snapshot("run-1", 3);
        assertEquals(2, rows.size());
        TestRunContribution rerun = rows.get(1);
        assertTrue(rerun.isRerun());
        assertEquals(names("A"), rerun.getRerunSuites());
        assertEquals(Integer.valueOf(3), rerun.getGroupNumber());
    }

    @Test
    void theSuiteTimeIsTheLargestCumulativeFigureRecorded() {
        // given a first plan that timed 30s of suite work; the figure is cumulative per JVM
        TestJvmContribution jvm = new TestJvmContribution("jvm-a");
        jvm.record(false, plan(names("A", "B"), names("A")), 1000L, 35_000L, 30_000L);

        // when a retry re-reports the same cumulative total, then a late plan a smaller one
        jvm.record(true, plan(names("A"), names()), 35_000L, 41_000L, 30_000L);
        jvm.record(false, plan(names("C"), names()), 41_000L, 42_000L, 900L);

        // then the suite time neither accumulates nor regresses, while the plan time does add up
        TestRunContribution firstAttempt = jvm.snapshot("run-1", 0).get(0);
        assertEquals(30_000L, firstAttempt.getSuitesTimeMs());
        assertEquals(35_000L, firstAttempt.getTestTimeMs());
    }

    @Test
    void aFreshJvmHasRecordedNoPlans() {
        // given
        // when
        TestJvmContribution jvm = new TestJvmContribution();

        // then
        assertFalse(jvm.hasRecordedPlans());
        assertTrue(jvm.snapshot("run-1", null).isEmpty());
    }
}
