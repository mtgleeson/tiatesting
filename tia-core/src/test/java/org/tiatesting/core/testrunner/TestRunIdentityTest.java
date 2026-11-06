package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the run identity the build tool hands to every test JVM.
 */
class TestRunIdentityTest {

    /**
     * Remove the properties a test published.
     */
    @AfterEach
    void clearProperties() {
        System.clearProperty(TestRunIdentity.PROP_TEST_RUN_ID);
        System.clearProperty(TestRunIdentity.PROP_TEST_RUN_START_MS);
    }

    @Test
    void roundTripsThroughTheForkProperties() {
        // given a new run published the way the build tool publishes it
        TestRunIdentity identity = TestRunIdentity.newRun(1234L);
        for (Map.Entry<String, String> property : identity.toProperties().entrySet()) {
            System.setProperty(property.getKey(), property.getValue());
        }

        // when
        TestRunIdentity read = TestRunIdentity.fromSystemProperties();

        // then
        assertEquals(identity.getRunId(), read.getRunId());
        assertEquals(1234L, read.getStartMs());
    }

    @Test
    void everyNewRunHasItsOwnId() {
        // given
        // when
        TestRunIdentity first = TestRunIdentity.newRun(1L);
        TestRunIdentity second = TestRunIdentity.newRun(1L);

        // then
        assertNotEquals(first.getRunId(), second.getRunId());
    }

    @Test
    void aMissingIdNamesTheProperty() {
        // given no run identity in this JVM
        // when
        IllegalStateException e = assertThrows(IllegalStateException.class, TestRunIdentity::fromSystemProperties);

        // then
        assertTrue(e.getMessage().contains(TestRunIdentity.PROP_TEST_RUN_ID));
    }
}
