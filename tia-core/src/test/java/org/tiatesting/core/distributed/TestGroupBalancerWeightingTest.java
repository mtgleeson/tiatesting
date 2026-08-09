package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the weighting {@link TestGroupBalancer} applies before packing. The weights decide the
 * whole plan, so a systematic error here would skew every group without any test of the packing
 * algorithms noticing.
 */
class TestGroupBalancerWeightingTest {

    /**
     * Verify that with coverage collection off, the weights are exactly the per-suite run times
     * with nothing added. A non-mapping run does not pay the coverage-capture overhead.
     */
    @Test
    void shouldUsePerSuiteRunTimesUnchangedWhenNotCollectingCoverage() {
        // given
        Map<String, Long> perSuiteRunTimes = new HashMap<>();
        perSuiteRunTimes.put("A", 100L);
        perSuiteRunTimes.put("B", 200L);

        // when
        Map<String, Long> weights = TestGroupBalancer.suiteWeights(perSuiteRunTimes, 60L, false);

        // then
        assertEquals(100L, weights.get("A").longValue());
        assertEquals(200L, weights.get("B").longValue());
    }

    /**
     * Verify the whole-selection mapping overhead is divided back out to a per-suite figure and
     * added to each suite. The caller supplies the total because that is what the existing
     * run-time estimate reports.
     */
    @Test
    void shouldAddTheAmortisedMappingOverheadWhenCollectingCoverage() {
        // given
        Map<String, Long> perSuiteRunTimes = new HashMap<>();
        perSuiteRunTimes.put("A", 100L);
        perSuiteRunTimes.put("B", 200L);
        perSuiteRunTimes.put("C", 300L);

        // when
        // 90ms across 3 suites is 30ms each
        Map<String, Long> weights = TestGroupBalancer.suiteWeights(perSuiteRunTimes, 90L, true);

        // then
        assertEquals(130L, weights.get("A").longValue());
        assertEquals(230L, weights.get("B").longValue());
        assertEquals(330L, weights.get("C").longValue());
    }

    /**
     * Verify a zero overhead leaves the run times untouched. The overhead is zero whenever Tia has
     * no full-suite baseline to derive it from, which is the normal state on a new database.
     */
    @Test
    void shouldLeaveRunTimesUnchangedWhenThereIsNoOverheadToAmortise() {
        // given
        Map<String, Long> perSuiteRunTimes = new HashMap<>();
        perSuiteRunTimes.put("A", 100L);

        // when
        Map<String, Long> weights = TestGroupBalancer.suiteWeights(perSuiteRunTimes, 0L, true);

        // then
        assertEquals(100L, weights.get("A").longValue());
    }

    /**
     * Verify an empty selection produces an empty weight map rather than dividing by zero. A build
     * where Tia selects nothing reaches this path.
     */
    @Test
    void shouldReturnAnEmptyMapForAnEmptySelectionWithoutDividingByZero() {
        // given
        Map<String, Long> perSuiteRunTimes = new HashMap<>();

        // when
        Map<String, Long> weights = TestGroupBalancer.suiteWeights(perSuiteRunTimes, 90L, true);

        // then
        assertTrue(weights.isEmpty());
    }

    /**
     * Verify a suite with no recorded time still receives the overhead, so a brand-new suite is
     * not treated as costing nothing at all and packed alongside a full group's worth of work.
     */
    @Test
    void shouldStillChargeOverheadToASuiteWithZeroRecordedRunTime() {
        // given
        Map<String, Long> perSuiteRunTimes = new HashMap<>();
        perSuiteRunTimes.put("NeverRun", 0L);
        perSuiteRunTimes.put("Known", 100L);

        // when
        Map<String, Long> weights = TestGroupBalancer.suiteWeights(perSuiteRunTimes, 20L, true);

        // then
        assertEquals(10L, weights.get("NeverRun").longValue());
        assertEquals(110L, weights.get("Known").longValue());
    }

    /**
     * Verify the returned map is a copy, so weighting cannot corrupt the run-time estimate its
     * caller may still be using for its own reporting.
     */
    @Test
    void shouldNotMutateTheSuppliedRunTimeMap() {
        // given
        Map<String, Long> perSuiteRunTimes = new HashMap<>();
        perSuiteRunTimes.put("A", 100L);

        // when
        TestGroupBalancer.suiteWeights(perSuiteRunTimes, 50L, true);

        // then
        assertEquals(100L, perSuiteRunTimes.get("A").longValue());
    }

    /**
     * Verify a negative overhead is rejected rather than silently reducing every weight. The
     * existing estimate clamps at zero, so a negative value means the caller computed it wrongly.
     */
    @Test
    void shouldRejectANegativeMappingOverhead() {
        // given
        Map<String, Long> perSuiteRunTimes = new HashMap<>();
        perSuiteRunTimes.put("A", 100L);

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TestGroupBalancer.suiteWeights(perSuiteRunTimes, -1L, true));

        // then
        assertTrue(thrown.getMessage().contains("overhead"), thrown.getMessage());
    }
}
