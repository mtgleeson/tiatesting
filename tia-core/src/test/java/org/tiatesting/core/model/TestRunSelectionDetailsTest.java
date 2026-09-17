package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
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
        TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 1, 2, 3, 4, 5);

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
}
