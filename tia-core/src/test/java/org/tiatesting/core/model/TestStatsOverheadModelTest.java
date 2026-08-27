package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cover the rolling averages the two overhead constants are kept as.
 *
 * <p>They behave like {@code avgRunTime}: each measurement is folded in against the count of the
 * measurements before it, so no single build's numbers dominate. The two share one count, because
 * they are always solved together from one build's group rows and so can never have contributed
 * different run counts.
 */
class TestStatsOverheadModelTest {

    /**
     * A project that has never distributed a build reports both constants as zero, which is the
     * signal the estimate falls back on rather than a measurement of a run with no overhead.
     */
    @Test
    void bothConstantsStartAtZeroWithNoMeasurements() {
        // given
        TestStats stats = new TestStats();

        // when - nothing is folded in

        // then
        assertEquals(0L, stats.getFixedOverheadMs(), "an unmeasured project has no fixed overhead");
        assertEquals(0L, stats.getCaptureOverheadPerSuiteMs(),
                "an unmeasured project has no capture overhead");
        assertEquals(0L, stats.getNumOverheadMeasurements(),
                "an unmeasured project has contributed no measurements");
    }

    /**
     * The first measurement is stored as-is - there is nothing to average it against.
     */
    @Test
    void theFirstMeasurementIsStoredAsItStands() {
        // given
        TestStats stats = new TestStats();

        // when
        stats.incrementOverheadModel(150L, 50L);

        // then
        assertEquals(150L, stats.getFixedOverheadMs(), "the first fixed measurement stands alone");
        assertEquals(50L, stats.getCaptureOverheadPerSuiteMs(),
                "the first capture measurement stands alone");
        assertEquals(1L, stats.getNumOverheadMeasurements(), "one build has contributed");
    }

    /**
     * Later measurements are averaged against everything folded in before them, so one unusually
     * loaded CI runner moves the stored constants by a fraction rather than replacing them.
     */
    @Test
    void laterMeasurementsAreAveragedAgainstTheOnesBeforeThem() {
        // given
        TestStats stats = new TestStats();
        stats.incrementOverheadModel(100L, 20L);
        stats.incrementOverheadModel(200L, 40L);

        // when
        stats.incrementOverheadModel(300L, 60L);

        // then
        assertEquals(200L, stats.getFixedOverheadMs(),
                "100, 200 and 300 average to 200, not to the last value");
        assertEquals(40L, stats.getCaptureOverheadPerSuiteMs(),
                "20, 40 and 60 average to 40, not to the last value");
        assertEquals(3L, stats.getNumOverheadMeasurements(), "three builds have contributed");
    }

    /**
     * The two constants keep one shared count. Folding a measurement in advances the count once, so
     * the pair can never drift into averaging over different numbers of builds - which would make
     * the two halves of the same equation disagree about which builds they describe.
     */
    @Test
    void theTwoConstantsShareOneMeasurementCount() {
        // given
        TestStats stats = new TestStats();

        // when
        stats.incrementOverheadModel(100L, 20L);
        stats.incrementOverheadModel(300L, 20L);

        // then
        assertEquals(2L, stats.getNumOverheadMeasurements(),
                "each solved pair advances the shared count exactly once");
        assertEquals(200L, stats.getFixedOverheadMs(), "the fixed part moved");
        assertEquals(20L, stats.getCaptureOverheadPerSuiteMs(),
                "the capture part is unchanged by two identical measurements, over the same count");
    }
}
