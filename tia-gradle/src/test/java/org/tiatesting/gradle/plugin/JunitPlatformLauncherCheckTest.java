package org.tiatesting.gradle.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the warning for a test task whose JUnit Platform launcher is too old to call Tia's
 * listeners.
 */
class JunitPlatformLauncherCheckTest {

    @TempDir
    File gradleHome;

    @Test
    void declaredLauncherOlderThan18IsWarned() {
        // given
        List<File> classpath = Arrays.asList(new File("junit-jupiter-api-5.7.0.jar"),
                new File("junit-platform-launcher-1.7.2.jar"));

        // when
        String warning = JunitPlatformLauncherCheck.warningFor(":test", classpath, null, "8.4");

        // then
        assertTrue(warning.contains("':test' declares junit-platform-launcher 1.7.2"), warning);
        assertTrue(warning.contains("records nothing"), warning);
    }

    @Test
    void declaredLauncherWinsOverAnOldBundledOne() throws IOException {
        // given
        bundle("junit-platform-launcher-1.3.1.jar");
        List<File> classpath = Collections.singletonList(new File("junit-platform-launcher-1.11.3.jar"));

        // when
        String warning = JunitPlatformLauncherCheck.warningFor(":test", classpath, gradleHome, "6.1");

        // then
        assertNull(warning);
    }

    @Test
    void oldBundledLauncherIsWarnedWhenNoneIsDeclared() throws IOException {
        // given
        bundle("junit-platform-engine-1.3.1.jar", "junit-platform-launcher-1.3.1.jar");
        List<File> classpath = Collections.singletonList(new File("junit-jupiter-api-5.7.0.jar"));

        // when
        String warning = JunitPlatformLauncherCheck.warningFor(":test", classpath, gradleHome, "6.1");

        // then
        assertTrue(warning.contains("1.3.1 launcher bundled with Gradle 6.1"), warning);
        assertTrue(warning.contains("testRuntimeOnly 'org.junit.platform:junit-platform-launcher'"), warning);
    }

    @Test
    void bundled18LauncherIsNotWarned() throws IOException {
        // given
        bundle("junit-platform-launcher-1.8.2.jar");

        // when
        String warning = JunitPlatformLauncherCheck.warningFor(":test", Collections.<File>emptyList(),
                gradleHome, "8.14");

        // then
        assertNull(warning);
    }

    @Test
    void noLauncherAnywhereIsNotWarned() {
        // given - Gradle 9 bundles none and fails the task itself

        // when
        String warning = JunitPlatformLauncherCheck.warningFor(":test", Collections.<File>emptyList(),
                gradleHome, "9.8.1");

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

    /**
     * Put empty jars in the fake Gradle home's bundled-jars directory.
     *
     * @param names the jar file names
     * @throws IOException if a file cannot be created
     */
    private void bundle(final String... names) throws IOException {
        File dir = new File(gradleHome, JunitPlatformLauncherCheck.GRADLE_BUNDLED_DIR);
        assertTrue(dir.mkdirs());
        for (String name : names) {
            assertTrue(new File(dir, name).createNewFile());
        }
    }
}
