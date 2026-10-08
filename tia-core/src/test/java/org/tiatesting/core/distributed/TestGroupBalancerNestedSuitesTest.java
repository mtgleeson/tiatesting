package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the balancer never splits a JUnit 5 {@code @Nested} suite from its top-level suite: a
 * nested class only runs inside its enclosing class, so a group holding one without the other
 * would skip it.
 */
class TestGroupBalancerNestedSuitesTest {

    /**
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
     * @param result a grouping
     * @param suite a suite name
     * @return the number of the group holding the suite
     */
    private static int groupOf(final GroupingResult result, final String suite) {
        for (SuiteGroup group : result.getGroups()) {
            if (group.getSuiteNames().contains(suite)) {
                return group.getGroupNumber();
            }
        }
        throw new AssertionError(suite + " is in no group");
    }

    @Test
    void fixedCountKeepsNestedSuitesWithTheirTopLevelSuite() {
        // given
        Map<String, Long> weights = weights("A", 5, "A$N", 5, "A$N$M", 5, "B", 5, "C", 5);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(weights, 3, 0L);

        // then
        assertEquals(3, result.getGroups().size());
        assertEquals(groupOf(result, "A"), groupOf(result, "A$N"));
        assertEquals(groupOf(result, "A"), groupOf(result, "A$N$M"));
    }

    @Test
    void aFamilyIsNotWeighedTwiceForItsNestedSuites() {
        // given - A's 60ms is its whole container, which already includes A$N's 40ms
        Map<String, Long> weights = weights("A", 60, "A$N", 40, "B", 50);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(weights, 2, 0L);

        // then
        assertEquals(60L, result.getGroups().get(groupOf(result, "A")).getEstimatedMs());
    }

    @Test
    void aFamilyWithoutItsTopLevelSuiteWeighsItsNestedSuites() {
        // given - only nested suites selected
        Map<String, Long> weights = weights("A$N", 40, "A$M", 30, "B", 50);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(weights, 2, 0L);

        // then
        assertEquals(70L, result.getGroups().get(groupOf(result, "A$N")).getEstimatedMs());
    }

    @Test
    void fixedCountIsCappedAtTheNumberOfFamilies() {
        // given - two suites, but one family
        Map<String, Long> weights = weights("A", 5, "A$N", 5);

        // when
        GroupingResult result = TestGroupBalancer.balanceIntoGroups(weights, 2, 0L);

        // then
        assertEquals(1, result.getGroups().size());
        assertEquals(2, result.getGroups().get(0).getSuiteNames().size());
    }

    @Test
    void targetRunTimeKeepsNestedSuitesWithTheirTopLevelSuite() {
        // given - the family alone exceeds the target, so it cannot be split to meet it
        Map<String, Long> weights = weights("A", 15, "A$N", 10, "B", 10);

        // when
        GroupingResult result = TestGroupBalancer.balanceForTargetRunTime(weights, 12L, null, 0L);

        // then
        assertEquals(groupOf(result, "A"), groupOf(result, "A$N"));
        assertTrue(result.isSingleSuiteExceedsTarget());
    }
}
