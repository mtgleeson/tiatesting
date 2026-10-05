package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Picks the {@link TestFrameworkAdapter} for a project: an explicit {@code tia { testFramework = ...
 * }} wins, otherwise the framework is detected from the groups of the project's declared test
 * dependencies. Declared rather than resolved, so detection never resolves a configuration at
 * configuration time; a framework that only arrives through a BOM or a platform is not detected and
 * needs the explicit setting, which every failure message names.
 */
public final class TestFrameworkDetector {

    /** Name of JUnit 5, recognised so its projects get a clear message until it has an adapter. */
    static final String JUNIT5 = "junit5";

    private static final String JUNIT5_GROUP = "org.junit.jupiter";

    private static final String SETTING_HINT = "Set tia { testFramework = '" + SpockFrameworkAdapter.NAME + "' }.";

    /**
     * Static utility; not instantiable.
     */
    private TestFrameworkDetector() {
    }

    /**
     * Choose the adapter for a project.
     *
     * @param override the configured {@code testFramework}, or null to detect
     * @param declaredGroups the groups of the project's declared test dependencies
     * @return the adapter to use
     * @throws GradleException if the override is unknown or unsupported, or detection finds no
     *                         supported framework or more than one framework
     */
    public static TestFrameworkAdapter detect(final String override, final Collection<String> declaredGroups) {
        TestFrameworkAdapter spock = new SpockFrameworkAdapter();
        if (override != null && !override.trim().isEmpty()) {
            String name = override.trim().toLowerCase(Locale.ROOT);
            if (name.equals(spock.name())) {
                return spock;
            }
            if (name.equals(JUNIT5)) {
                throw junit5NotSupported();
            }
            throw new GradleException("Unknown Tia test framework '" + override + "'. Supported values: ["
                    + spock.name() + "].");
        }

        List<String> detected = new ArrayList<>();
        if (declares(declaredGroups, spock.dependencyGroup())) {
            detected.add(spock.name());
        }
        if (declares(declaredGroups, JUNIT5_GROUP)) {
            detected.add(JUNIT5);
        }

        if (detected.isEmpty()) {
            throw new GradleException("Tia could not detect the test framework from this project's declared "
                    + "test dependencies (it looks for " + spock.dependencyGroup() + "). " + SETTING_HINT);
        }
        if (detected.size() > 1) {
            throw new GradleException("Tia detected more than one test framework in this project's declared "
                    + "test dependencies " + detected + ", and mixed projects are not supported yet. "
                    + SETTING_HINT);
        }
        if (detected.get(0).equals(JUNIT5)) {
            throw junit5NotSupported();
        }
        return spock;
    }

    /**
     * @param declaredGroups the declared dependency groups
     * @param group the group to look for; a declared group starting with it also matches
     * @return true when a declared dependency is in the group
     */
    private static boolean declares(final Collection<String> declaredGroups, final String group) {
        return declaredGroups.stream().anyMatch(declared -> declared != null && declared.startsWith(group));
    }

    /**
     * @return the failure for a JUnit 5 project, which the Gradle plugin does not support yet
     */
    private static GradleException junit5NotSupported() {
        return new GradleException("Tia's Gradle plugin does not support JUnit 5 yet; it supports Spock. "
                + "If this project's Tia tests are Spock specs, " + SETTING_HINT);
    }
}
