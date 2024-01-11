package org.tiatesting.junit.junit4;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.coverage.TestSuiteTracker;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.sourcefile.FileExtensions;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TiaTestingJunit4Listener extends RunListener {

    private static final Logger log = LoggerFactory.getLogger(TiaTestingJunit4Listener.class);

    private final JacocoClient coverageClient;
    private final DataStore dataStore;
    private final VCSReader vcsReader;
    private final Map<String, TestSuiteTracker> testSuiteTrackers;
    private final Map<Integer, MethodImpactTracker> testRunMethodsImpacted;
    private Set<String> testSuitesFailed;
    private final String testClassesDir;
    /*
    Track all the test suites that were executed by the test runner. This includes those that were skipped/ignored.
     */
    private Set<String> runnerTestSuites;
    private final boolean enabled; // is the Tia Junit4Listener enabled for updating the test mapping?

    public TiaTestingJunit4Listener(VCSReader vcsReader) {
        this.enabled = isEnabled();
        this.coverageClient = new JacocoClient();

        if (enabled){
            this.coverageClient.initialize();
        }

        this.testSuiteTrackers = new ConcurrentHashMap<>();
        this.testSuitesFailed = ConcurrentHashMap.newKeySet();
        this.runnerTestSuites = ConcurrentHashMap.newKeySet();
        this.testRunMethodsImpacted = new ConcurrentHashMap<>();
        this.vcsReader = vcsReader;
        this.dataStore = enabled ? new MapDataStore(System.getProperty("tiaDBFilePath"), vcsReader.getBranchName()) : null;
        this.testClassesDir = System.getProperty("testClassesDir");
    }

    /**
     * Enable the TiaTestingJunit4Listener only if TIA is enabled and the test run is
     * marked to update the DB. i.e. only collect coverage metrics and update the
     * TIA DB when the test run is marked to update the DB (usually from CI).
     *
     * @return
     */
    private boolean isEnabled(){
        boolean enabled = Boolean.parseBoolean(System.getProperty("tiaEnabled"));
        boolean updateDB = Boolean.parseBoolean(System.getProperty("tiaUpdateDB"));
        log.info("Tia Junit4Listener: enabled: {}, update DB: {}", enabled, updateDB);

        /**
         * If the user specified specific individual tests to run, disable Tia so we don't try to update the test mapping.
         */
        if (enabled){
            String userSpecifiedTests = System.getProperty("test");
            boolean hasUserSpecifiedTests = userSpecifiedTests != null && !userSpecifiedTests.isEmpty();
            if (hasUserSpecifiedTests){
                log.info("User has specified tests, disabling Tia");
                enabled = false;
            }
        }

        return enabled && updateDB;
    }

    public void testSuiteStarted(Description description) throws Exception {
        runnerTestSuites.add(getTestSuiteName(description));
    }

    public void testIgnored(Description description) throws Exception {
        runnerTestSuites.add(getTestSuiteName(description));
    }

    @Override
    public void testFailure(Failure failure) {
        this.testSuitesFailed.add(getTestSuiteName(failure.getDescription()));
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        this.testSuitesFailed.add(getTestSuiteName(failure.getDescription()));
    }

    /**
     * Called when a test suite (class) has finished, whether the test suite succeeds or fails.
     * Track the list of methods called for the tests in the suite (only if all the tests in the suite were successful).
     *
     * @param description
     * @throws Exception
     */
    @Override
    public void testSuiteFinished(Description description) throws Exception {
        if (!enabled){
            return;
        }

        String testSuiteName = getTestSuiteName(description);
        log.debug("Collecting coverage and adding the mapping for the test suite: " + testSuiteName);
        TestSuiteTracker testSuiteTracker = new TestSuiteTracker(testSuiteName);
        testSuiteTracker.setSourceFilename(testSuiteName.replaceAll("\\.", "/"));
        CoverageResult coverageResult = this.coverageClient.collectCoverage();
        testSuiteTracker.setClassesImpacted(coverageResult.getClassesInvoked());
        this.testSuiteTrackers.put(testSuiteName, testSuiteTracker);
        testRunMethodsImpacted.putAll(coverageResult.getAllMethodsClassesInvoked());
    }

    /**
     * Called when all tests have finished.
     *
     * @param result
     * @throws Exception
     */
    @Override
    public void testRunFinished(Result result) {
        if (!enabled){
            return;
        }

        log.info("Test run finished. Persisting the test mapping.");
        runnerTestSuites = getRunnerTestSuites();
        this.dataStore.persistTestMapping(testSuiteTrackers, testSuitesFailed, runnerTestSuites, testRunMethodsImpacted,
                vcsReader.getHeadCommit());
    }

    private String getTestSuiteName(Description description){
        return description.getClassName();
    }

    /**
     * Get the list of test suites being executed by the test runner. Normally we can rely on the test runner to be passed
     * the full list of test suites to be executed (including ignored test suites).
     *
     * There is an edge case where Surefire doesn't seem to pass the Ignored test classes when specifying the "groups"
     * param. In this case the junit listener only executes the unignored test suites and Tia will then think all the
     * ignored test classes were deleted and remove them from the DB.
     *
     * i.e. testSuiteStarted and testIgnored hooks are not being fired for Ignored tests when "groups" is specified.
     * Seems like a bug with Surefire.
     *
     * To work around this, the user can specify the test-classes file system path and we can read all the classes and use
     * that as the list of known test classes valid for the test run.
     *
     * @return
     */
    private Set<String> getRunnerTestSuites(){
        if (testClassesDir == null){
            return runnerTestSuites;
        }

        Path path = Paths.get(testClassesDir);
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Test classes path must be a directory - " + testClassesDir);
        }

        Set<String> testClasses;
        String classFileExt = "." + FileExtensions.CLASS_FILE_EXT;

        try (Stream<Path> walk = Files.walk(path)) {
            testClasses = walk
                    .filter(p -> !Files.isDirectory(p))
                    // convert from the full file system path for the class files into the class name
                    .map(p -> p.toString())
                    .filter(f -> f.toLowerCase().endsWith(classFileExt))
                    .map(p ->
                            p.replace(testClassesDir, "")
                                    .replace(classFileExt, "")
                                    .replace(File.separator, ".")
                                    .substring((p.startsWith(File.separator) ? 1 : 0))
                    )
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.trace("Test classes found: " + testClasses);
        return testClasses;
    }
}
