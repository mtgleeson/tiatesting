package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock how {@link TestGroupBalancer} charges the fixed per-JVM overhead: once per group that runs
 * something, never inside the weights the packing decides on, and always inside the budget the
 * dynamic-groups target is measured against.
 *
 * <p>The distinction matters because the fixed cost behaves the opposite way to every other number
 * here when a build is fanned out. Suite time and coverage capture are <em>divided</em> across
 * groups; the fixed cost is <em>duplicated</em>, since each runner is its own JVM. A balancer that
 * folded it into the per-suite weights would charge a five-suite group five copies of a cost paid
 * once, and would corrupt the capacity arithmetic that chooses the group count.
 */
class TestGroupBalancerFixedOverheadTest {

    /**
     * Build a weight map from alternating name and weight arguments, so a test's fixture reads as a
     * compact table rather than many lines of map population.
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
     * Reduce a grouping to the suite names each group owns, so a packing can be compared against
     * another without depending on the weights that produced it.
     *
     * @param result the grouping to reduce
     * @return the suite names per group, in group-number order
     */
    private static List<List<String>> assignment(final GroupingResult result) {
        java.util.List<java.util.List<String>> groups = new java.util.ArrayList<>();
        for (SuiteGroup group : result.getGroups()) {
            groups.add(group.getSuiteNames());
        }
        return groups;
    }

    /**
     * <b>The invariant that keeps the model honest.</b> The fixed cost is the same constant on every
     * group, so it cannot change which suites belong together - and folding it into the weights
     * would. This asserts the packing is byte-for-byte identical with and without it, which no
     * assertion about group totals could catch: totals differ by design.
     */
    @Test
    void theFixedOverheadDoesNotChangeWhichSuitesGroupTogether() {
        // given
        Map<String, Long> suiteWeights =
                weights("PaymentIT", 900, "OrderIT", 700, "UserIT", 600, "AuthIT", 500,
                        "CartIT", 400, "SearchIT", 300, "EmailIT", 300, "AuditIT", 200);

        // when
        GroupingResult without = TestGroupBalancer.balanceIntoGroups(suiteWeights, 3, 0L);
        GroupingResult with = TestGroupBalancer.balanceIntoGroups(suiteWeights, 3, 5_000L);

        // then
        assertEquals(assignment(without), assignment(with),
                "the same constant on every group cannot change which suites belong together, so "
                        + "the packing must be identical");
    }

