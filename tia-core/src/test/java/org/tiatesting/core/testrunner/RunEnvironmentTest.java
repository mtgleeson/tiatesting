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
        RunOrigin origin = RunEnvironment.distributedRunOrigin(null);

        // then
        assertEquals(null, origin.getHostName(),
                "a distributed build must not be attributed to a single host");
        assertNotNull(origin.getRunSource(), "the run source still applies to a distributed build");
    }

    /**
     * The seal runs in whichever runner's test JVM finishes last, often a container that cannot see
     * the CI marker variables, so the source the plan step recorded must win over the sealing JVM's
     * own detection.
     */
    @Test
    void aDistributedRunOriginUsesThePlannedRunSource() {
        // given - a label neither detection outcome could produce, so the assertion cannot pass by
        // accident whatever environment this test runs in
        String plannedRunSource = " NIGHTLY ";

        // when
        RunOrigin origin = RunEnvironment.distributedRunOrigin(plannedRunSource);

        // then
        assertEquals("NIGHTLY", origin.getRunSource());
        assertEquals(null, origin.getHostName());
    }

    /**
     * A run planned before the source was recorded reads back null, and a blank value is no label
     * either; both must fall back to the sealing JVM's own detection rather than record nothing.
     */
    @Test
    void aDistributedRunOriginFallsBackToDetectionWithoutAPlannedRunSource() {
        // given
        String blankPlannedRunSource = "   ";

        // when
        RunOrigin origin = RunEnvironment.distributedRunOrigin(blankPlannedRunSource);

        // then
        assertEquals(RunEnvironment.runSource(), origin.getRunSource());
    }

    /**
     * The plan step reads {@code tiaRunSource} from the build's configuration, so a declared label
     * must win over this JVM's detection, trimmed like every other override.
     */
    @Test
    void aDeclaredRunSourceWinsOverDetection() {
        // given
        String declaredRunSource = " NIGHTLY ";

        // when
        String runSource = RunEnvironment.runSource(declaredRunSource);

        // then
        assertEquals("NIGHTLY", runSource);
    }

    /**
     * With nothing declared the plan step detects the source exactly as any other step would.
     */
    @Test
    void anUndeclaredRunSourceFallsBackToDetection() {
        // given
        String declaredRunSource = null;

        // when
        String runSource = RunEnvironment.runSource(declaredRunSource);

        // then
        assertEquals(RunEnvironment.runSource(), runSource);
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
