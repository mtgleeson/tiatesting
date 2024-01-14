package org.tiatesting.junit.junit4;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
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
import java.util.HashSet;
import java.util.List;
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
    /*
    The set of tests selected to run by Tia.
     */
    private Set<String> selectedTests;
    private final boolean enabled; // is the Tia Junit4Listener enabled for updating the test mapping?

    private long testRunStartTime;

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
        setSelectedTests();
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

    public void testRunStarted(Description description) throws Exception {
        testRunStartTime = System.currentTimeMillis();
    }

    public void testSuiteStarted(Description description) throws Exception {
        if (!enabled){
            return;
        }

        String testSuiteName = getTestSuiteName(description);
        runnerTestSuites.add(testSuiteName);

        TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(testSuiteName);
        if (testSuiteTracker == null){
            testSuiteTracker = new TestSuiteTracker(testSuiteName);
            testSuiteTracker.setSourceFilename(testSuiteName.replaceAll("\\.", "/"));
            testSuiteTracker.setNumRuns(1); // assume it will run. Explicitly set to 0 if ignored.
            testSuiteTracker.setNumSuccessRuns(1); // assume it will succeed. Explicitly set to false on failure.
            testSuiteTracker.setTotalRunTime(System.currentTimeMillis()); // track the start of the test run, do it in this object to keep the class thread safe
            this.testSuiteTrackers.put(testSuiteName, testSuiteTracker);
        }
    }

    public void testIgnored(Description description) throws Exception {
        if (!enabled){
            return;
        }
        // reset the stats - the tests wasn't run
        TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(getTestSuiteName(description));
        testSuiteTracker.setNumRuns(0);
        testSuiteTracker.setNumSuccessRuns(0);
        testSuiteTracker.setNumFailRuns(0);

        // track the test was run by the runner (even though it was ignored)
        runnerTestSuites.add(getTestSuiteName(description));
    }

    @Override
    public void testFailure(Failure failure) {
        if (!enabled){
            return;
        }
        
        this.testSuitesFailed.add(getTestSuiteName(failure.getDescription()));
        updateTrackerForFailedRun(getTestSuiteName(failure.getDescription()));
    }

    private void updateTrackerForFailedRun(String testSuiteName) {
        // reset the stats - the tests wasn't run
        TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(testSuiteName);
        testSuiteTracker.setNumSuccessRuns(0);
        testSuiteTracker.setNumFailRuns(1);
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        if (!enabled){
            return;
        }

        this.testSuitesFailed.add(getTestSuiteName(failure.getDescription()));
        updateTrackerForFailedRun(getTestSuiteName(failure.getDescription()));
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
        TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(testSuiteName);
        testSuiteTracker.setTotalRunTime(calcTestSuiteRuntime(testSuiteTracker));

        log.debug("Collecting coverage and adding the mapping for the test suite: " + testSuiteName);
        CoverageResult coverageResult = this.coverageClient.collectCoverage();
        List<ClassImpactTracker> classImpactTrackers = coverageResult.getClassesInvoked();
        addClassTrackersToTestSuiteTracker(testSuiteTracker, classImpactTrackers);

        testRunMethodsImpacted.putAll(coverageResult.getAllMethodsClassesInvoked());
    }

    private long calcTestSuiteRuntime(TestSuiteTracker testSuiteTracker) {
        long totalRunTime = System.currentTimeMillis() - testSuiteTracker.getTotalRunTime();
        totalRunTime = totalRunTime > 1000 ? (totalRunTime / 1000) : 1;
        return totalRunTime;
    }

    /**
     * If the test suite is a parameterized test, we may already have a testSuiteTracker for the parent class
     * group the coverage for all parameterized tests into its parent class.
     *
     * @param testSuiteTracker
     * @param classImpactTrackers
     */
    private static void addClassTrackersToTestSuiteTracker(TestSuiteTracker testSuiteTracker,
                                                           List<ClassImpactTracker> classImpactTrackers) {
        // merge parameterized class coverage
        for (ClassImpactTracker newClassImpactTracker : classImpactTrackers){
            // check if the class is already tracked for the test suite
            boolean classTrackerAdded = false;
            for (ClassImpactTracker classImpactTracker : testSuiteTracker.getClassesImpacted()){
                if (classImpactTracker.getSourceFilename().equals(newClassImpactTracker.getSourceFilename())){
                    classImpactTracker.getMethodsImpacted().addAll(newClassImpactTracker.getMethodsImpacted());
                    classTrackerAdded = true;
                    break;
                }
            }

            if (!classTrackerAdded){
                testSuiteTracker.getClassesImpacted().add(newClassImpactTracker);
            }
        }
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
        long totalRunTime = System.currentTimeMillis() - this.testRunStartTime;
        this.dataStore.persistTestMapping(testSuiteTrackers, testSuitesFailed, runnerTestSuites, selectedTests,
                testRunMethodsImpacted, vcsReader.getHeadCommit(), totalRunTime);
    }

    private String getTestSuiteName(Description description){
        if (description.getTestClass() == null && !description.getChildren().isEmpty()){
            //parameterized test, get the name of the class containing the test being executed rather than the generated parameter classes
            return description.getChildren().get(0).getClassName();
        }else {
            return description.getClassName();
        }
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

    private void setSelectedTests(){
        String selectedTestsStr = System.getProperty("tiaSelectedTests");
        if (selectedTestsStr != null && !selectedTestsStr.trim().isEmpty()){
            this.selectedTests = Stream.of(selectedTestsStr.split(",")).collect(Collectors.toSet());
        }else{
            this.selectedTests = new HashSet<>();
        }
        log.trace("Reading system property tiaSelectedTests: {}", selectedTests);
    }
}
