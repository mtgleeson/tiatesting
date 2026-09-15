package org.tiatesting.core.perf;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the {@link ProfileHtmlReport} argument parsing and timing aggregation logic.
 */
class ProfileHtmlReportTest {

    /**
     * Verify {@link ProfileHtmlReport.Args#parse} maps every supported key onto its field,
     * including the Gradle {@code out} to {@code outDb} rename.
     */
    @Test
    void parsePopulatesAllSupportedKeys() {
        // given
        String[] argv = {"out=/tmp/db", "branch=feature", "iterations=5",
                "url=jdbc:h2:tcp://localhost/x", "user=bob", "password=secret", "outDir=/tmp/report"};

        // when
        ProfileHtmlReport.Args parsed = ProfileHtmlReport.Args.parse(argv);

        // then
        assertEquals("/tmp/db", parsed.outDb);
        assertEquals("feature", parsed.branch);
        assertEquals(5, parsed.iterations);
        assertEquals("jdbc:h2:tcp://localhost/x", parsed.url);
        assertEquals("bob", parsed.user);
        assertEquals("secret", parsed.password);
        assertEquals("/tmp/report", parsed.outDir);
    }

    /**
     * Verify parsing an empty argument list leaves the documented defaults in place.
     */
    @Test
    void parseKeepsDefaultsWhenNoArgs() {
        // given
        String[] argv = {};

        // when
        ProfileHtmlReport.Args parsed = ProfileHtmlReport.Args.parse(argv);

        // then
        assertEquals("main", parsed.branch);
        assertEquals(1, parsed.iterations);
        assertNull(parsed.url);
    }

    /**
     * Verify an argument without an '=' separator is rejected.
     */
    @Test
    void parseRejectsMalformedArg() {
        // given
        String[] argv = {"branch"};

        // when / then
        assertThrows(IllegalArgumentException.class, () -> ProfileHtmlReport.Args.parse(argv));
    }

    /**
     * Verify an unknown key is rejected rather than silently ignored.
     */
    @Test
    void parseRejectsUnknownKey() {
        // given
        String[] argv = {"bogus=1"};

        // when / then
        assertThrows(IllegalArgumentException.class, () -> ProfileHtmlReport.Args.parse(argv));
    }

    /**
     * Verify {@link ProfileHtmlReport.Timings#averages} returns the mean of the recorded
     * durations per phase and preserves the order phases were first recorded in.
     */
    @Test
    void timingsAveragesMeanPerPhaseInInsertionOrder() {
        // given
        ProfileHtmlReport.Timings timings = new ProfileHtmlReport.Timings();
        timings.record("Phase A", 10);
        timings.record("Phase B", 20);
        timings.record("Phase A", 30);
        timings.record("Phase B", 40);

        // when
        Map<String, Double> averages = timings.averages();

        // then
        assertEquals(20.0, averages.get("Phase A"));
        assertEquals(30.0, averages.get("Phase B"));
        assertEquals("[Phase A, Phase B]", averages.keySet().toString());
    }
}