    /**
     * The same invariant on the dynamic path, where getting it wrong would also change the group
     * <em>count</em> - the balancer would open runners to carry an overhead that opening a runner
     * is what causes.
     */
    @Test
    void theFixedOverheadDoesNotChangeTheGroupCountChosenForATarget() {
        // given - a target generous enough that the fixed cost does not eat into the packing
        Map<String, Long> suiteWeights =
                weights("PaymentIT", 900, "OrderIT", 700, "UserIT", 600, "AuthIT", 500);

        // when - the second call raises the target by exactly the fixed cost, so the suite budget
        // is the same in both
        GroupingResult without = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 1_500L,
                null, 0L);
        GroupingResult with = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 1_700L, null,
                200L);

        // then
        assertEquals(assignment(without), assignment(with),
                "the same suite budget must produce the same packing whatever the fixed cost is");
    }

    /**
     * A group's reported total carries exactly one copy of the fixed cost, however many suites it
     * owns. Charging per suite would be the per-suite capture term, which the weights already carry.
     */
    @Test
    void eachGroupIsChargedExactlyOneCopyOfTheFixedOverhead() {
        // given - one group of three suites and one of a single suite
        Map<String, Long> suiteWeights = weights("HeavyIT", 100, "MidIT", 60, "SmallA", 20,
                "SmallB", 20);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 2, 500L);

        // then
        for (SuiteGroup group : result.getGroups()) {
            long suiteTimeMs = 0L;
            for (String suiteName : group.getSuiteNames()) {
                suiteTimeMs += suiteWeights.get(suiteName).longValue();
            }
            assertEquals(suiteTimeMs + 500L, group.getEstimatedMs(),
                    "group " + group.getGroupNumber() + " must carry its suites plus exactly one "
                            + "JVM start-up, not one per suite");
        }
    }

    /**
     * A group with no suites is charged nothing. Groups beyond the suite count come back empty
     * rather than being dropped, and an empty group runs no tests - charging it would put a non-zero
     * estimate on a runner with nothing to do.
     */
    @Test
    void anEmptyGroupIsChargedNoFixedOverhead() {
        // given - more groups than suites
        Map<String, Long> suiteWeights = weights("OnlyIT", 100);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 3, 500L);

        // then
        assertEquals(600L, result.getGroups().get(0).getEstimatedMs(),
                "the group that got the suite pays for its JVM");
        assertEquals(0L, result.getGroups().get(1).getEstimatedMs(),
                "a group with nothing to run costs nothing");
        assertEquals(0L, result.getGroups().get(2).getEstimatedMs(),
                "a group with nothing to run costs nothing");
    }

    /**
     * An empty selection produces one empty group costing nothing at all, so a nothing-impacted
     * build still reports an estimate of zero rather than the cost of a JVM it never starts.
     */
    @Test
    void anEmptySelectionCostsNothing() {
        // given
        Map<String, Long> suiteWeights = Collections.emptyMap();

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 1_000L,
                null, 500L);

        // then
        assertEquals(1, result.getGroupCount(), "an empty selection plans a single group");
        assertEquals(0L, result.getHeaviestGroupMs(),
                "a build with nothing to run must not be estimated at the cost of a JVM");
    }

    /**
     * <b>The target is a budget for the whole group.</b> A group meets the target only when its
     * suites <em>plus</em> its own JVM start-up come in under it, so the suites have to fit inside
     * {@code target - fixed}. Two suites of 400ms fit a 1000ms target on their own, but not once
     * each runner's 300ms start-up is paid for.
     */
    @Test
    void theSuiteBudgetIsTheTargetLessTheFixedOverhead() {
        // given
        Map<String, Long> suiteWeights = weights("AlphaIT", 400, "BetaIT", 400);

        // when
        GroupingResult withoutOverhead = TestGroupBalancer.balanceForTargetRunTime(suiteWeights,
                1_000L, null, 0L);
        GroupingResult withOverhead = TestGroupBalancer.balanceForTargetRunTime(suiteWeights,
                1_000L, null, 300L);

        // then
        assertEquals(1, withoutOverhead.getGroupCount(),
                "with the whole 1000ms available, both suites fit one group");
        assertTrue(withoutOverhead.isTargetMet(), "and that group meets the target");
        assertEquals(2, withOverhead.getGroupCount(),
                "with only 700ms left for suites, 800ms of suites needs a second group");
        assertTrue(withOverhead.isTargetMet(),
                "and each group's 400ms of suites plus 300ms of start-up still meets the 1000ms "
                        + "target");
        assertEquals(700L, withOverhead.getHeaviestGroupMs(),
                "the heaviest group reports its suites plus its own start-up");
    }

    /**
     * The target verdict is taken against the whole target, not the suite budget - the heaviest
     * group already carries its own copy of the fixed cost, so subtracting it twice would report a
     * miss on a plan that meets the target.
     */
    @Test
    void theTargetVerdictComparesTheWholeGroupAgainstTheWholeTarget() {
        // given - 600ms of suites plus 300ms of start-up is exactly the 900ms target
        Map<String, Long> suiteWeights = weights("AlphaIT", 600);

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 900L, null,
                300L);

        // then
        assertEquals(900L, result.getHeaviestGroupMs(), "the group costs its suites plus its JVM");
        assertTrue(result.isTargetMet(), "landing exactly on the target meets it");
    }

    /**
     * <b>The case Tia used to report as a met target.</b> When the fixed cost alone meets or exceeds
     * the target, no group count can help - every runner added brings another full copy of a cost
     * that already blows the budget. Before the fixed cost was modelled at all, this plan reported
     * "target met" and left a user adding runners that could not possibly work.
     */
    @Test
    void aFixedOverheadAtOrAboveTheTargetCanNeverMeetIt() {
        // given - a 500ms target against a 500ms JVM start-up
        Map<String, Long> suiteWeights = weights("AlphaIT", 100, "BetaIT", 100, "GammaIT", 100);

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 500L, null,
                500L);

        // then
        assertFalse(result.isTargetMet(),
                "a target no group count can reach must not be reported as met");
        assertTrue(result.isFixedOverheadExceedsTarget(),
                "and the reason must be named, since no change to the selection can fix it");
        assertFalse(result.isSingleSuiteExceedsTarget(),
                "no single suite is at fault here - the suites are 100ms against a 500ms target - "
                        + "so blaming one would send the user after the wrong lever");
    }

    /**
     * No group count meets it, however many are allowed. This is what separates this miss from the
     * ceiling one, whose lever is exactly "allow more groups".
     */
    @Test
    void noGroupCountRescuesAFixedOverheadAboveTheTarget() {
        // given
        Map<String, Long> suiteWeights = weights("AlphaIT", 100, "BetaIT", 100, "GammaIT", 100,
                "DeltaIT", 100);

        // when - every ceiling from one group to one per suite
        for (int maxGroups = 1; maxGroups <= 4; maxGroups++) {
            GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 400L,
                    Integer.valueOf(maxGroups), 600L);

            // then
            assertFalse(result.isTargetMet(),
                    "a ceiling of " + maxGroups + " group(s) cannot rescue a target below the "
                            + "per-JVM cost");
            assertTrue(result.isFixedOverheadExceedsTarget(),
                    "and the reason stays the same at " + maxGroups + " group(s)");
        }
    }

    /**
     * The single-suite reason is measured against the suite budget, not the whole target: a suite
     * that fits the target on its own but not once the JVM is paid for is genuinely one no group
     * count can accommodate, and must be reported as such.
     */
    @Test
    void aSuiteLongerThanTheBudgetButShorterThanTheTargetStillMissesIt() {
        // given - a 900ms suite against a 1000ms target with 200ms of start-up
        Map<String, Long> suiteWeights = weights("MonolithIT", 900, "SmallIT", 50);

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 1_000L,
                null, 200L);

        // then
        assertFalse(result.isTargetMet(),
                "900ms of suite plus 200ms of start-up exceeds the 1000ms target");
        assertTrue(result.isSingleSuiteExceedsTarget(),
                "the suite is what no group count can split, so that is the lever to name");
        assertFalse(result.isFixedOverheadExceedsTarget(),
                "the start-up alone is well under the target, so it is not the reason");
    }

    /**
     * A negative fixed overhead is rejected at the entry point rather than silently shrinking a
     * group's estimate or, worse, inflating the suite budget above the target.
     */
    @Test
    void aNegativeFixedOverheadIsRejected() {
        // given
        Map<String, Long> suiteWeights = weights("AlphaIT", 100);

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> TestGroupBalancer.balanceIntoGroups(suiteWeights, 1, -1L),
                "a negative per-JVM cost is meaningless and must be rejected, not applied");
        assertThrows(IllegalArgumentException.class,
                () -> TestGroupBalancer.balanceForTargetRunTime(suiteWeights, 500L, null, -1L),
                "a negative per-JVM cost is meaningless and must be rejected, not applied");
    }
}
