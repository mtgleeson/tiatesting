package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRunTriggerTest {

    @Test
    public void exposesTypeNameAndCount() {
        // given
        TestRunTrigger trigger = new TestRunTrigger(
                TestRunTrigger.Type.SOURCE_METHOD, "com/example/Foo.save", 519);

        // when
        TestRunTrigger.Type type = trigger.getType();
        String name = trigger.getName();
        int testCount = trigger.getTestCount();

        // then
        assertEquals(TestRunTrigger.Type.SOURCE_METHOD, type);
        assertEquals("com/example/Foo.save", name);
        assertEquals(519, testCount);
    }

    @Test
    public void equalsComparesAllThreeFields() {
        // given
        TestRunTrigger a = new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, "MDP", 1009);
        TestRunTrigger same = new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, "MDP", 1009);
        TestRunTrigger differentCount = new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, "MDP", 1);

        // when
        boolean matchesSame = a.equals(same);
        boolean sameHash = a.hashCode() == same.hashCode();
        boolean matchesDifferent = a.equals(differentCount);

        // then
        assertTrue(matchesSame);
        assertTrue(sameHash);
        assertFalse(matchesDifferent);
    }

    @Test
    public void filterByTypeSortedByCountDescKeepsOneTypeOrderedByCountDescending() {
        // given
        List<TestRunTrigger> triggers = Arrays.asList(
                new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, "Foo.a", 5),
                new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, "MDP", 1009),
                new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, "Foo.b", 20));

        // when
        List<TestRunTrigger> methods = TestRunTrigger.filterByTypeSortedByCountDesc(
                triggers, TestRunTrigger.Type.SOURCE_METHOD);

        // then
        assertEquals(2, methods.size());
        assertEquals("Foo.b", methods.get(0).getName());
        assertEquals("Foo.a", methods.get(1).getName());
    }

    @Test
    public void filterByTypeSortedByCountDescTreatsNullListAsEmpty() {
        // given
        List<TestRunTrigger> triggers = null;

        // when
        List<TestRunTrigger> result = TestRunTrigger.filterByTypeSortedByCountDesc(
                triggers, TestRunTrigger.Type.STATIC_RULE);

        // then
        assertTrue(result.isEmpty());
    }
}
