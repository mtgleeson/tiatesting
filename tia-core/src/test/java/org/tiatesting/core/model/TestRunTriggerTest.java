package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

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
}
