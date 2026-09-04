package org.tiatesting.junit.junit4;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.distributed.DistributedForkProperties;
import org.tiatesting.core.distributed.DistributedRunnerContext;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.library.LibraryImpactDrainResultSerializer;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.testrunner.TestRunResult;
import org.tiatesting.core.agent.ForkSystemProperties;
import org.tiatesting.core.testrunner.TestRunnerService;
import org.tiatesting.core.vcs.VCSReader;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TiaJunit4Listener extends RunListener {

    private static final Logger log = LoggerFactory.getLogger(TiaJunit4Listener.class);

    private final TestRunnerService testRunnerService;
    private final JacocoClient coverageClient;
    private final String headCommit;
    private final Map<String, TestSuiteTracker> testSuiteTrackers;
    private final Map<Integer, MethodImpactTracker> testRunMethodsImpacted;
    private final Set<String> testSuitesFailed;
    private final String testClassesDirs;
    /*
    Track all the test suites that were executed by the test runner. This includes those that were skipped/ignored.
    Track how many times the test suite was executed (hit the finish hook, this doesn't happen for ignored tests).
     */
    private final Map<String, Integer> runnerTestSuites;
    /*
    The suites this JVM has actually observed - those it has seen finish (testSuiteFinished) or seen
    skipped (testIgnored) - with no test-classes directory-scan override, unlike
    getRunnerTestSuites(). This, not the (possibly overridden) discovered set, is what feeds the
    distributed completeness guard: see TestRunResult#getSuitesObserved. JUnit4 reuses this listener
    instance across Surefire retries, so a single field (rather than something shared like JUnit5's
    SharedTestRunData) is enough to accumulate across attempts.
     */
    private final Set<String> suitesObserved;
    /*
    The set of tests selected to run by Tia.
     */
    private Set<String> selectedTests;
    /*
    Count of test suites Tia chose to ignore for this run. Sourced from the
    `tiaIgnoredTestSuiteCount` system property set by the agent at premain time.
    Persisted as `tia_test_run_history.num_suites_ignored`.
     */
    private int ignoredTestSuiteCount;
    /*
    Per-attempt set of suite names that finished between testRunStarted and testRunFinished.
    JUnit4 reuses the same listener instance across Surefire retries, so we clear this set
    in testRunStarted to make each attempt's history "Ran" count reflect only that attempt -
    not the cumulative count carried across retries by testSuiteTrackers.
     */
    private final Set<String> suitesFinishedThisAttempt = ConcurrentHashMap.newKeySet();
    private final boolean enabled; // is the Tia Junit4Listener enabled for updating the DB?
    private final boolean updateDBMapping;
    private final boolean updateDBTestRunHistory;
    private final String branch;
    private long testRunStartTime;
    private final TestStats testRunStats = new TestStats();
    /*
    The distributed run this JVM is one runner of, or null for an ordinary single-host build.
    Resolved once here from the properties the build plugin forwarded through the fork properties
    file; JUnit4 reuses this listener instance across Surefire retries, so every attempt persists
    under the same run, runner identity and group the claim recorded.
     */
    private final DistributedRunnerContext distributedRunnerContext;

    /**
     * Build the listener for this test JVM: read the update flags and the selected/ignored suite
     * lists the agent published, open the datastore, and resolve whether this JVM is one runner of
     * a distributed build.
     *
     * <p>The distributed context is resolved here rather than at persist time because it is what
     * decides which persist flow runs. Resolving it to null when the build really is distributed
     * would put this runner on the single-host path, where it rebuilds the method catalogue from
     * an edge set holding only its own group's suites and stamps the commit - dropping every method
     * only the other groups reach, and silently under-selecting on the next build.
     *
     * @param vcsReader the VCS reader for the workspace under test; supplies the branch and head
     *                  commit, and is closed here
     */
    public TiaJunit4Listener(VCSReader vcsReader) {
        this.updateDBMapping = Boolean.parseBoolean(System.getProperty("tiaUpdateDBMapping"));
        // updateDBTestRunHistory defaults to TRUE - log unless explicitly switched off.
        this.updateDBTestRunHistory = !"false".equalsIgnoreCase(System.getProperty("tiaUpdateDBTestRunHistory"));
        this.enabled = isEnabled();
        this.coverageClient = new JacocoClient();

        if (enabled && updateDBMapping){
            this.coverageClient.initialize();
        }

        this.testSuiteTrackers = new ConcurrentHashMap<>();
        this.testSuitesFailed = ConcurrentHashMap.newKeySet();
        this.runnerTestSuites = new ConcurrentHashMap<>();
        this.suitesObserved = ConcurrentHashMap.newKeySet();
        this.testRunMethodsImpacted = new ConcurrentHashMap<>();
        this.headCommit = vcsReader.getHeadCommit();
        this.branch = vcsReader.getBranchName();
        DataStore dataStore = enabled ? DataStoreFactory.fromSystemProperties(this.branch) : null;
        this.testRunnerService = new TestRunnerService(dataStore);
        this.testClassesDirs = System.getProperty(ForkSystemProperties.PROP_TEST_CLASSES_DIRS);
        // Null for every ordinary build, whose persist is therefore exactly the one it always took.
        this.distributedRunnerContext = enabled
                ? DistributedForkProperties.contextFromSystemProperties() : null;
        vcsReader.close();
        setSelectedTests();
        setIgnoredTestSuiteCount();
    }

    /**
     * Read the count of test suites Tia chose to ignore for this run from the
     * {@code tiaIgnoredTestSuiteCount} system property set by the agent at premain time.
     * Defaults to {@code 0} when the property is unset, blank, or unparseable so the history
     * row records "0 ignored" rather than failing the persist.
     */
    private void setIgnoredTestSuiteCount(){
        String raw = System.getProperty("tiaIgnoredTestSuiteCount");
        if (raw == null || raw.trim().isEmpty()){
            this.ignoredTestSuiteCount = 0;
        } else {
            try {
                this.ignoredTestSuiteCount = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                log.warn("Could not parse tiaIgnoredTestSuiteCount '{}'; defaulting to 0", raw);
                this.ignoredTestSuiteCount = 0;
            }
        }
        log.trace("Reading system property tiaIgnoredTestSuiteCount: {}", ignoredTestSuiteCount);
    }

    /**
     * Enable the TiaJunit4Listener only if TIA is enabled and the test run is
     * marked to update the DB. i.e. only collect coverage metrics and update the
     * TIA DB when the test run is marked to update the DB (usually from CI).
     *
     * @return is Tia enabled
     */
    private boolean isEnabled(){
        boolean enabled = Boolean.parseBoolean(System.getProperty("tiaEnabled"));
        log.info("Tia Junit4Listener: enabled: {}, update mapping (and stats): {}, update test run history: {}",
                enabled, updateDBMapping, updateDBTestRunHistory);

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

        // Enable the listener if Tia is on AND at least one DB write is requested. The history
        // log defaults on, so this is almost always true when Tia is enabled.
        return enabled && (updateDBMapping || updateDBTestRunHistory);
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
     * This method gets called once for all tests. But on re-runs, sometime this won't get called again,
     * the retry of the failed tests will happen all within 1 run.
     * I've als seen retries of failed tests happen in separate runs.
     *
     * @param description The test run description.
     * @throws Exception The exception thrown by the test runner.
     */
    @Override
    public void testRunStarted(Description description) throws Exception {
        if (!enabled){
            return;
        }
        testRunStartTime = System.currentTimeMillis();
        // Reset the per-attempt suites-finished set. The listener instance is reused across
        // Surefire retries, so without this clear the history row's "Ran" count would
        // accumulate across attempts.
        suitesFinishedThisAttempt.clear();
    }

    @Override
    public void testSuiteStarted(Description description) throws Exception {
        if (!enabled){
            return;
        }

        if (isParameterizedTest(description)){
            // only do the test suite started logic for test suite files, not individual parameterized tests.
            // when surefire runs, before executing the test suites, testSuiteStarted and testSuiteFinished get fired
            // but with null description.getClass (and so isParameterizedTest() will be true). We don't want to execute
            // testSuiteStarted for this very first test run fire, or for true parameterized tests.
            return;
        }

        String testSuiteName = getTestSuiteName(description);
        TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(testSuiteName);

        // check if this is the first run for the test suite
        if (testSuiteTracker == null) {
            testSuiteTracker = new TestSuiteTracker(testSuiteName);
            this.testSuiteTrackers.put(testSuiteName, testSuiteTracker);
        }

        if (updateDBMapping){
            // assume the test suite will run and succeed. Explicitly set to 0 on failure, or no runs if ignored.
            testSuiteTracker.getTestStats().setNumSuccessRuns(1);

            if (shouldCalcTestSuiteAvgTime(description, testSuiteName)){
                // track the start of the test run, do it in the test suite object to keep the class thread safe
                testSuiteTracker.getTestStats().setAvgRunTime(System.currentTimeMillis());
            }
        }

        if (runnerTestSuites.containsKey(testSuiteName)){
            // If surefire is configured to re-run failed tests, it will use the same instance of this class and it
            // will execute new test runs. Each time a test suite is run, remove it from the failed list in case
            // it was previously run, failed, and is being re-run.
            this.testSuitesFailed.remove(testSuiteName);
        }
    }

    /**
     * Called when a test suite (class) or individual test is ignored/skipped. Records the suite as
     * observed by this JVM - even though it never ran - so a class-level {@code @Ignore} does not
     * make the distributed completeness guard wait for it forever.
     *
     * <p>Only a <b>class-level</b> skip counts as an observation, matching {@code
     * TiaTestExecutionListener.executionSkipped}, which gates on {@code isExecutionForTestSuite},
     * and {@code TiaSpockSkipExecutionListener}, which gates on a class-sourced container. A single
     * {@code @Ignore}d method inside a class that otherwise runs says nothing about whether this
     * JVM has finished with that class, and the observed set is the one signal the distributed
     * completeness guard reads. The two are told apart by the method name: JUnit 4 reports a
     * class-level skip with the class's own suite description, whose {@link
     * Description#getMethodName()} is null, and a method-level skip with a test description naming
     * the method. ({@link Description#isSuite()} is not usable here - it is false for both, since
     * an ignored class's description carries no children.)
     *
     * @param description the JUnit test description
     * @throws Exception an Exception
     */
    @Override
    public void testIgnored(Description description) throws Exception {
        if (!enabled){
            return;
        }

        String testSuiteName = getTestSuiteName(description);
        // track the test suite was run by the runner but not executed (0 executions)
        runnerTestSuites.put(testSuiteName, 0);
        if (description.getMethodName() == null){
            // this JVM has observed the whole suite (as skipped), independent of any test-classes directory
            // override applied by getRunnerTestSuites() - see the field's javadoc.
            suitesObserved.add(testSuiteName);
        }

        /*
        Note, we don't need to reset stats for Ignore:
        1. when the test suite is ignored, only this method is called, testsuitestarted is not called so no stats were created.
        2. when at least 1 test in the suite is ignored, this will get called, but we want to increment the run stats as Tia did not ignore the suite.
         */
    }

    @Override
    public void testFailure(Failure failure) {
        if (!enabled){
            return;
        }

        this.testSuitesFailed.add(getTestSuiteName(failure.getDescription()));
        updateTrackerStatsForFailedRun(getTestSuiteName(failure.getDescription()));
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        if (!enabled){
            return;
        }

        this.testSuitesFailed.add(getTestSuiteName(failure.getDescription()));
        updateTrackerStatsForFailedRun(getTestSuiteName(failure.getDescription()));
    }

    /**
     * Called when a test suite (class) has finished, whether the test suite succeeds or fails.
     * Track the list of methods called for the tests in the suite.
     *
     * @param description the Junit test description
     * @throws Exception an Exception
     */
    @Override
    public void testSuiteFinished(Description description) throws Exception {
        if (!enabled){
            return;
        }

        String testSuiteName = getTestSuiteName(description);
        TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(testSuiteName);

        if (updateDBMapping){
            testSuiteTracker.getTestStats().setNumRuns(1);

            if (shouldCalcTestSuiteAvgTime(description, testSuiteName)){
                testSuiteTracker.getTestStats().setAvgRunTime(calcTestSuiteRuntime(testSuiteTracker));
            }
        }

        if (updateDBMapping) {
            log.debug("Collecting coverage and adding the mapping for the test suite: " + testSuiteName);
            CoverageResult coverageResult = this.coverageClient.collectCoverage();
            List<ClassImpactTracker> classImpactTrackers = coverageResult.getClassesInvoked();
            addClassTrackersToTestSuiteTracker(testSuiteTracker, classImpactTrackers);
            testRunMethodsImpacted.putAll(coverageResult.getAllMethodsClassesInvoked());
        }

        // only track the test has run once all the individual param tests have completed and its executing testSuiteFinished for the overall test suite
        if (!isParameterizedTest(description)){
            int previousRuns = runnerTestSuites.get(testSuiteName) == null ? 0 : runnerTestSuites.get(testSuiteName);
            runnerTestSuites.put(testSuiteName, previousRuns+1);
            // this JVM has observed the suite (as finished), independent of any test-classes directory
            // override applied by getRunnerTestSuites() - see the field's javadoc.
            suitesObserved.add(testSuiteName);
            suitesFinishedThisAttempt.add(testSuiteName);
        }
    }

    /**
     * Called when all tests have finished.
     * This method gets called once for all tests. But on re-runs, sometime this won't get called again,
     * the retry of the failed tests will happen all within 1 run.
     * I've als seen retries of failed tests happen in separate runs.
     *
     * @param result the Junit runner result
     */
    @Override
    public void testRunFinished(Result result) {
        if (!enabled || (!updateDBMapping && !updateDBTestRunHistory)){
            // not enabled, or not updating the DB in any way
            return;
        }

        log.info("Test run finished. Persisting the DB.");
        Set<String> runnerTestSuites = getRunnerTestSuites();
        TestStats testStats = updateDBMapping ? getStatsForTestRun() : null;
        LibraryImpactDrainResult drainResult = LibraryImpactDrainResultSerializer.deserialize(
                System.getProperty("tiaDrainResultFile"));
        TestRunResult testRunResult = new TestRunResult(testSuiteTrackers, testSuitesFailed, runnerTestSuites,
                suitesObserved, selectedTests, testRunMethodsImpacted, testStats, drainResult,
                ignoredTestSuiteCount, suitesFinishedThisAttempt.size());
        // Null context on an ordinary build, which persists as a single host - suite mapping,
        // failed set, seal and history row. A distributed runner instead persists only its own
        // share and completes its group, and seals the build only if it turns out to be the last
        // runner to finish.
        testRunnerService.persistTestRunData(updateDBMapping, updateDBTestRunHistory,
                headCommit, branch, testRunStartTime, testRunResult, distributedRunnerContext);

        // If the tests are being re-run due to failure retry,reset stats (but not mappings) between re-runs.
        // We don't want to keep the stats from the first test run for the subsequent test runs.
        // But we do want to keep the mapping from previous runs as retry runs will only execute the failed tests
        // which is a subset of test and won't contain the mapping for the successful tests in the previous run.
        if (testRunStats.getNumRuns() > 0) {
            testSuiteTrackers.values().forEach(testSuiteTracker -> resetStatsForSubsequentRun(testSuiteTracker.getTestStats()));
        }
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
     * Also, for parameterized tests suites, only calculate the runtime for the overall test suite, not the individual param tests.
     *
     * @param testSuiteName The name of the test suite being executed
     * @param description the description of the test being executed
     * @return if we should calculate the average execution time for the test suite
     */
    private boolean shouldCalcTestSuiteAvgTime(Description description, String testSuiteName){
        boolean testSuiteFirstRun = !runnerTestSuites.containsKey(testSuiteName) || runnerTestSuites.get(testSuiteName) == 0;
        return testSuiteFirstRun && !isParameterizedTest(description);
    }

    /**
     * If the test suite is a parameterized test, we may already have a testSuiteTracker for the parent class
     * group the coverage for all parameterized tests into its parent class.
     *
     * @param testSuiteTracker the test suite being executed
     * @param classImpactTrackers the class trackers with coverage info for the test suite being executed
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

    private String getTestSuiteName(Description description){
        if (isParameterizedTest(description) && !description.getChildren().isEmpty()){
            //parameterized test, get the name of the class containing the test being executed rather than the generated parameter classes
            return description.getChildren().get(0).getClassName();
        }else {
            return description.getClassName();
        }
    }

    /**
     * Parameterized tests don't have a test class, it's null.
     * The overall test suite containing the param tests will have a test class.
     *
     * @param description the description of the test being executed
     * @return is the test being executed a parameterized test
     */
    private boolean isParameterizedTest(Description description){
        return description.getTestClass() == null;
    }

    /**
     * Get the list of test suites being executed by the test runner. But when "groups" is used in Surefire, it
     * filters the tests being executed and so out TestExecutionListener is not aware of the test suites filtered out.
     * So we can't rely on using TestExecutionListener to know the full list of tests.
     * <p>
     * There is an edge case where Surefire doesn't seem to pass the Ignored test classes when specifying the "groups"
     * param. In this case the junit listener only executes the unignored test suites and Tia will then think all the
     * ignored test classes were deleted and remove them from the DB.
     * <p>
     * i.e. testIgnored hooks are not being fired for Ignored classes when "groups" is specified for Surefire.
     * When groups is not specified and a test suite is Ignored, only the testIgnored() method is fired.
     * Seems like a bug with Surefire
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
        if (testClassesDirs == null || testClassesDirs.trim().isEmpty()){
            return runnerTestSuites.keySet();
        }

        return testRunnerService.getTestClassesFromDirs(testClassesDirs);
    }

    private void updateTrackerStatsForFailedRun(String testSuiteName) {
        if (updateDBMapping) {
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
}
