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
 * Verifies how {@link TestFrameworkDetector} picks the adapter: an override wins, Spock and JUnit 5
 * are detected from their declared groups, Spock wins when both are declared, and a project with
 * neither is unsupported with a message naming {@code testFramework}.
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
    void junit5IsDetectedFromItsDeclaredGroup() {
        // given
        List<String> groups = Arrays.asList("org.junit", "org.junit.jupiter");

        // when
        TestFrameworkAdapter adapter = TestFrameworkDetector.detect(null, groups);

        // then
        assertEquals(Junit5FrameworkAdapter.NAME, adapter.name());
    }

    @Test
    void spockWinsWhenBothFrameworksAreDeclared() {
        // given - a Spock project routinely declares JUnit Jupiter too
        List<String> groups = Arrays.asList("org.junit.jupiter", "org.spockframework");

        // when
        TestFrameworkAdapter adapter = TestFrameworkDetector.detect(null, groups);

        // then
        assertEquals(SpockFrameworkAdapter.NAME, adapter.name());
    }

    @Test
    void junit5OverrideWinsWhenBothFrameworksAreDeclared() {
        // given
        List<String> groups = Arrays.asList("org.spockframework", "org.junit.jupiter");

        // when
        TestFrameworkAdapter adapter = TestFrameworkDetector.detect(" JUnit5 ", groups);

        // then
        assertEquals(Junit5FrameworkAdapter.NAME, adapter.name());
    }

    @Test
    void overrideWinsOverDetection() {
        // given - only JUnit 5 declared, e.g. Spock arriving through a platform
        List<String> groups = Collections.singletonList("org.junit.jupiter");

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
        assertTrue(exception.getMessage().contains("[spock, junit5]"), exception.getMessage());
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
        assertTrue(exception.getMessage().contains("[spock, junit5]"), exception.getMessage());
    }
}
