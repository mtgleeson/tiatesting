package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code developerDisabled} flag on {@link TestSuiteTracker}: it defaults to
 * {@code false} for a freshly-constructed tracker, round-trips through the setter/getter, and is
 * surfaced in {@code toString}.
 */
class TestSuiteTrackerDeveloperDisabledTest {

    /**
     * A newly-constructed tracker is not developer-disabled until something sets the flag.
     */
    @Test
    void developerDisabled_defaultsToFalse(){
        // given
        TestSuiteTracker tracker = new TestSuiteTracker("com.example.FooTest");

        // when
        boolean disabled = tracker.isDeveloperDisabled();

        // then
        assertFalse(disabled);
    }

    /**
     * Setting the flag to {@code true} is reflected by the getter.
     */
    @Test
    void developerDisabled_setterRoundTrips(){
        // given
        TestSuiteTracker tracker = new TestSuiteTracker("com.example.FooTest");

        // when
        tracker.setDeveloperDisabled(true);

        // then
        assertTrue(tracker.isDeveloperDisabled());
    }

    /**
     * The flag value appears in {@code toString} so it shows up in debug logging.
     */
    @Test
    void developerDisabled_includedInToString(){
        // given
        TestSuiteTracker tracker = new TestSuiteTracker("com.example.FooTest");
        tracker.setDeveloperDisabled(true);

        // when
        String text = tracker.toString();

        // then
        assertTrue(text.contains("developerDisabled=true"));
    }
}
