package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies defensive copying behaviour and basic accessors for {@link SuiteGroup}. The critical
 * contract is that mutating the caller's original list after construction must not affect the
 * object, and the list returned by {@link SuiteGroup#getSuiteNames()} must reject mutation.
 */
class SuiteGroupTest {

    /**
     * Verifies that constructing a SuiteGroup defensively copies the input list, so that
     * mutating the caller's original list after construction does not affect the group's
     * internal state. This is essential because the grouper's working lists are reused during
     * balancing.
     */
    @Test
    void constructor_defensivelyCopiesInputList() {
        // given - a mutable list of suite names
        List<String> suiteNames = new ArrayList<>();
        suiteNames.add("suite.ATest");
        suiteNames.add("suite.BTest");
        SuiteGroup group = new SuiteGroup(0, suiteNames, 5000L);

        // when - the caller mutates the original list after construction
        suiteNames.add("suite.CTest");
        suiteNames.remove("suite.ATest");

        // then - the group's suite names are unchanged
        assertEquals(2, group.getSuiteNames().size(), "group should have original 2 suites");
        assertEquals("suite.ATest", group.getSuiteNames().get(0), "first suite unchanged");
        assertEquals("suite.BTest", group.getSuiteNames().get(1), "second suite unchanged");
    }

    /**
     * Verifies that the list returned by {@link SuiteGroup#getSuiteNames()} rejects mutation
     * attempts. This ensures callers cannot later modify the group's suite assignment.
     */
    @Test
    void getSuiteNames_returnsUnmodifiableList() {
        // given - a group with suite names
        List<String> inputSuites = new ArrayList<>();
        inputSuites.add("suite.XTest");
        inputSuites.add("suite.YTest");
        SuiteGroup group = new SuiteGroup(1, inputSuites, 3000L);

        // when - attempting to mutate the returned list
        List<String> returnedList = group.getSuiteNames();

        // then - mutations are rejected
        assertThrows(UnsupportedOperationException.class, () -> returnedList.add("suite.ZTest"),
                "returned list should reject add()");
        assertThrows(UnsupportedOperationException.class, () -> returnedList.remove(0),
                "returned list should reject remove()");
        assertThrows(UnsupportedOperationException.class, () -> returnedList.clear(),
                "returned list should reject clear()");
    }

    /**
     * Verifies that {@link SuiteGroup#getGroupNumber()} returns the group number passed to the
     * constructor.
     */
    @Test
    void getGroupNumber_returnsConstructorValue() {
        // given
        List<String> suites = new ArrayList<>();
        suites.add("suite.Test");

        // when - creating a group with group number 5
        SuiteGroup group = new SuiteGroup(5, suites, 1000L);

        // then
        assertEquals(5, group.getGroupNumber(), "group number should match constructor value");
    }

    /**
     * Verifies that {@link SuiteGroup#getEstimatedMs()} returns the estimated time passed to
     * the constructor.
     */
    @Test
    void getEstimatedMs_returnsConstructorValue() {
        // given
        List<String> suites = new ArrayList<>();
        suites.add("suite.Test");

        // when - creating a group with estimated time 12345 ms
        SuiteGroup group = new SuiteGroup(0, suites, 12345L);

        // then
        assertEquals(12345L, group.getEstimatedMs(), "estimated ms should match constructor value");
    }

    /**
     * Verifies that {@link SuiteGroup#getSuiteNames()} returns the suite names as an
     * unmodifiable list, preserving order and content.
     */
    @Test
    void getSuiteNames_returnsCorrectSuites() {
        // given
        List<String> inputSuites = new ArrayList<>();
        inputSuites.add("suite.ATest");
        inputSuites.add("suite.BTest");
        inputSuites.add("suite.CTest");

        // when
        SuiteGroup group = new SuiteGroup(0, inputSuites, 5000L);

        // then
        List<String> returnedSuites = group.getSuiteNames();
        assertEquals(3, returnedSuites.size(), "returned list size should match input");
        assertEquals("suite.ATest", returnedSuites.get(0), "first suite should be preserved");
        assertEquals("suite.BTest", returnedSuites.get(1), "second suite should be preserved");
        assertEquals("suite.CTest", returnedSuites.get(2), "third suite should be preserved");
    }

    /**
     * Verifies that an empty suite list is handled correctly - the group can have zero suites
     * with a zero estimated time.
     */
    @Test
    void constructor_acceptsEmptySuiteList() {
        // given - an empty list
        List<String> emptySuites = new ArrayList<>();

        // when - constructing a group with no suites
        SuiteGroup group = new SuiteGroup(0, emptySuites, 0L);

        // then - the group is valid with zero suites
        assertEquals(0, group.getSuiteNames().size(), "group should have zero suites");
        assertEquals(0L, group.getEstimatedMs(), "estimated time should be zero");
    }

    /**
     * Verifies that a group with a single suite works correctly.
     */
    @Test
    void constructor_acceptsSingleSuite() {
        // given - a list with one suite
        List<String> singleSuite = new ArrayList<>();
        singleSuite.add("suite.OnlyTest");

        // when
        SuiteGroup group = new SuiteGroup(0, singleSuite, 2500L);

        // then
        assertEquals(1, group.getSuiteNames().size(), "group should have one suite");
        assertEquals("suite.OnlyTest", group.getSuiteNames().get(0), "single suite should match");
        assertEquals(2500L, group.getEstimatedMs(), "estimated ms should match");
    }
}
