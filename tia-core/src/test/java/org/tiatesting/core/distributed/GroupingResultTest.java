package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies defensive copying, immutability, and derived weight calculations for
 * {@link GroupingResult}. The critical contracts are: mutating the caller's original
 * group list after construction must not affect the result; the list returned by
 * {@link GroupingResult#getGroups()} must reject mutation; {@link GroupingResult#getHeaviestGroupMs()}
 * and {@link GroupingResult#getTotalEstimatedMs()} must be correctly computed at construction time.
 */
class GroupingResultTest {

    /**
     * Verifies that constructing a GroupingResult defensively copies the input group list, so
     * that mutating the caller's original list after construction does not affect the result's
     * internal state. This is essential because the grouper's working lists are reused during
     * balancing.
     */
    @Test
    void constructor_defensivelyCopiesGroupList() {
        // given - a mutable list of groups
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.ATest"), 1000L));
        groups.add(new SuiteGroup(1, createSuiteList("suite.BTest"), 2000L));
        GroupingResult result = new GroupingResult(groups, true, false);

        // when - the caller mutates the original list after construction
        groups.add(new SuiteGroup(2, createSuiteList("suite.CTest"), 3000L));
        groups.remove(0);

        // then - the result's groups are unchanged
        assertEquals(2, result.getGroupCount(), "result should have original 2 groups");
        assertEquals(0, result.getGroups().get(0).getGroupNumber(), "first group number unchanged");
        assertEquals(1, result.getGroups().get(1).getGroupNumber(), "second group number unchanged");
    }

    /**
     * Verifies that the list returned by {@link GroupingResult#getGroups()} rejects mutation
     * attempts. This ensures callers cannot later modify the result's group assignment.
     */
    @Test
    void getGroups_returnsUnmodifiableList() {
        // given - a result with groups
        List<SuiteGroup> inputGroups = new ArrayList<>();
        inputGroups.add(new SuiteGroup(0, createSuiteList("suite.XTest"), 1000L));
        inputGroups.add(new SuiteGroup(1, createSuiteList("suite.YTest"), 2000L));
        GroupingResult result = new GroupingResult(inputGroups, true, false);

        // when - attempting to mutate the returned list
        List<SuiteGroup> returnedList = result.getGroups();

        // then - mutations are rejected
        assertThrows(UnsupportedOperationException.class,
                () -> returnedList.add(new SuiteGroup(2, createSuiteList("suite.ZTest"), 3000L)),
                "returned list should reject add()");
        assertThrows(UnsupportedOperationException.class, () -> returnedList.remove(0),
                "returned list should reject remove()");
        assertThrows(UnsupportedOperationException.class, () -> returnedList.clear(),
                "returned list should reject clear()");
    }

    /**
     * Verifies that {@link GroupingResult#getHeaviestGroupMs()} returns the weight of the
     * group with the maximum estimated time when groups have differing weights.
     */
    @Test
    void getHeaviestGroupMs_findsSingleMaxWhenWeightsDiffer() {
        // given - three groups with different weights
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.Light"), 1000L));
        groups.add(new SuiteGroup(1, createSuiteList("suite.Heavy"), 5000L));
        groups.add(new SuiteGroup(2, createSuiteList("suite.Medium"), 3000L));

        // when
        GroupingResult result = new GroupingResult(groups, true, false);

        // then - the heaviest is 5000
        assertEquals(5000L, result.getHeaviestGroupMs(),
                "heaviest group should be 5000ms (the maximum)");
    }

    /**
     * Verifies that {@link GroupingResult#getHeaviestGroupMs()} returns the correct weight when
     * multiple groups have the same maximum weight.
     */
    @Test
    void getHeaviestGroupMs_findsMaxWhenMultipleGroupsHaveSameWeight() {
        // given - three groups, two of which have the same maximum weight
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.A"), 1000L));
        groups.add(new SuiteGroup(1, createSuiteList("suite.B"), 5000L));
        groups.add(new SuiteGroup(2, createSuiteList("suite.C"), 5000L));

        // when
        GroupingResult result = new GroupingResult(groups, true, false);

        // then - the heaviest is still 5000
        assertEquals(5000L, result.getHeaviestGroupMs(),
                "heaviest should be 5000ms even when multiple groups have that weight");
    }

    /**
     * Verifies that {@link GroupingResult#getHeaviestGroupMs()} returns the single group's
     * weight when there is exactly one group.
     */
    @Test
    void getHeaviestGroupMs_returnsSingleGroupWeightWhenOnlyOneGroup() {
        // given - exactly one group
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.OnlyOne"), 7500L));

        // when
        GroupingResult result = new GroupingResult(groups, true, false);

        // then
        assertEquals(7500L, result.getHeaviestGroupMs(),
                "with one group, heaviest should equal that group's weight");
    }

    /**
     * Verifies that {@link GroupingResult#getHeaviestGroupMs()} returns 0 when the group list
     * is empty, with no exception thrown.
     */
    @Test
    void getHeaviestGroupMs_returnsZeroForEmptyGroupList() {
        // given - an empty group list
        List<SuiteGroup> groups = new ArrayList<>();

        // when
        GroupingResult result = new GroupingResult(groups, true, false);

        // then - no exception, and heaviest is 0
        assertEquals(0L, result.getHeaviestGroupMs(),
                "empty result should have heaviest 0ms with no exception");
    }

    /**
     * Verifies that {@link GroupingResult#getTotalEstimatedMs()} returns the sum of all groups'
     * estimated times when groups have differing weights.
     */
    @Test
    void getTotalEstimatedMs_sumsDifferentWeights() {
        // given - three groups with different weights
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.A"), 1000L));
        groups.add(new SuiteGroup(1, createSuiteList("suite.B"), 3000L));
        groups.add(new SuiteGroup(2, createSuiteList("suite.C"), 6000L));

        // when
        GroupingResult result = new GroupingResult(groups, true, false);

        // then - total is 1000 + 3000 + 6000 = 10000
        assertEquals(10000L, result.getTotalEstimatedMs(),
                "total should be sum of all groups (1000 + 3000 + 6000 = 10000)");
    }

    /**
     * Verifies that {@link GroupingResult#getTotalEstimatedMs()} returns the correct total when
     * all groups have the same weight.
     */
    @Test
    void getTotalEstimatedMs_sumsEqualWeights() {
        // given - three groups all with the same weight
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.A"), 2000L));
        groups.add(new SuiteGroup(1, createSuiteList("suite.B"), 2000L));
        groups.add(new SuiteGroup(2, createSuiteList("suite.C"), 2000L));

        // when
        GroupingResult result = new GroupingResult(groups, true, false);

        // then - total is 2000 + 2000 + 2000 = 6000
        assertEquals(6000L, result.getTotalEstimatedMs(),
                "total should be 6000 when all three groups are 2000 each");
    }

    /**
     * Verifies that {@link GroupingResult#getTotalEstimatedMs()} returns the single group's
     * weight when there is exactly one group.
     */
    @Test
    void getTotalEstimatedMs_returnsSingleGroupWeightWhenOnlyOneGroup() {
        // given - exactly one group
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.OnlyOne"), 4500L));

        // when
        GroupingResult result = new GroupingResult(groups, true, false);

        // then
        assertEquals(4500L, result.getTotalEstimatedMs(),
                "with one group, total should equal that group's weight");
    }

    /**
     * Verifies that {@link GroupingResult#getTotalEstimatedMs()} returns 0 when the group list
     * is empty, with no exception thrown.
     */
    @Test
    void getTotalEstimatedMs_returnsZeroForEmptyGroupList() {
        // given - an empty group list
        List<SuiteGroup> groups = new ArrayList<>();

        // when
        GroupingResult result = new GroupingResult(groups, true, false);

        // then - no exception, and total is 0
        assertEquals(0L, result.getTotalEstimatedMs(),
                "empty result should have total 0ms with no exception");
    }

    /**
     * Verifies that both heaviest and total are zero for an empty group list.
     */
    @Test
    void emptyGroupList_bothWeightsAreZero() {
        // given - an empty group list
        List<SuiteGroup> groups = new ArrayList<>();

        // when
        GroupingResult result = new GroupingResult(groups, false, false);

        // then - both are 0
        assertEquals(0L, result.getHeaviestGroupMs(), "heaviest should be 0");
        assertEquals(0L, result.getTotalEstimatedMs(), "total should be 0");
    }

    /**
     * Verifies that {@link GroupingResult#getGroupCount()} returns the correct number of
     * groups.
     */
    @Test
    void getGroupCount_returnsCorrectCount() {
        // given
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.A"), 1000L));
        groups.add(new SuiteGroup(1, createSuiteList("suite.B"), 2000L));
        groups.add(new SuiteGroup(2, createSuiteList("suite.C"), 3000L));

        // when
        GroupingResult result = new GroupingResult(groups, true, false);

        // then
        assertEquals(3, result.getGroupCount(), "group count should be 3");
    }

    /**
     * Verifies that {@link GroupingResult#isTargetMet()} returns the value passed to the
     * constructor.
     */
    @Test
    void isTargetMet_returnsConstructorValue() {
        // given
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.Test"), 1000L));

        // when - creating with targetMet = true
        GroupingResult resultMet = new GroupingResult(groups, true, false);

        // then
        assertTrue(resultMet.isTargetMet(), "should return true when constructor passed true");

        // when - creating with targetMet = false
        GroupingResult resultNotMet = new GroupingResult(groups, false, false);

        // then
        assertFalse(resultNotMet.isTargetMet(), "should return false when constructor passed false");
    }

    /**
     * Verifies that {@link GroupingResult#isClampedToMaxGroups()} returns the value passed to
     * the constructor.
     */
    @Test
    void isClampedToMaxGroups_returnsConstructorValue() {
        // given
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.Test"), 1000L));

        // when - creating with clampedToMaxGroups = true
        GroupingResult resultClamped = new GroupingResult(groups, true, true);

        // then
        assertTrue(resultClamped.isClampedToMaxGroups(),
                "should return true when constructor passed true");

        // when - creating with clampedToMaxGroups = false
        GroupingResult resultNotClamped = new GroupingResult(groups, true, false);

        // then
        assertFalse(resultNotClamped.isClampedToMaxGroups(),
                "should return false when constructor passed false");
    }

    /**
     * Verifies that the getters return the expected values in a typical multi-group scenario.
     */
    @Test
    void typicalScenario_multipleGroupsWithMixedWeights() {
        // given - a realistic grouping with 4 groups of varying weight
        List<SuiteGroup> groups = new ArrayList<>();
        groups.add(new SuiteGroup(0, createSuiteList("suite.Fast"), 2000L));
        groups.add(new SuiteGroup(1, createSuiteList("suite.Slow"), 8000L));
        groups.add(new SuiteGroup(2, createSuiteList("suite.Medium"), 4000L));
        groups.add(new SuiteGroup(3, createSuiteList("suite.Fast2"), 2500L));

        // when
        GroupingResult result = new GroupingResult(groups, true, false);

        // then - verify all properties
        assertEquals(4, result.getGroupCount(), "should have 4 groups");
        assertEquals(8000L, result.getHeaviestGroupMs(), "heaviest should be 8000");
        assertEquals(16500L, result.getTotalEstimatedMs(), "total should be 16500");
        assertTrue(result.isTargetMet(), "target met should be true");
        assertFalse(result.isClampedToMaxGroups(), "not clamped should be false");
    }

    /**
     * Helper method to create a list with a single suite name. Used to avoid repetition in
     * test fixtures and ensure consistent list creation patterns.
     *
     * @param suiteName the name of the single suite to add
     * @return a new ArrayList containing the single suite name
     */
    private List<String> createSuiteList(String suiteName) {
        List<String> list = new ArrayList<>();
        list.add(suiteName);
        return list;
    }
}
