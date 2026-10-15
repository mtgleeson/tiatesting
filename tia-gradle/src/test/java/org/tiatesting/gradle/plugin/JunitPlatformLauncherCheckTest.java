package org.tiatesting.gradle.plugin;

import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the warning for a test task whose JUnit Platform launcher Tia cannot work with.
 */
class JunitPlatformLauncherCheckTest {

    private static final List<File> NO_LAUNCHER = Collections.singletonList(new File("junit-jupiter-api-5.7.0.jar"));

    @Test
    void declaredLauncherOlderThan18IsWarned() {
        // given
        List<File> classpath = Arrays.asList(new File("junit-jupiter-api-5.7.0.jar"),
                new File("junit-platform-launcher-1.7.2.jar"));

        // when
        String warning = JunitPlatformLauncherCheck.warningFor(":test", classpath, GradleVersion.version("8.4"));

        // then
        assertTrue(warning.contains("':test' declares junit-platform-launcher 1.7.2"), warning);
        assertTrue(warning.contains("records nothing"), warning);
    }

    @Test
    void declaredLauncher18OrNewerIsNotWarnedOnOldGradle() {
        // given
        List<File> classpath = Collections.singletonList(new File("junit-platform-launcher-1.11.3.jar"));

        // when
        String warning = JunitPlatformLauncherCheck.warningFor(":test", classpath, GradleVersion.version("6.1"));

        // then
        assertNull(warning);
    }

    @Test
    void noDeclaredLauncherIsWarnedOnGradle6() {
        // given - Gradle 6.x bundles a launcher older than 1.8

        // when
        String warning = JunitPlatformLauncherCheck.warningFor(":test", NO_LAUNCHER, GradleVersion.version("6.5.1"));

        // then
        assertTrue(warning.contains("declares no junit-platform-launcher, so Gradle 6.5.1"), warning);
        assertTrue(warning.contains("testRuntimeOnly 'org.junit.platform:junit-platform-launcher'"), warning);
    }

    @Test
    void noDeclaredLauncherIsWarnedOnGradle7() {
        // given - Gradle 7.x bundles 1.8.2 from 7.5, but loads it where Tia's listener cannot see it

        // when
        String warning = JunitPlatformLauncherCheck.warningFor(":test", NO_LAUNCHER, GradleVersion.version("7.6.4"));

        // then
        assertTrue(warning.contains("NoClassDefFoundError"), warning);
    }

    @Test
    void noDeclaredLauncherIsNotWarnedOnGradle8OrNewer() {
        // given - Gradle 8 bundles a usable launcher; Gradle 9 bundles none and fails the task itself

        // when
        String onGradle8 = JunitPlatformLauncherCheck.warningFor(":test", NO_LAUNCHER, GradleVersion.version("8.3"));
        String onGradle9 = JunitPlatformLauncherCheck.warningFor(":test", NO_LAUNCHER, GradleVersion.version("9.8.1"));

        // then
        assertNull(onGradle8);
        assertNull(onGradle9);
    }

    @Test
    void releaseCandidateOfGradle8IsTreatedAsGradle8() {
        // given
        GradleVersion gradle = GradleVersion.version("8.0-rc-1");

        // when
        String warning = JunitPlatformLauncherCheck.warningFor(":test", NO_LAUNCHER, gradle);

        // then
        assertNull(warning);
    }

    @Test
    void launcherVersionIsReadFromTheJarName() {
        // given
        List<File> files = Arrays.asList(new File("junit-platform-engine-1.10.0.jar"),
                new File("junit-platform-launcher-1.10.0-M1.jar"));

        // when
        String version = JunitPlatformLauncherCheck.launcherVersion(files);

        // then
        assertEquals("1.10.0-M1", version);
    }

    @Test
    void sessionListenerSupportStartsAt18() {
        // given / when / then
        assertFalse(JunitPlatformLauncherCheck.supportsSessionListener("1.3.1"));
        assertFalse(JunitPlatformLauncherCheck.supportsSessionListener("1.7.2"));
        assertTrue(JunitPlatformLauncherCheck.supportsSessionListener("1.8.0-RC1"));
        assertTrue(JunitPlatformLauncherCheck.supportsSessionListener("1.10.0"));
        assertTrue(JunitPlatformLauncherCheck.supportsSessionListener("6.0.0"));
    }

    @Test
    void unparseableVersionIsNotWarned() {
        // given
        String version = "SNAPSHOT";

        // when
        boolean supported = JunitPlatformLauncherCheck.supportsSessionListener(version);

        // then
        assertTrue(supported);
    }
}
