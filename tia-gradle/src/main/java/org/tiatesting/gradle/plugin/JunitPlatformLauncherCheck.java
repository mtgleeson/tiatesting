package org.tiatesting.gradle.plugin;

import org.gradle.util.GradleVersion;

import java.io.File;

/**
 * Finds a test task whose JUnit Platform launcher Tia cannot work with. Both Tia listeners (JUnit 5/6
 * and Spock) register as a {@code LauncherSessionListener}, which the launcher added in 1.8. Two
 * setups break them:
 * <ul>
 *   <li>The project declares a launcher older than 1.8, which never calls the listeners: the tests
 *       run, but Tia records nothing and reports no error.</li>
 *   <li>The project declares no launcher on Gradle before 8, so Gradle supplies the one it bundles.
 *       Gradle 6.x bundles one older than 1.8 (the same silent failure), and Gradle 7.x loads its
 *       bundled launcher where Tia's listener cannot see it, failing the test JVM with
 *       {@code NoClassDefFoundError: org/junit/platform/launcher/LauncherSessionListener}. Gradle 8
 *       puts its bundled 1.8.2 launcher where the listener sees it, and Gradle 9 bundles none and
 *       fails the task itself.</li>
 * </ul>
 * See the "Supported Gradle versions" chapter in {@code WIKI.md}.
 *
 * <p>The check only reads jar file names on the test task's classpath, which the task resolves to run
 * anyway.
 */
final class JunitPlatformLauncherCheck {

    /** File name prefix of the launcher jar, followed by its version. */
    static final String LAUNCHER_JAR_PREFIX = "junit-platform-launcher-";

    /** The first Gradle whose bundled launcher Tia's listeners can use. */
    static final GradleVersion FIRST_GRADLE_WITH_USABLE_BUNDLED_LAUNCHER = GradleVersion.version("8.0");

    private JunitPlatformLauncherCheck() {
    }

    /**
     * Build the warning for a test task whose launcher Tia cannot work with: a declared launcher older
     * than 1.8, or none declared on a Gradle before 8.
     *
     * @param taskPath the test task's path, named in the warning
     * @param testClasspath the test task's runtime classpath
     * @param gradleVersion the running Gradle version
     * @return the warning, or {@code null} when Tia can work with the task's launcher
     */
    static String warningFor(final String taskPath, final Iterable<File> testClasspath,
                             final GradleVersion gradleVersion) {
        String declared = launcherVersion(testClasspath);
        if (declared != null) {
            return supportsSessionListener(declared) ? null
                    : warning(taskPath, "declares junit-platform-launcher " + declared + ", but Tia needs 1.8 "
                            + "or newer and records nothing on an older one, although the tests still run",
                            "Upgrade it to the version matching your JUnit dependencies.");
        }
        if (gradleVersion.getBaseVersion().compareTo(FIRST_GRADLE_WITH_USABLE_BUNDLED_LAUNCHER) >= 0) {
            return null;
        }
        return warning(taskPath, "declares no junit-platform-launcher, so Gradle " + gradleVersion.getVersion()
                        + " supplies its own bundled launcher, which Tia cannot use before Gradle 8: the run "
                        + "records nothing (Gradle 6) or fails with NoClassDefFoundError (Gradle 7)",
                "Declare testRuntimeOnly 'org.junit.platform:junit-platform-launcher' matching your "
                        + "JUnit dependencies.");
    }

    /**
     * Find the launcher's version among a set of jars by its file name.
     *
     * @param files the jars to search
     * @return the version of the first {@code junit-platform-launcher-<version>.jar}, or {@code null}
     */
    static String launcherVersion(final Iterable<File> files) {
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(LAUNCHER_JAR_PREFIX) && name.endsWith(".jar")) {
                return name.substring(LAUNCHER_JAR_PREFIX.length(), name.length() - ".jar".length());
            }
        }
        return null;
    }

    /**
     * Whether a launcher version has {@code LauncherSessionListener}, added in JUnit Platform 1.8.
     * JUnit 6 numbers the launcher 6.x, so any major version above 1 qualifies. A version that does
     * not parse is assumed to qualify, so the check never warns on a guess.
     *
     * @param version the launcher version, e.g. {@code 1.3.1}, {@code 1.10.0-M1} or {@code 6.0.0}
     * @return {@code true} if the launcher calls Tia's listeners
     */
    static boolean supportsSessionListener(final String version) {
        String[] parts = version.split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 1 || (major == 1 && minor >= 8);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * Word the warning.
     *
     * @param taskPath the test task's path
     * @param cause what is wrong with the task's launcher setup and what it does to Tia
     * @param fix what the user should change
     * @return the full warning
     */
    private static String warning(final String taskPath, final String cause, final String fix) {
        return "Tia: test task '" + taskPath + "' " + cause + ". " + fix
                + " See the \"Supported Gradle versions\" chapter of the Tia wiki.";
    }
}
