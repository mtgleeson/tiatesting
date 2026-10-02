package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRunSelectionDetailsTest {

    @Test
    public void filtersAndSortsTriggersByCountDescending() {
        // given
        List<TestRunTrigger> triggers = Arrays.asList(
                new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, "Foo.a", 5),
                new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, "Foo.b", 20),
                new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, "MDP", 1009));
        TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 1, 2, 3, 4, 5,
                SelectionMode.SELECTIVE);

        // when
        List<TestRunTrigger> methods = details.getSourceMethodTriggers();
        List<TestRunTrigger> rules = details.getStaticRuleTriggers();

        // then
        assertEquals(2, methods.size());
        assertEquals("Foo.b", methods.get(0).getName());
        assertEquals("Foo.a", methods.get(1).getName());
        assertEquals(1, rules.size());
        assertEquals("MDP", rules.get(0).getName());
        assertEquals(1, details.getNumModifiedTestFiles());
        assertEquals(2, details.getNumNewTestFiles());
        assertEquals(3, details.getNumPreviouslyFailed());
        assertEquals(4, details.getNumUnsealedMapping());
        assertEquals(5, details.getNumPendingLibrary());
    }

    @Test
    public void emptyHasNoTriggersAndZeroCounters() {
        // given

        // when
        TestRunSelectionDetails empty = TestRunSelectionDetails.empty();

        // then
        assertTrue(empty.getTriggers().isEmpty());
        assertEquals(0, empty.getNumModifiedTestFiles());
        assertEquals(0, empty.getNumPendingLibrary());
    }

    /**
     * Verify that {@link TestRunSelectionDetails#forFullRun(SelectionMode)} carries the given mode
     * with no triggers and every counter at zero, since a full run has nothing to attribute.
     */
    @Test
    public void forFullRunCarriesTheModeWithEmptyCounters() {
        // given
        SelectionMode mode = SelectionMode.SELECT_ALL;

        // when
        TestRunSelectionDetails details = TestRunSelectionDetails.forFullRun(mode);

        // then
        assertEquals(mode, details.getSelectionMode());
        assertTrue(details.getTriggers().isEmpty());
        assertEquals(0, details.getNumModifiedTestFiles());
    }

    /**
     * Verify that {@link TestRunSelectionDetails#withSelectionMode(SelectionMode)} returns a copy
     * with every trigger and counter unchanged and only the mode replaced, leaving the original
     * breakdown untouched.
     */
    @Test
    public void withSelectionModeKeepsEveryCounter() {
        // given
        TestRunSelectionDetails details = new TestRunSelectionDetails(Collections.emptyList(),
                1, 2, 3, 4, 5, SelectionMode.SELECTIVE);

        // when
        TestRunSelectionDetails copy = details.withSelectionMode(SelectionMode.RESEED);

        // then
        assertEquals(SelectionMode.RESEED, copy.getSelectionMode());
        assertEquals(5, copy.getNumPendingLibrary());
        assertEquals(SelectionMode.SELECTIVE, details.getSelectionMode());
    }
}
