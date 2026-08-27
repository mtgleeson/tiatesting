package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the static-groups path of {@link TestGroupBalancer}, where the caller fixes the group
 * count and the balancer minimises the heaviest group. The plan must be reproducible: two runners
 * deriving different groupings from the same inputs would be undebuggable, so the tie-breaks are
 * asserted rather than assumed.
 */
class TestGroupBalancerStaticGroupsTest {

    /**
     * Build a weight map from alternating name and weight arguments, so a test's fixture reads as
     * a compact table rather than five lines of map population.
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
     * Build the nine-suite weight fixture (weights 9,7,6,5,4,3,3,2,1) used by the invariant tests
     * that check every suite is placed and group numbers line up with their index.
     *
     * @return the nine-suite weight map
     */
    private static Map<String, Long> nineSuiteFixture() {
        return weights("A", 9, "B", 7, "C", 6, "D", 5, "E", 4, "F", 3, "G", 3, "H", 2, "I", 1);
    }

    /**
     * Verify the classic LPT outcome: heaviest suite first, each subsequent suite onto the
     * currently-lightest group. This is the behaviour every other static-groups guarantee rests
     * on, so it is asserted by exact group contents rather than only by weight.
     */
    @Test
    void shouldAssignEachSuiteToTheCurrentlyLightestGroup() {
        // given
        Map<String, Long> suiteWeights = weights("A", 9, "B", 7, "C", 6, "D", 5, "E", 4);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 3, 0L);

