package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.RunOrigin;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link RunEnvironment}'s run-source resolution: the precedence between the declared
 * overrides and the CI marker variables, and the detection itself.
 */
class RunEnvironmentTest {

    /**
     * Build an environment lookup over a fixed map, standing in for {@code System::getenv}.
     *
     * @param entries alternating variable name and value pairs
     * @return a lookup returning the mapped value, or null for anything unmapped
     */
    private UnaryOperator<String> env(final String... entries) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(entries[i], entries[i + 1]);
        }
        return map::get;
    }

    @Test
    void noMarkerVariablesResolvesToLocal() {
        // given
        UnaryOperator<String> environment = env("PATH", "/usr/bin", "HOME", "/home/dev");

        // when
        String runSource = RunEnvironment.runSource(null, environment);

        // then
        assertEquals(RunOrigin.SOURCE_LOCAL, runSource);
    }

    @Test
    void theConventionalCiVariableResolvesToCi() {
        // given
        UnaryOperator<String> environment = env("CI", "true");

        // when
        String runSource = RunEnvironment.runSource(null, environment);

        // then
        assertEquals(RunOrigin.SOURCE_CI, runSource);
    }

    /**
     * Jenkins does not set {@code CI}, so a marker set covering only that variable would label every
     * Jenkins build LOCAL - the failure mode that makes a mislabelled column worse than no column.
     */
    @Test
    void aCiSystemSpecificVariableResolvesToCi() {
        // given
        UnaryOperator<String> environment = env("BUILD_NUMBER", "4711");

        // when
        String runSource = RunEnvironment.runSource(null, environment);

        // then
        assertEquals(RunOrigin.SOURCE_CI, runSource);
    }

    /**
     * Presence is the signal, not the value: a CI system is still a CI system whatever it sets its
     * marker to.
     */
    @Test
    void aMarkerVariableWithAnUnexpectedValueStillResolvesToCi() {
        // given
        UnaryOperator<String> environment = env("CI", "false");

        // when
        String runSource = RunEnvironment.runSource(null, environment);

        // then
        assertEquals(RunOrigin.SOURCE_CI, runSource);
    }

    /**
     * An exported-but-empty variable is not a marker - some shells export empty values wholesale, and
     * treating that as CI would label developer machines wrongly.
     */
    @Test
    void anEmptyMarkerVariableDoesNotResolveToCi() {
        // given
        UnaryOperator<String> environment = env("CI", "   ");

        // when
        String runSource = RunEnvironment.runSource(null, environment);

        // then
        assertEquals(RunOrigin.SOURCE_LOCAL, runSource);
    }

    @Test
    void theSystemPropertyOverrideWinsOverDetection() {
        // given
        UnaryOperator<String> environment = env("CI", "true");

        // when
        String runSource = RunEnvironment.runSource("NIGHTLY", environment);

        // then
        assertEquals("NIGHTLY", runSource);
    }

    @Test
    void theEnvironmentOverrideWinsOverDetection() {
        // given
        UnaryOperator<String> environment = env(RunEnvironment.ENV_RUN_SOURCE, "NIGHTLY", "CI", "true");

        // when
        String runSource = RunEnvironment.runSource(null, environment);

        // then
        assertEquals("NIGHTLY", runSource);
    }

    @Test
    void theSystemPropertyOverrideWinsOverTheEnvironmentOverride() {
        // given
        UnaryOperator<String> environment = env(RunEnvironment.ENV_RUN_SOURCE, "FROM-ENV");

        // when
        String runSource = RunEnvironment.runSource("FROM-PROPERTY", environment);

        // then
        assertEquals("FROM-PROPERTY", runSource);
    }

    @Test
    void anOverrideIsTrimmed() {
        // given
        UnaryOperator<String> environment = env();

        // when
        String runSource = RunEnvironment.runSource("  CI  ", environment);

        // then
        assertEquals(RunOrigin.SOURCE_CI, runSource);
    }

    @Test
    void aBlankOverrideFallsBackToDetection() {
        // given
        UnaryOperator<String> environment = env("CI", "true");

        // when
        String runSource = RunEnvironment.runSource("   ", environment);

        // then
        assertEquals(RunOrigin.SOURCE_CI, runSource);
    }

    /**
     * A distributed build's row describes work several machines did between them, so naming the one
     * that happened to seal last would misrepresent it.
     */
    @Test
    void aDistributedRunOriginCarriesNoHost() {
        // given
        // nothing to arrange - reads this JVM's real environment

        // when
        RunOrigin origin = RunEnvironment.distributedRunOrigin();

        // then
        assertEquals(null, origin.getHostName(),
                "a distributed build must not be attributed to a single host");
        assertNotNull(origin.getRunSource(), "the run source still applies to a distributed build");
    }

    @Test
    void theCurrentRunOriginCarriesBothComponents() {
        // given
        // nothing to arrange - reads this JVM's real environment

        // when
        RunOrigin origin = RunEnvironment.currentRunOrigin();

        // then
        assertNotNull(origin.getRunSource());
        assertNotNull(origin.getHostName(), "the local hostname resolves on a normal test machine");
    }
}
