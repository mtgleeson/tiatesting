package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunContribution;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how a single-host run's history rows are built from its JVMs' contributions.
 */
class TestRunHistoryAssemblerTest {

    private static final long BASELINE_MS = 60_000L;

    /**
     * Build the run-constant fields of a run.
     *
     * @param ignored the suites Tia ignored
     * @param selected whether the selection expected any suite to run
     * @return the context
     */
    private static TestRunHistoryAssembler.RunContext context(int ignored, boolean selected) {
        return context(ignored, selected, false);
    }

    /**
     * Build the run-constant fields of a run.
     *
     * @param ignored the suites Tia ignored
     * @param selected whether the selection expected any suite to run
     * @param updatesMapping whether the run updated the mapping
     * @return the context
     */
    private static TestRunHistoryAssembler.RunContext context(int ignored, boolean selected,
                                                              boolean updatesMapping) {
        return new TestRunHistoryAssembler.RunContext("main", "c1", "run-1", 1000L, ignored, selected,
                updatesMapping, BASELINE_MS, RunOrigin.of(RunOrigin.SOURCE_LOCAL, "host"), TestRunSelectionDetails.empty());
    }

    private static TestRunContribution first(String jvmId, int ran, long timeMs, long startMs, long endMs,
                                             String... failed) {
        return new TestRunContribution("run-1", jvmId, false, null, ran, new HashSet<>(Arrays.asList(failed)),
                Collections.<String>emptySet(), timeMs, 0L, startMs, endMs);
    }

    @Test
    void threeForksMakeOneFirstAttemptRow() {
        // given
        List<TestRunContribution> contributions = Arrays.asList(
                first("a", 3, 3000L, 1000L, 4000L), first("b", 2, 2000L, 1000L, 3000L, "X"),
                first("c", 2, 2000L, 1000L, 3500L));

        // when
        List<TestRunHistoryEntry> rows = TestRunHistoryAssembler.assemble(contributions, context(4, true));

        // then
        assertEquals(1, rows.size());
        TestRunHistoryEntry row = rows.get(0);
        assertFalse(row.isRerun());
        assertEquals(7, row.getNumSuitesRan());
        assertEquals(1, row.getNumSuitesFailed());
        assertEquals(7000L, row.getDurationMs());
        assertEquals(Long.valueOf(3000L), row.getWallClockMs());
        assertEquals(BASELINE_MS - 7000L, row.getTimeSavingsMs());
        assertEquals(TestRunHistoryEntry.idFor("main", "c1", "run-1", false), row.getId());
    }

    @Test
    void rerunsGetTheirOwnRowWithNoSavings() {
        // given
        TestRunContribution rerun = new TestRunContribution("run-1", "a", true, null, 1,
                Collections.<String>emptySet(), Collections.singleton("X"), 500L, 0L, 5000L, 5500L);
        List<TestRunContribution> contributions = Arrays.asList(first("a", 2, 2000L, 1000L, 3000L, "X"), rerun);

        // when
        List<TestRunHistoryEntry> rows = TestRunHistoryAssembler.assemble(contributions, context(4, true));

        // then the rerun row has no savings and is stamped when the reruns started
        assertEquals(2, rows.size());
        assertTrue(rows.get(1).isRerun());
        assertEquals(0L, rows.get(1).getTimeSavingsMs());
        assertEquals(1000L, rows.get(0).getRunTimestampMs());
        assertEquals(5000L, rows.get(1).getRunTimestampMs());
    }

    @Test
    void aRunThatIgnoredEverythingKeepsItsSavings() {
        // given nothing impacted: no suite selected, so nothing ran, as intended
        List<TestRunContribution> contributions = Collections.singletonList(first("a", 0, 50L, 1000L, 1050L));

        // when
        List<TestRunHistoryEntry> rows = TestRunHistoryAssembler.assemble(contributions, context(11, false));

        // then
        assertEquals(BASELINE_MS - 50L, rows.get(0).getTimeSavingsMs());
    }

    @Test
    void aRunThatRanNoneOfItsSelectedSuitesSavesNothing() {
        // given a selection that expected suites, none of which ran
        List<TestRunContribution> contributions = Collections.singletonList(first("a", 0, 50L, 1000L, 1050L));

        // when
        List<TestRunHistoryEntry> rows = TestRunHistoryAssembler.assemble(contributions, context(4, true));

        // then
        assertEquals(0L, rows.get(0).getTimeSavingsMs());
    }

    @Test
    void aMappingRunThatRanNothingBecauseNothingWasImpactedIsStillAMappingUpdate() {
        // given a mapping run that selected nothing, so its one JVM ran nothing and it sealed
        List<TestRunContribution> contributions = Collections.singletonList(first("a", 0, 50L, 1000L, 1050L));

        // when
        List<TestRunHistoryEntry> rows = TestRunHistoryAssembler.assemble(contributions, context(11, false, true));

        // then
        assertTrue(rows.get(0).isUpdatedDbMapping());
    }
}