        // then
        assertEquals(3, result.getGroupCount());
        assertEquals(Arrays.asList("A"), result.getGroups().get(0).getSuiteNames());
        assertEquals(Arrays.asList("B", "E"), result.getGroups().get(1).getSuiteNames());
        assertEquals(Arrays.asList("C", "D"), result.getGroups().get(2).getSuiteNames());
        assertEquals(9L, result.getGroups().get(0).getEstimatedMs());
        assertEquals(11L, result.getGroups().get(1).getEstimatedMs());
        assertEquals(11L, result.getGroups().get(2).getEstimatedMs());
    }

    /**
     * Verify equal-weight suites are ordered by name, so the plan is identical across runs and
     * across JVMs regardless of the iteration order of the caller's map.
     */
    @Test
    void shouldBreakWeightTiesBySuiteNameSoThePlanIsReproducible() {
        // given
        // deliberately inserted in non-alphabetical order
        Map<String, Long> suiteWeights = weights("Zebra", 5, "Apple", 5, "Mango", 5);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 3, 0L);

        // then
        assertEquals(Arrays.asList("Apple"), result.getGroups().get(0).getSuiteNames());
        assertEquals(Arrays.asList("Mango"), result.getGroups().get(1).getSuiteNames());
        assertEquals(Arrays.asList("Zebra"), result.getGroups().get(2).getSuiteNames());
    }

    /**
     * Verify that when several groups weigh the same, the lowest-numbered one is chosen. Without
     * this the first few assignments would depend on internal iteration order and the plan would
     * not be reproducible.
     */
    @Test
    void shouldBreakGroupTiesByLowestGroupNumber() {
        // given
        Map<String, Long> suiteWeights = weights("A", 5, "B", 5);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 4, 0L);

        // then
        assertEquals(Arrays.asList("A"), result.getGroups().get(0).getSuiteNames());
        assertEquals(Arrays.asList("B"), result.getGroups().get(1).getSuiteNames());
        assertTrue(result.getGroups().get(2).getSuiteNames().isEmpty());
        assertTrue(result.getGroups().get(3).getSuiteNames().isEmpty());
    }

    /**
     * Verify a group count larger than the number of suites still returns that many groups, with
     * the surplus empty. The planner turns every group into a runner, so silently returning fewer
     * would make the plan disagree with the fan-out count the pipeline was given.
     */
    @Test
    void shouldReturnEmptyGroupsWhenThereAreMoreGroupsThanSuites() {
        // given
        Map<String, Long> suiteWeights = weights("A", 5);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 3, 0L);

        // then
        assertEquals(3, result.getGroupCount());
        assertEquals(5L, result.getTotalEstimatedMs());
        assertEquals(5L, result.getHeaviestGroupMs());
    }

    /**
     * Verify an empty selection produces the requested number of empty groups rather than
     * failing. A build where Tia selects nothing is normal, not an error.
     */
    @Test
    void shouldProduceEmptyGroupsForAnEmptySelection() {
        // given
        Map<String, Long> suiteWeights = new HashMap<>();

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 2, 0L);

        // then
        assertEquals(2, result.getGroupCount());
        assertEquals(0L, result.getTotalEstimatedMs());
        assertEquals(0L, result.getHeaviestGroupMs());
    }

    /**
     * Verify static groups always report the target as met, never as clamped, and never as a
     * single suite exceeding the target, since none of a target, a ceiling, or a target-relative
     * comparison applies when the caller fixed the count.
     */
    @Test
    void shouldReportTargetMetAndNotClampedForStaticGroups() {
        // given
        Map<String, Long> suiteWeights = weights("A", 100, "B", 1);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 2, 0L);

        // then
        assertTrue(result.isTargetMet());
        assertFalse(result.isClampedToMaxGroups());
        assertFalse(result.isSingleSuiteExceedsTarget());
    }

    /**
     * Verify every suite in the input map appears in exactly one output group, with no suite
     * dropped or duplicated. A regression here means selected tests silently never run on any
     * runner, so it is checked directly against the input key set rather than trusted from the
     * group count alone.
     */
    @Test
    void shouldAssignEveryInputSuiteToExactlyOneGroup() {
        // given
        Map<String, Long> suiteWeights = nineSuiteFixture();

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 4, 0L);

        // then
        List<String> assignedSuiteNames = new ArrayList<>();
        for (SuiteGroup group : result.getGroups()) {
            assignedSuiteNames.addAll(group.getSuiteNames());
        }
        assertEquals(suiteWeights.size(), assignedSuiteNames.size(),
                "every suite should be assigned exactly once, with none dropped or duplicated");
        assertEquals(suiteWeights.keySet(), new HashSet<>(assignedSuiteNames),
                "the assigned suites must match the input suites exactly");
    }

    /**
     * Verify each group's reported group number equals its index in the returned groups list.
     * The planner keys a Map<Integer, List<String>> off getGroupNumber(), so a mismatch there would
     * silently misfile a group's suites under the wrong runner.
     */
    @Test
    void shouldReportGroupNumberEqualToItsIndexInTheGroupsList() {
        // given
        Map<String, Long> suiteWeights = nineSuiteFixture();

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 4, 0L);

        // then
        for (int i = 0; i < result.getGroups().size(); i++) {
            assertEquals(i, result.getGroups().get(i).getGroupNumber(),
                    "group at index " + i + " should report that index as its group number");
        }
    }

    /**
     * Verify a non-positive group count is rejected. Returning zero groups would produce a plan
     * no runner could claim, and the build would report green having run nothing.
     */
    @Test
    void shouldRejectAGroupCountBelowOne() {
        // given
        Map<String, Long> suiteWeights = weights("A", 5);

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TestGroupBalancer.balanceIntoGroups(suiteWeights, 0, 0L));

        // then
        assertTrue(thrown.getMessage().contains("groupCount"), thrown.getMessage());
    }

    /**
     * Verify the returned groups list cannot be mutated by a caller, so a plan cannot be altered
     * after the balancer has validated it.
     */
    @Test
    void shouldReturnAnUnmodifiableGroupsList() {
        // given
        Map<String, Long> suiteWeights = weights("A", 5);
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(suiteWeights, 1, 0L);

        // when
        List<SuiteGroup> groups = result.getGroups();

        // then
        assertThrows(UnsupportedOperationException.class,
                () -> groups.add(new SuiteGroup(9, Arrays.asList("X"), 1L)));
    }
}
