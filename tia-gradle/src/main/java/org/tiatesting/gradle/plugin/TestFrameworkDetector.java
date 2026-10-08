package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Picks the {@link TestFrameworkAdapter} for a project: an explicit {@code tia { testFramework = ...
 * }} wins, otherwise the framework is detected from the groups of the project's declared test
 * dependencies. Declared rather than resolved, so detection never resolves a configuration at
 * configuration time; a framework that only arrives through a BOM or a platform is not detected and
 * needs the explicit setting, which every failure message names.
 *
 * <p>Spock wins when both Spock and JUnit Jupiter are declared: Spock 2 runs on the JUnit Platform
 * and Spock projects routinely declare {@code org.junit.jupiter} too, so the pair is far more often a
 * Spock project than a JUnit 5 one. A JUnit 5 project that also declares Spock sets
 * {@code testFramework = 'junit5'}. Detection warns whenever it has to make that choice, and an
 * explicit {@code testFramework} silences the warning.
 */
public final class TestFrameworkDetector {

    private static final Logger LOGGER = Logging.getLogger(TestFrameworkDetector.class);

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
     * @throws UnsupportedTestFrameworkException if detection finds no supported framework
     * @throws GradleException if the override names no supported framework
     */
    public static TestFrameworkAdapter detect(final String override, final Collection<String> declaredGroups) {
        // In precedence order: the first declared one wins detection.
        List<TestFrameworkAdapter> adapters = Arrays.asList(new SpockFrameworkAdapter(), new Junit5FrameworkAdapter());

        if (override != null && !override.trim().isEmpty()) {
            String name = override.trim().toLowerCase(Locale.ROOT);
            return adapters.stream()
                    .filter(adapter -> adapter.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new GradleException("Unknown Tia test framework '" + override
                            + "'. Supported values: " + names(adapters) + "."));
        }

        List<TestFrameworkAdapter> detected = adapters.stream()
                .filter(adapter -> declares(declaredGroups, adapter.dependencyGroup()))
                .collect(Collectors.toList());
        if (detected.isEmpty()) {
            throw new UnsupportedTestFrameworkException("Tia could not detect the test framework from this "
                    + "project's declared test dependencies (it looks for "
                    + adapters.stream().map(TestFrameworkAdapter::dependencyGroup).collect(Collectors.joining(" or "))
                    + "). " + settingHint(adapters));
        }
        TestFrameworkAdapter chosen = detected.get(0);
        if (detected.size() > 1) {
            // A warning, not info: a JUnit 5 project that only pulls Spock in (a shared test-utils
            // convention, say) would otherwise get no skipping and no mapping with nothing visible
            // saying why. Setting testFramework explicitly silences it.
            LOGGER.warn("Tia found more than one test framework in this project's declared test dependencies "
                    + "{} and uses {}. If this project's tests are not {} tests, Tia will neither skip nor map "
                    + "them. {}", names(detected), chosen.name(), chosen.name(), settingHint(adapters));
        }
        return chosen;
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
     * List adapters by name, for the detection messages.
     *
     * @param adapters the adapters to name
     * @return their names, e.g. {@code [spock, junit5]}
     */
    private static String names(final List<TestFrameworkAdapter> adapters) {
        return adapters.stream().map(TestFrameworkAdapter::name).collect(Collectors.toList()).toString();
    }

    /**
     * Build the sentence every detection message ends with, naming the setting that overrides
     * detection and the values it accepts.
     *
     * @param adapters the supported adapters
     * @return the hint naming the setting that picks the framework explicitly
     */
    private static String settingHint(final List<TestFrameworkAdapter> adapters) {
        return "Set tia { testFramework = ... } to one of " + names(adapters) + " to choose explicitly.";
    }
}
