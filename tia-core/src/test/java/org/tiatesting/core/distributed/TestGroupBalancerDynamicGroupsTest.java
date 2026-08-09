package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the dynamic-groups path of {@link TestGroupBalancer}, where the caller fixes a target run
 * time and the balancer uses the fewest groups that meet it.
 *
 * <p>Every test here asserts the returned <em>group count</em>, not merely that the target was
 * met. A balancer that always returned the maximum allowed group count would meet the target in
 * every case and pass a weaker suite, while costing the user runners they were not asked to pay
 * for.
 */
class TestGroupBalancerDynamicGroupsTest {

    /**
     * Build a weight map from alternating name and weight arguments, so a test's fixture reads as
     * a compact table rather than many lines of map population.
     *
     * @param nameThenWeight suite name followed by weight, repeated
     * @return the assembled weight map
     */
    private static Map<String, Long> weights(Object... nameThenWeight) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < nameThenWeight.length; i += 2) {
            map.put((String) nameThenWeight[i], ((Number) nameThenWeight[i + 1]).longValue());
        }
        return map;
    }

    /**
     * The nine suites weighing 9,7,6,5,4,3,3,2,1 against a target of 10. This is the regression
     * guard for the whole task: bin packing answers 4 groups (which brute force confirms is
     * optimal), while the rejected "LPT, then raise the group count until the target is met"
     * approach answers 5. Without this case the two designs are indistinguishable.
     *
     * @return the nine-suite weight map
     */
    private static Map<String, Long> nineSuites() {
        return weights("PaymentIT", 9, "OrderIT", 7, "UserIT", 6, "AuthIT", 5, "CartIT", 4,
                "SearchIT", 3, "EmailIT", 3, "AuditIT", 2, "ConfigIT", 1);
    }

    /**
     * Verify the balancer finds the optimal four groups for the nine-suite case rather than the
     * five that escalating LPT would produce. This is the single most important assertion in the
     * stage: it is what distinguishes bin packing from makespan scheduling.
     */
    @Test
    void shouldUseFourGroupsForTheNineSuiteCaseNotFive() {
        // given
        Map<String, Long> suiteWeights = nineSuites();

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, null);

        // then
        assertEquals(4, result.getGroupCount());
        assertTrue(result.isTargetMet());
        assertEquals(10L, result.getHeaviestGroupMs());
        assertEquals(40L, result.getTotalEstimatedMs());
    }

    /**
     * Verify the LPT re-balance is not applied when it is worse than the FFD packing. In the
     * nine-suite case LPT at four groups gives a heaviest of 11 against FFD's 10, so the FFD
     * packing must survive. A balancer that always preferred the re-balance would fail here, and
     * would also miss the target it had already met.
     */
    @Test
    void shouldKeepTheFfdPackingWhenTheRebalanceIsWorse() {
        // given
        Map<String, Long> suiteWeights = nineSuites();

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, null);

        // then
        assertEquals(10L, result.getHeaviestGroupMs());
        assertTrue(result.isTargetMet());
    }

    /**
     * Verify the LPT re-balance IS applied when it produces a lighter heaviest group at the same
     * group count. FFD fills bins to capacity, so without the re-balance a build would routinely
     * land exactly at its target when it could have finished sooner on the same runners.
     */
    @Test
    void shouldApplyTheRebalanceWhenItProducesALighterHeaviestGroup() {
        // given
        // FFD at capacity 10 opens two bins, filling the first to 10 and leaving the second at 6;
        // LPT at two groups spreads the same suites to 8 and 8.
        Map<String, Long> suiteWeights = weights("A", 6, "B", 4, "C", 4, "D", 2);

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, null);

        // then
        assertEquals(2, result.getGroupCount());
        assertEquals(8L, result.getHeaviestGroupMs());
        assertTrue(result.isTargetMet());
    }

    /**
     * Verify that when one suite is longer than the whole target, the capacity rises to that
     * suite's weight rather than staying at the unreachable target. Packing to the target would
     * scatter the remaining suites over an extra runner for no gain: the build takes 15 either
     * way, so the cheaper plan is the correct one.
     */
    @Test
    void shouldNotSpendExtraGroupsChasingATargetASingleSuiteAlreadyExceeds() {
        // given
        Map<String, Long> suiteWeights = weights("Dominant", 15, "B", 6, "C", 4, "D", 3, "E", 2);

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, null);

        // then
        // packing to capacity 10 would give three groups; capacity 15 gives two, same makespan
        assertEquals(2, result.getGroupCount());
        assertEquals(15L, result.getHeaviestGroupMs());
        assertFalse(result.isTargetMet());
        assertFalse(result.isClampedToMaxGroups());
    }

    /**
     * Verify a ceiling below the group count the target needs still returns a usable plan,
     * balanced by time, rather than failing the build or dropping suites. Hitting the target is
     * best effort; the overrun must be shared across the available runners rather than dumped on
     * one.
     */
    @Test
    void shouldBalanceByTimeWhenClampedByMaxGroups() {
        // given
        Map<String, Long> suiteWeights = nineSuites();

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, 3);

        // then
        assertEquals(3, result.getGroupCount());
        assertFalse(result.isTargetMet());
        assertTrue(result.isClampedToMaxGroups());
        // the overrun must be spread, not concentrated: 40 over 3 groups is 13.33, so no group
        // should be more than one unit above the lightest
        long heaviest = result.getHeaviestGroupMs();
        long lightest = Long.MAX_VALUE;
        for (SuiteGroup group : result.getGroups()) {
            lightest = Math.min(lightest, group.getEstimatedMs());
        }
        assertTrue(heaviest - lightest <= 1L,
                "expected an evenly balanced clamp, got heaviest=" + heaviest + " lightest=" + lightest);
    }

    /**
     * Verify a ceiling at or above what the target needs is not reported as a clamp, so a user
     * reading {@code clampedToMaxGroups} can trust it to mean "raise the ceiling".
     */
    @Test
    void shouldNotReportAClampWhenTheCeilingIsGenerous() {
        // given
        Map<String, Long> suiteWeights = nineSuites();

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, 12);

        // then
        assertEquals(4, result.getGroupCount());
        assertTrue(result.isTargetMet());
        assertFalse(result.isClampedToMaxGroups());
    }

    /**
     * Verify weights that divide evenly into the target use exactly the arithmetic minimum number
     * of groups, with no group over the target.
     */
    @Test
    void shouldUseTheArithmeticMinimumWhenWeightsDivideEvenly() {
        // given
        Map<String, Long> suiteWeights = weights("A", 5, "B", 5, "C", 5, "D", 5);

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, null);

        // then
        assertEquals(2, result.getGroupCount());
        assertEquals(10L, result.getHeaviestGroupMs());
        assertTrue(result.isTargetMet());
    }

    /**
     * Verify an empty selection produces a single empty group rather than zero groups or a
     * failure. A build where Tia selects nothing is normal, and stage 4 still needs a plan shape
     * to write.
     */
    @Test
    void shouldProduceOneEmptyGroupForAnEmptySelection() {
        // given
        Map<String, Long> suiteWeights = new HashMap<>();

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, null);

        // then
        assertEquals(1, result.getGroupCount());
        assertEquals(0L, result.getTotalEstimatedMs());
        assertTrue(result.isTargetMet());
        assertFalse(result.isClampedToMaxGroups());
    }

    /**
     * Verify a single suite lighter than the target needs only one group, so a small change does
     * not start a fan-out the build does not need.
     */
    @Test
    void shouldUseOneGroupForASingleLightSuite() {
        // given
        Map<String, Long> suiteWeights = weights("OnlyTest", 3);

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, null);

        // then
        assertEquals(1, result.getGroupCount());
        assertTrue(result.isTargetMet());
    }

    /**
     * Verify the same inputs always produce the same plan, including group membership, since two
     * runners must never be able to derive different groupings.
     */
    @Test
    void shouldProduceAnIdenticalPlanOnRepeatedCalls() {
        // given
        Map<String, Long> suiteWeights = nineSuites();

        // when
        GroupingResult first = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, null);
        GroupingResult second = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 10L, null);

        // then
        assertEquals(first.getGroupCount(), second.getGroupCount());
        for (int i = 0; i < first.getGroupCount(); i++) {
            assertEquals(first.getGroups().get(i).getSuiteNames(),
                    second.getGroups().get(i).getSuiteNames());
        }
    }
}
