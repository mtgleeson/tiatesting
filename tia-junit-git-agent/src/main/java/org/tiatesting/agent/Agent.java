package org.tiatesting.agent;

import org.junit.Ignore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.agent.instrumentation.IgnoreTestInstrumentor;
import org.tiatesting.core.agent.AgentOptions;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        final AgentOptions agentOptions = new AgentOptions(agentArgs);

        Set<String> testsToIgnore;
        try (Stream<String> lines = Files.lines(Paths.get(agentOptions.getIgnoreTestsFile()))) {
            testsToIgnore = lines.collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        log.info("Ignoring tests: {}", testsToIgnore);
        new IgnoreTestInstrumentor().ignoreTests(testsToIgnore, instrumentation, Ignore.class);

        Set<String> selectedTests;
        try (Stream<String> lines = Files.lines(Paths.get(agentOptions.getSelectedTestsFile()))) {
            selectedTests = lines.collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        setSelectedTestsSystemProperty(selectedTests);
    }

    /**
     * Set the selected tests to run as a System property so it's available for the test runners.
     * The test runners should rely on the ignore tests to drive which tests to exclude.
     * But the test runner will need to know which existing tests Tia is aware of that it selected to run as part
     * of tracking previously failed tests that have now been ignored. We can't always rely on the test runner to fire
     * for ignored tests (there's an issue with using the Surefire "groups" property not firing Ignored tests with junit).
     *
     * @param testsToRun
     */
    private static void setSelectedTestsSystemProperty(Set<String> testsToRun){
        String selectedTestsSystemProp = String.join(",", testsToRun);
        log.trace("Setting system property for tiaSelectedTests: {}", selectedTestsSystemProp);
        System.setProperty("tiaSelectedTests", selectedTestsSystemProp);
    }

}