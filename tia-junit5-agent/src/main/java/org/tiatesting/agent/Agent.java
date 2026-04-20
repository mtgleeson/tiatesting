package org.tiatesting.agent;

import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.core.agent.instrumentation.IgnoreTestInstrumentor;

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
        instrumentIgnoredTests(instrumentation, agentOptions.getIgnoreTestsFile());
        setSelectedTestsSystemProperty(agentOptions.getSelectedTestsFile());
        setLibraryJarsSystemProperty(agentOptions.getLibraryJarsFile());
        setDrainResultFileSystemProperty(agentOptions.getDrainResultFile());
    }

    private static void instrumentIgnoredTests(Instrumentation instrumentation, String ignoreTestsFile) {
        Set<String> testsToIgnore;
        try (Stream<String> lines = Files.lines(Paths.get(ignoreTestsFile))) {
            testsToIgnore = lines.collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        new IgnoreTestInstrumentor().ignoreTests(testsToIgnore, instrumentation, Disabled.class);
    }

    /**
     * Read the library JARs file (one absolute JAR path per line) and publish the joined CSV
     * as the {@code tiaLibraryJars} system property so {@code JacocoClient} picks it up in the
     * forked test JVM. Library Jars are used for Jacoco class loading to track coverage.
     * Skips silently when the option is unset.
     */
    private static void setLibraryJarsSystemProperty(String libraryJarsFile){
        if (libraryJarsFile == null || libraryJarsFile.isEmpty()){
            return;
        }
        String csv;

        try (Stream<String> lines = Files.lines(Paths.get(libraryJarsFile))) {
            csv = lines.filter(l -> !l.isEmpty()).collect(Collectors.joining(","));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (!csv.isEmpty()){
            log.trace("Setting system property for tiaLibraryJars: {}", csv);
            System.setProperty("tiaLibraryJars", csv);
        }
    }

    /**
     * Set the drain result file path as a system property so the test listener can deserialize
     * the drain result for post-test-run cleanup. Skips silently when the option is unset.
     */
    private static void setDrainResultFileSystemProperty(String drainResultFile) {
        if (drainResultFile == null || drainResultFile.isEmpty()) {
            return;
        }
        log.trace("Setting system property for tiaDrainResultFile: {}", drainResultFile);
        System.setProperty("tiaDrainResultFile", drainResultFile);
    }

    /**
     * Set the selected tests to run as a System property so it's available for the test runners.
     * The test runners should rely on the ignore tests to drive which tests to exclude.
     * But the test runner will need to know which existing tests Tia is aware of that it selected to run as part
     * of tracking previously failed tests that have now been ignored. Test suites can be filtered out by surefire
     * when using the 'groups' configuration.
     *
     * @param selectedTestsFile
     */
    private static void setSelectedTestsSystemProperty(String selectedTestsFile){
        Set<String> selectedTests;
        try (Stream<String> lines = Files.lines(Paths.get(selectedTestsFile))) {
            selectedTests = lines.collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String selectedTestsSystemProp = String.join(",", selectedTests);
        log.trace("Setting system property for tiaSelectedTests: {}", selectedTestsSystemProp);
        System.setProperty("tiaSelectedTests", selectedTestsSystemProp);
    }

}