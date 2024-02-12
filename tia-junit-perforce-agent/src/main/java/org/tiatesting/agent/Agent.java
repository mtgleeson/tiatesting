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
        log.info("Ignoring test!!: {}", testsToIgnore);
        new IgnoreTestInstrumentor().ignoreTests(testsToIgnore, instrumentation, Ignore.class);
    }
}