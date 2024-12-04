package org.tiatesting.junit.junit5;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.testrunner.TestRunResult;
import org.tiatesting.core.testrunner.TestRunnerService;
import org.tiatesting.core.vcs.VCSReader;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Notes:
 * 1. unlike Junit4 where the same instance of RunListener is used for failed re-runs,
 * each re-run in Junit5 will create a new instance of this class.
 * <p>
 * 2. testPlanExecutionStarted and testPlanExecutionFinished are always called from the same thread and it's safe to
 * assume there's at most one TestPlan at a time. All other methods could be called from different threads concurrently
 * in case one or multiple test engines execute tests in parallel.
 **/
public class TiaTestExecutionListener implements TestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(TiaTestExecutionListener.class);

    private final TestRunnerService testRunnerService;
    private final JacocoClient coverageClient;
    private final String headCommit;
    private final Map<String, TestSuiteTracker> testSuiteTrackers;
    private final Map<Integer, MethodImpactTracker> testRunMethodsImpacted;
    private final Set<String> testSuitesFailed;
    private final String testClassesDir;
    /*
    Track all the test suites that were executed by the test runner. This includes those that were skipped/ignored.
     */
    private final Set<String> runnerTestSuites;
    /*
    The set of tests selected to run by Tia.
     */
    private Set<String> selectedTests;
    private final boolean enabled; // is the Tia Junit4Listener enabled for updating the DB?
    private final boolean updateDBMapping;
    private final boolean updateDBStats;
    private long testRunStartTime;
    private final TestStats testRunStats;

    public TiaTestExecutionListener(final SharedTestRunData sharedTestRunData, VCSReader vcsReader) {
        this.updateDBMapping = Boolean.parseBoolean(System.getProperty("tiaUpdateDBMapping"));
        this.updateDBStats = Boolean.parseBoolean(System.getProperty("tiaUpdateDBStats"));
        this.enabled = isEnabled();
        this.coverageClient = new JacocoClient();

        if (enabled && updateDBMapping){
            this.coverageClient.initialize();
        }

        this.testSuiteTrackers = sharedTestRunData.getTestSuiteTrackers();
        this.testSuitesFailed = ConcurrentHashMap.newKeySet();
        this.runnerTestSuites = sharedTestRunData.getRunnerTestSuites();
        this.testRunStats = sharedTestRunData.getTestRunStats();
        this.testRunMethodsImpacted = new ConcurrentHashMap<>();
        this.headCommit = vcsReader.getHeadCommit();
        DataStore dataStore = enabled ? new H2DataStore(System.getProperty("tiaDBFilePath"), vcsReader.getBranchName()) : null;
        this.testRunnerService = new TestRunnerService(dataStore);
        this.testClassesDir = System.getProperty("testClassesDir");
        vcsReader.close();
        setSelectedTests();
    }

    /**
     * Enable the TiaTestExecutionListener only if TIA is enabled and the test run is
     * marked to update the DB. i.e. only collect coverage metrics and update the
     * TIA DB when the test run is marked to update the DB (usually from CI).
     *
     * @return is Tia enabled
     */
    private boolean isEnabled(){
        boolean enabled = Boolean.parseBoolean(System.getProperty("tiaEnabled"));
        log.info("Tia TestExecutionListener: enabled: {}, update mapping: {}, update stats: {}",
                enabled, updateDBMapping, updateDBStats);

        /*
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

        // only enable the Tia JUnit runner is TIa is enabled and we're updating the mapping and/or updating the stats
        return enabled && (updateDBMapping || updateDBStats);
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

    /**
     * This is executed only once for all tests in the session/run/test plan.
     * For re-runs, this will be run again with the new TestExecutionListener instance.
     *
     * @param testPlan The test plan being executed.
     */
    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        if (!enabled){
            return;
        }
        testRunStartTime = System.currentTimeMillis();

        // If the tests are being re-run due to failure retry,reset stats (but not mappings) between re-runs.
        // We don't want to keep the stats from the first test run for the subsequent test runs.
        // But we do want to keep the mapping from previous runs as retry runs will only execute the failed tests
        // which is a subset of test and won't contain the mapping for the successful tests in the previous run.
        if (testRunStats.getNumRuns() > 0) {
            testSuiteTrackers.values().forEach(testSuiteTracker -> resetStatsForSubsequentRun(testSuiteTracker.getTestStats()));
        }
    }

    /**
     * This is executed when the test engine starts, when the test suite is initialized, and when individual tests
     * are executed.
     * This can be called concurrently if tests are being executed concurrently.
     *
     * @param testIdentifier The identifier for the item being executed.
     */
    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (!enabled){
            return;
        }

        if (isExecutionForTestSuite(testIdentifier)){
            testSuiteStarted(testIdentifier);
        }
    }

    private void testSuiteStarted(TestIdentifier testIdentifier){
        if (!enabled){
            return;
        }

        String testSuiteName = getTestSuiteName(testIdentifier);
        TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(testSuiteName);

        // check if this is the first run for the test suite
        if (testSuiteTracker == null){
            testSuiteTracker = new TestSuiteTracker(testSuiteName);
            this.testSuiteTrackers.put(testSuiteName, testSuiteTracker);
        }

        if (updateDBStats){
            // assume the test suite will run and succeed. Explicitly set to false on failure, or no runs if ignored.
            testSuiteTracker.getTestStats().setNumSuccessRuns(1);

            if (shouldCalcTestSuiteAvgTime(testSuiteName)){
                // track the start of the test run, do it in the test suite object to keep the class thread safe
                testSuiteTracker.getTestStats().setAvgRunTime(System.currentTimeMillis());
            }
        }
    }

    /**
     * This is executed when a test suite, or individual test is disabled/skipped.
     * This can be called concurrently if tests are being executed concurrently.
     *
     * @param testIdentifier The identifier for the item being executed.
     */
    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (!enabled){
            return;
        }

        // when the test suite is ignored, only this method is called, executionStarted/Finished is not called. So we need
        // to track testSuiteName in runnerTestSuite here.
        if (isExecutionForTestSuite(testIdentifier)){
            // track the test suite was run by the runner but not executed (0 executions)
            String testSuiteName = getTestSuiteName(testIdentifier);
            runnerTestSuites.add(testSuiteName);
        }

        /*
            Note, we don't need to reset stats for Ignore:
            When at least 1 test in the suite is ignored, this will get called, but we want to increment the run stats as Tia did not ignore the suite.
         */
    }

    /**
     * This is executed when the test engine has finished executing all tests, when all the tests in the test suite have
     * been executed, and when individual tests execution is complete.
     * This can be called concurrently if tests are being executed concurrently.
     *
     * @param testIdentifier The identifier for the item being executed.
     */
    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!enabled){
            return;
        }

        if (isExecutionForTest(testIdentifier)){
            if (testExecutionResult.getStatus() != TestExecutionResult.Status.SUCCESSFUL){
                testFailure(testIdentifier);
            }
        } else if (isExecutionForTestSuite(testIdentifier)) {
            testSuiteFinished(testIdentifier);
        }
    }

    private void testFailure(TestIdentifier testIdentifier) {
        String testSuiteName = getTestSuiteName(testIdentifier);
        this.testSuitesFailed.add(testSuiteName);
        updateTrackerStatsForFailedRun(testSuiteName);
    }

    private void testSuiteFinished(TestIdentifier testIdentifier) {
        String testSuiteName = getTestSuiteName(testIdentifier);
        TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(testSuiteName);

        if (updateDBStats){
            testSuiteTracker.getTestStats().setNumRuns(1);

            if (shouldCalcTestSuiteAvgTime(testSuiteName)){
                testSuiteTracker.getTestStats().setAvgRunTime(calcTestSuiteRuntime(testSuiteTracker));
            }
        }

        if (updateDBMapping) {
            log.debug("Collecting coverage and adding the mapping for the test suite: " + testSuiteName);
            CoverageResult coverageResult = null;
            try {
                coverageResult = this.coverageClient.collectCoverage();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            List<ClassImpactTracker> classImpactTrackers = coverageResult.getClassesInvoked();
            addClassTrackersToTestSuiteTracker(testSuiteTracker, classImpactTrackers);
            testRunMethodsImpacted.putAll(coverageResult.getAllMethodsClassesInvoked());
        }

        runnerTestSuites.add(testSuiteName);
    }

    /**
     * This is executed only once for all tests in the session/run/test plan.
     * For re-runs, this will be run again with the new TestExecutionListener instance.
     *
     * @param testPlan The test plan being executed.
     */
    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (!enabled || (!updateDBMapping && !updateDBStats)){
            // not enabled, or not updating the DB in any way
            return;
        }

        log.info("Test run finished. Persisting the DB.");
        Set<String> runnerTestSuites = getRunnerTestSuites();
        TestStats testStats = updateDBStats ? getStatsForTestRun() : null;
        TestRunResult testRunResult = new TestRunResult(testSuiteTrackers, testSuitesFailed, runnerTestSuites,
                selectedTests, testRunMethodsImpacted, testStats);
        testRunnerService.persistTestRunData(updateDBMapping, updateDBStats, headCommit, testRunResult);
    }

    private TestStats getStatsForTestRun(){
        if (testRunStats.getNumRuns() > 0){
            // Don't increment the stats for this test run if we have already done so.
            // This happens when a failed test is configured by Surefire etc to be re-run.
            // For the re-runs, we don't count those in the stats as we only want to track
            // how many times Tia was used for selecting tests.
            return new TestStats();
        }

        testRunStats.setNumRuns(1);
        testRunStats.setAvgRunTime(System.currentTimeMillis() - this.testRunStartTime);

        // check if all the test suites succeeded
        int numTestSuitesRun = testSuiteTrackers.keySet().size();
        int numTestSuitesSucceeded = testSuiteTrackers.values().stream()
                .reduce(0, (partialSum, element) ->
                        partialSum + (element.getTestStats().getNumSuccessRuns() > 0 ? 1: 0), Integer::sum);
        boolean allTestsSucceeded = numTestSuitesRun == numTestSuitesSucceeded;

        testRunStats.setNumSuccessRuns(allTestsSucceeded ? 1: 0);
        testRunStats.setNumFailRuns((allTestsSucceeded ? 0 : 1));

        return testRunStats;
    }

    private long calcTestSuiteRuntime(TestSuiteTracker testSuiteTracker) {
        return System.currentTimeMillis() - testSuiteTracker.getTestStats().getAvgRunTime();
    }

    /**
     * Only calc the test suite run time for the first attempt. If a test fails, it might be configured to re-run at which
     * point it only runs the individual tests that failed (not the whole test suite) so the run time is a lot smaller.
     * We want the run time for the whole test suite - the first attempt.
     *
     * @param testSuiteName The name of the test suite being executed
     * @return if we should calculate the average execution time for the test suite
     */
    private boolean shouldCalcTestSuiteAvgTime(String testSuiteName){
        return !runnerTestSuites.contains(testSuiteName);
    }

    /**
     * Add all the source classes with coverage to the tracker for the test suite.
     * We need to merge the tracking coverage to account for failed test re-runs.
     * i.e. the re-run test plan mappings will be only relevant for the re-run test method(s) which are a subset and
     * won't account for the other test methods in the suite.
     *
     * @param testSuiteTracker the test suite being executed
     * @param classImpactTrackers the class trackers with coverage info for the test suite being executed
     */
    private void addClassTrackersToTestSuiteTracker(TestSuiteTracker testSuiteTracker,
                                                    List<ClassImpactTracker> classImpactTrackers) {
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

    private String getTestSuiteName(TestIdentifier testIdentifier){
        if (isExecutionForTestSuite(testIdentifier)){
            return ((ClassSource) testIdentifier.getSource().get()).getClassName();
        } else if (isExecutionForTest(testIdentifier)){
            return ((MethodSource) testIdentifier.getSource().get()).getClassName();
        }
        return null;
    }

    /**
     * Get the list of test suites being executed by the test runner. But when "groups" is used in Surefire, it
     * filters the tests being executed and so out TestExecutionListener is not aware of the test suites filtered out.
     * So we can't rely on using TestExecutionListener to know the full list of tests.
     * <p>
     * To work around this, the user can specify the test-classes file system path and we can read all the classes and use
     * that as the list of known test classes valid for the test run. i.e. if a test class exists, it hasn't been
     * deleted and so we treat it as still value (even if it's disabled or filtered out via 'groups').
     * This was we don't delete the tests that are being filtered from Tia. We only delete from Tia when the test class
     * has been removed.
     *
     * @return the set of all test suite names for the project that Tia is aware of.
     */
    private Set<String> getRunnerTestSuites(){
        if (testClassesDir == null){
            return runnerTestSuites;
        }

        return testRunnerService.getTestClassesFromDir(testClassesDir);
    }

    private void updateTrackerStatsForFailedRun(String testSuiteName) {
        if (updateDBStats) {
            TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(testSuiteName);
            testSuiteTracker.getTestStats().setNumSuccessRuns(0);
            testSuiteTracker.getTestStats().setNumFailRuns(1);
        }
    }

    /**
     * Clear the stats for the test suite when being re-run due to a failed retry.
     * Don't reset the average time, we keep that from the initial run as the initial run covers all tests.
     * Re-run times will be shorter due to only running failed tests. We don't want to use this for the avg time for
     * the suite.
     *
     * @param testStats The stats for a test suite.
     */
    private void resetStatsForSubsequentRun(TestStats testStats) {
        testStats.setNumRuns(0);
        testStats.setNumSuccessRuns(0);
        testStats.setNumFailRuns(0);
    }

    private boolean isExecutionForTestSuite(TestIdentifier testIdentifier) {
        return testIdentifier.isContainer() && testIdentifier.getSource().isPresent()
                && testIdentifier.getSource().get() instanceof ClassSource;
    }

    private boolean isExecutionForTest(TestIdentifier testIdentifier) {
        return testIdentifier.isTest();
    }

}
