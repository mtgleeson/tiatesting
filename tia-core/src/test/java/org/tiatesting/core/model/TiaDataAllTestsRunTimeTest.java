package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the Tia-level {@link TiaData#incrementStats(TestStats, boolean)} routes the run's
 * duration into the all-tests-run average or the selected-run average according to the
 * {@code allTestsRun} flag.
 */
class TiaDataAllTestsRunTimeTest {

    /**
     * A single run carrying a duration with {@code numRuns == 1}.
     */
    private static TestStats run(long durationMs){
        TestStats stats = new TestStats();
        stats.setNumRuns(1);
        stats.setAvgRunTime(durationMs);
        stats.setNumSuccessRuns(1);
        return stats;
    }

    /**
     * allTestsRun=true folds into the Tia-level all-tests average, leaving the selected average 0.
     */
    @Test
    void incrementStats_allTestsRun_updatesAllTestsAverage(){
        // given
        TiaData tiaData = new TiaData();

        // when
        tiaData.incrementStats(run(500), true);

        // then
        assertEquals(500, tiaData.getTestStats().getAllTestsRunTime());
        assertEquals(1, tiaData.getTestStats().getNumAllTestsRuns());
        assertEquals(0, tiaData.getTestStats().getAvgRunTime());
    }

    /**
     * allTestsRun=false folds into the Tia-level selected average, leaving the all-tests stats 0.
     */
    @Test
    void incrementStats_selectedRun_updatesSelectedAverage(){
        // given
        TiaData tiaData = new TiaData();

        // when
        tiaData.incrementStats(run(250), false);

        // then
        assertEquals(250, tiaData.getTestStats().getAvgRunTime());
        assertEquals(0, tiaData.getTestStats().getAllTestsRunTime());
        assertEquals(0, tiaData.getTestStats().getNumAllTestsRuns());
    }
}
