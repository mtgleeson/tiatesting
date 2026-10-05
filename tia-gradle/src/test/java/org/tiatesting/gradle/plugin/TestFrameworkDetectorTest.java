package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link TestFrameworkDetector} picks the adapter: an override wins, Spock is detected
 * from its declared group, and every case it cannot settle fails naming {@code testFramework}.
 */
class TestFrameworkDetectorTest {

    @Test
    void spockIsDetectedFromItsDeclaredGroup() {
        // given
        List<String> groups = Arrays.asList("org.codehaus.groovy", "org.spockframework", "org.junit");

        // when
        TestFrameworkAdapter adapter = TestFrameworkDetector.detect(null, groups);

        // then
        assertEquals(SpockFrameworkAdapter.NAME, adapter.name());
    }

    @Test
    void overrideWinsOverDetection() {
        // given - both frameworks declared, which detection alone refuses
        List<String> groups = Arrays.asList("org.spockframework", "org.junit.jupiter");

        // when
        TestFrameworkAdapter adapter = TestFrameworkDetector.detect(" Spock ", groups);

        // then
        assertEquals(SpockFrameworkAdapter.NAME, adapter.name());
    }

    @Test
    void noFrameworkDetectedIsUnsupportedNamingTheSetting() {
        // given
        List<String> groups = Collections.singletonList("com.example");

        // when
        UnsupportedTestFrameworkException exception = assertThrows(UnsupportedTestFrameworkException.class,
                () -> TestFrameworkDetector.detect(null, groups));

        // then
        assertTrue(exception.getMessage().contains("testFramework"), exception.getMessage());
    }

    @Test
    void bothFrameworksDetectedFailsNamingTheSetting() {
        // given
        List<String> groups = Arrays.asList("org.spockframework", "org.junit.jupiter");

        // when
        GradleException exception = assertThrows(GradleException.class,
                () -> TestFrameworkDetector.detect(null, groups));

        // then
        assertTrue(exception.getMessage().contains("[spock, junit5]"), exception.getMessage());
        assertTrue(exception.getMessage().contains("testFramework"), exception.getMessage());
    }

    @Test
    void junit5OnlyIsUnsupported() {
        // given
        List<String> groups = Collections.singletonList("org.junit.jupiter");

        // when
        UnsupportedTestFrameworkException exception = assertThrows(UnsupportedTestFrameworkException.class,
                () -> TestFrameworkDetector.detect(null, groups));

        // then
        assertTrue(exception.getMessage().contains("does not support JUnit 5 yet"), exception.getMessage());
    }

    @Test
    void unknownOverrideFailsListingSupportedValues() {
        // given
        List<String> groups = Collections.singletonList("org.spockframework");

        // when
        GradleException exception = assertThrows(GradleException.class,
                () -> TestFrameworkDetector.detect("testng", groups));

        // then
        assertTrue(exception.getMessage().contains("'testng'"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[spock]"), exception.getMessage());
    }
}
