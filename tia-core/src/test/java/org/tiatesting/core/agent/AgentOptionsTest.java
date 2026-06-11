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
}
