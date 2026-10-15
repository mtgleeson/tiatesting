package org.tiatesting.gradle.plugin;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

/**
 * Finds a test task that will run on a JUnit Platform launcher too old for Tia. Both Tia listeners
 * (JUnit 5/6 and Spock) register as a {@code LauncherSessionListener}, which the launcher added in
 * 1.8; an older launcher never calls them, so the tests run but Tia records nothing and reports no
 * error. That happens when a project declares no launcher on a Gradle that bundles one older than 1.8
 * (Gradle 6.x bundles 1.3.1), or declares an old one itself. See the "Supported Gradle versions"
 * chapter in {@code WIKI.md}.
 *
 * <p>The check only reads jar file names: the test task's classpath, which the task resolves to run
 * anyway, and the Gradle distribution's {@code lib/plugins} directory.
 */
final class JunitPlatformLauncherCheck {

    /** File name prefix of the launcher jar, followed by its version. */
    static final String LAUNCHER_JAR_PREFIX = "junit-platform-launcher-";

    /** Directory under the Gradle home that holds the launcher Gradle bundles. */
    static final String GRADLE_BUNDLED_DIR = "lib/plugins";

    private JunitPlatformLauncherCheck() {
    }

    /**
     * Build the warning for a test task whose launcher cannot run Tia's listeners. The launcher the
     * project declares wins; with none declared, the one bundled with Gradle is used, if Gradle
     * bundles one (Gradle 9 does not, and fails the task itself).
     *
     * @param taskPath the test task's path, named in the warning
     * @param testClasspath the test task's runtime classpath
     * @param gradleHomeDir the Gradle distribution's home directory, or {@code null} if unknown
     * @param gradleVersion the running Gradle version, named in the warning
     * @return the warning, or {@code null} when the launcher supports Tia or none can be found
     */
    static String warningFor(final String taskPath, final Iterable<File> testClasspath,
                             final File gradleHomeDir, final String gradleVersion) {
        String declared = launcherVersion(testClasspath);
        if (declared != null) {
            return supportsSessionListener(declared) ? null
                    : warning(taskPath, "declares junit-platform-launcher " + declared,
                            "Upgrade it to the version matching your JUnit dependencies.");
        }
        String bundled = gradleHomeDir == null ? null : launcherVersion(bundledJars(gradleHomeDir));
        if (bundled == null || supportsSessionListener(bundled)) {
            return null;
        }
        return warning(taskPath, "declares no junit-platform-launcher, so it runs on the "
                        + bundled + " launcher bundled with Gradle " + gradleVersion,
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
     * List the jars Gradle bundles for its test workers.
     *
     * @param gradleHomeDir the Gradle distribution's home directory
     * @return the jars in its {@code lib/plugins} directory, empty if it has none
     */
    private static Iterable<File> bundledJars(final File gradleHomeDir) {
        File[] files = new File(gradleHomeDir, GRADLE_BUNDLED_DIR).listFiles();
        return files == null ? Collections.<File>emptyList() : Arrays.asList(files);
    }

    /**
     * Word the warning.
     *
     * @param taskPath the test task's path
     * @param cause what the task's launcher setup is
     * @param fix what the user should change
     * @return the full warning
     */
    private static String warning(final String taskPath, final String cause, final String fix) {
        return "Tia: test task '" + taskPath + "' " + cause + ". Tia needs JUnit Platform launcher 1.8 or "
                + "newer and records nothing on an older one, although the tests still run. " + fix
                + " See the \"Supported Gradle versions\" chapter of the Tia wiki.";
    }
}
