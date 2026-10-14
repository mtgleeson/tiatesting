package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the plugin's minimum Gradle version check.
 */
class TiaPluginGradleVersionTest {

    @Test
    void olderGradleVersionFailsWithClearMessage() {
        // given
        GradleVersion current = GradleVersion.version("6.0.1");

        // when
        GradleException e = assertThrows(GradleException.class,
                () -> TiaPlugin.checkMinimumGradleVersion(current));

        // then
        assertTrue(e.getMessage().contains("requires Gradle 6.1 or newer"), e.getMessage());
        assertTrue(e.getMessage().contains("Gradle 6.0.1"), e.getMessage());
    }

    @Test
    void minimumGradleVersionIsAccepted() {
        // given
        GradleVersion current = GradleVersion.version("6.1");

        // when / then
        assertDoesNotThrow(() -> TiaPlugin.checkMinimumGradleVersion(current));
    }

    @Test
    void releaseCandidateOfMinimumGradleVersionIsAccepted() {
        // given
        GradleVersion current = GradleVersion.version("6.1-rc-1");

        // when / then
        assertDoesNotThrow(() -> TiaPlugin.checkMinimumGradleVersion(current));
    }

    @Test
    void newerGradleVersionIsAccepted() {
        // given
        GradleVersion current = GradleVersion.version("9.1.0");

        // when / then
        assertDoesNotThrow(() -> TiaPlugin.checkMinimumGradleVersion(current));
    }

    @Test
    void pluginMethodsNameNoBuildServiceType() {
        // given - Gradle inspects every declared method of the plugin class before apply() runs,
        // so a build service type (Gradle 6.1+) in any signature fails older Gradle before the
        // version check can report it
        List<String> offending = new ArrayList<>();

        // when
        for (Method method : TiaPlugin.class.getDeclaredMethods()) {
            List<Class<?>> types = new ArrayList<>(Arrays.asList(method.getParameterTypes()));
            types.add(method.getReturnType());
            for (Class<?> type : types) {
                if (type.getName().startsWith("org.gradle.api.services.")) {
                    offending.add(method.getName() + " -> " + type.getName());
                }
            }
        }

        // then
        assertTrue(offending.isEmpty(), offending.toString());
    }

    @Test
    void currentGradleVersionIsAccepted() {
        // given - the Gradle version running this test build

        // when / then
        assertDoesNotThrow(() -> TiaPlugin.checkMinimumGradleVersion(GradleVersion.current()));
    }
}
