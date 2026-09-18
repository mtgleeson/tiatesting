package org.tiatesting.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that {@link AgentOptions} carries the {@code forkPropertiesFile} option through the
 * command-line round-trip (serialize to string, parse back) alongside the existing options.
 */
class AgentOptionsTest {

    @Test
    void forkPropertiesFileSurvivesCommandLineRoundTrip() {
        // given
        AgentOptions options = new AgentOptions();
        options.setForkPropertiesFile("/build/tia/fork.properties");
        options.setSelectedTestsFile("/build/tia/selected-tests.txt");

        // when
        AgentOptions parsed = new AgentOptions(options.toCommandLineOptionsString());

        // then
        assertEquals("/build/tia/fork.properties", parsed.getForkPropertiesFile());
        assertEquals("/build/tia/selected-tests.txt", parsed.getSelectedTestsFile());
    }

    @Test
    void forkPropertiesFileDefaultsToEmptyWhenUnset() {
        // given
        AgentOptions options = new AgentOptions();

        // when / then
        assertEquals("", options.getForkPropertiesFile());
    }

    /**
     * Verifies {@code selectionDetailsFile} round-trips through the command-line serialize/parse
     * cycle the same way {@code forkPropertiesFile} does above, since the mojo writes it to the
     * command line and the agent parses it back out in the forked JVM.
     */
    @Test
    void selectionDetailsFileSurvivesCommandLineRoundTrip() {
        // given
        AgentOptions options = new AgentOptions();
        options.setSelectionDetailsFile("/build/tia/run-selection-details.txt");
        options.setSelectedTestsFile("/build/tia/selected-tests.txt");

        // when
        AgentOptions parsed = new AgentOptions(options.toCommandLineOptionsString());

        // then
        assertEquals("/build/tia/run-selection-details.txt", parsed.getSelectionDetailsFile());
        assertEquals("/build/tia/selected-tests.txt", parsed.getSelectedTestsFile());
    }

    /**
     * Verifies {@code getSelectionDetailsFile()} defaults to the empty string when the option was
     * never set, matching how the other optional sidecar-file options default.
     */
    @Test
    void selectionDetailsFileDefaultsToEmptyWhenUnset() {
        // given
        AgentOptions options = new AgentOptions();

        // when
        String selectionDetailsFile = options.getSelectionDetailsFile();

        // then
        assertEquals("", selectionDetailsFile);
    }
}
