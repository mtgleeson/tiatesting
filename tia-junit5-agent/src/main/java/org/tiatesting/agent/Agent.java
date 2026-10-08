package org.tiatesting.agent;

import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.core.agent.ForkSystemProperties;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Agent {

    /*
    java.util.logging rather than slf4j: the agent must start wherever the project's classes are not
    reachable from the system class loader (Surefire with useSystemClassLoader=false, for one), and
    a project need not have SLF4J at all. Anything the premain path loads must come from the JDK or
    this agent jar.
     */
    private static final Logger log = Logger.getLogger(Agent.class.getName());

    /*
    ByteBuddy's switch for reading class files newer than the Java versions it knows. Written with
    ByteBuddy's own package name: the build relocates ByteBuddy, and this string with it, so the
    property set is the one the agent's bundled copy reads.
     */
    static final String BYTE_BUDDY_EXPERIMENTAL = "net.bytebuddy.experimental";

    /**
     * Start the agent in the forked test JVM: publish the forwarded system properties, register the
     * {@code @Disabled} instrumentation for the suites Tia skips, and publish the hand-off file
     * paths and counts the Tia test listener reads.
     *
     * @param agentArgs the agent options, as {@link AgentOptions} renders them
     * @param instrumentation the JVM instrumentation handle
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        final AgentOptions agentOptions = new AgentOptions(agentArgs);
        applyForkSystemProperties(agentOptions.getForkPropertiesFile());
        enableByteBuddyExperimentalMode();
        instrumentIgnoredTests(instrumentation, agentOptions.getIgnoreTestsFile());
        setSelectedTestsSystemProperty(agentOptions.getSelectedTestsFile());
        setLibraryJarsSystemProperty(agentOptions.getLibraryJarsFile());
        setDrainResultFileSystemProperty(agentOptions.getDrainResultFile());
        setSelectionDetailsFileSystemProperty(agentOptions.getSelectionDetailsFile());
    }

    /**
     * Publish the system properties the build tool forwarded for the forked test JVM (database
     * connection, project dirs, update flags) from the fork properties file. Done first in
     * {@code premain} so the values are live before any Tia test listener constructs. Skips
     * silently when the option is unset.
     *
     * @param forkPropertiesFile path to the fork properties file written by the build plugin
     */
    private static void applyForkSystemProperties(String forkPropertiesFile) {
        try {
            ForkSystemProperties.applyToSystemProperties(forkPropertiesFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Let the bundled ByteBuddy read test classes compiled for a newer Java than it knows, unless the
     * user set the switch themselves. The agent only adds a class annotation, which does not depend
     * on understanding newer bytecode, and without it every class Tia meant to skip would run on a
     * newer Java. Must run before any ByteBuddy class is loaded: ByteBuddy reads it once.
     */
    static void enableByteBuddyExperimentalMode() {
        if (System.getProperty(BYTE_BUDDY_EXPERIMENTAL) == null) {
            System.setProperty(BYTE_BUDDY_EXPERIMENTAL, "true");
        }
    }

    /**
     * Read the ignore-tests file written by the select-tests step, apply the {@code @Disabled}
     * bytecode instrumentation to each entry, and publish the count as the
     * {@code tiaIgnoredTestSuiteCount} system property so the test listener can record it on the
     * history row. The count is the exact size of the set Tia chose to ignore - engine-level
     * skips (user {@code @Disabled}, surefire {@code groups} filters, etc.) do not contribute.
     *
     * @param instrumentation the JVM instrumentation handle from {@code premain}
     * @param ignoreTestsFile path to the newline-separated ignore-tests file written during selection
     */
    private static void instrumentIgnoredTests(Instrumentation instrumentation, String ignoreTestsFile) {
        Set<String> testsToIgnore;
        try (Stream<String> lines = Files.lines(Paths.get(ignoreTestsFile))) {
            testsToIgnore = lines.collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        new IgnoreTestInstrumentor().ignoreTests(testsToIgnore, instrumentation);
        String count = Integer.toString(testsToIgnore.size());
        log.log(Level.FINEST, "Setting system property for tiaIgnoredTestSuiteCount: {0}", count);
        System.setProperty("tiaIgnoredTestSuiteCount", count);
    }

    /**
     * Read the library JARs file (one absolute JAR path per line) and publish the joined CSV
     * as the {@code tiaLibraryJars} system property so {@code JacocoClient} picks it up in the
     * forked test JVM. Library Jars are used for Jacoco class loading to track coverage.
     * Skips silently when the option is unset.
     *
     * @param libraryJarsFile path to the library JARs file, or null/empty when none was written
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
            log.log(Level.FINEST, "Setting system property for tiaLibraryJars: {0}", csv);
            System.setProperty("tiaLibraryJars", csv);
        }
    }

    /**
     * Set the drain result file path as a system property so the test listener can deserialize
     * the drain result for post-test-run cleanup. Skips silently when the option is unset.
     *
     * @param drainResultFile path to the serialized drain result, or null/empty when none was written
     */
    private static void setDrainResultFileSystemProperty(String drainResultFile) {
        if (drainResultFile == null || drainResultFile.isEmpty()) {
            return;
        }
        log.log(Level.FINEST, "Setting system property for tiaDrainResultFile: {0}", drainResultFile);
        System.setProperty("tiaDrainResultFile", drainResultFile);
    }

    /**
     * Set the run-selection-details sidecar file path as a system property so the test listener
     * can deserialize the selection breakdown and attach it to the history row. The agent itself
     * does not parse the file - it stays dependency-light and leaves parsing to the listener, which
     * already depends on {@code tia-core}. Skips silently when the option is unset.
     *
     * @param selectionDetailsFile path to the run-selection-details sidecar file written by the
     *                             build plugin, or empty when no selection breakdown was written
     */
    private static void setSelectionDetailsFileSystemProperty(String selectionDetailsFile) {
        if (selectionDetailsFile == null || selectionDetailsFile.isEmpty()) {
            return;
        }
        log.log(Level.FINEST, "Setting system property for tiaRunSelectionDetailsFile: {0}", selectionDetailsFile);
        System.setProperty("tiaRunSelectionDetailsFile", selectionDetailsFile);
    }

    /**
     * Set the selected tests to run as a System property so it's available for the test runners.
     * The test runners should rely on the ignore tests to drive which tests to exclude.
     * But the test runner will need to know which existing tests Tia is aware of that it selected to run as part
     * of tracking previously failed tests that have now been ignored. Test suites can be filtered out by surefire
     * when using the 'groups' configuration.
     *
     * @param selectedTestsFile path to the newline-separated selected-tests file written during selection
     */
    private static void setSelectedTestsSystemProperty(String selectedTestsFile){
        Set<String> selectedTests;
        try (Stream<String> lines = Files.lines(Paths.get(selectedTestsFile))) {
            selectedTests = lines.collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String selectedTestsSystemProp = String.join(",", selectedTests);
        log.log(Level.FINEST, "Setting system property for tiaSelectedTests: {0}", selectedTestsSystemProp);
        System.setProperty("tiaSelectedTests", selectedTestsSystemProp);
    }

}