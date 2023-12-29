package org.tiatesting.junit.junit4;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.coverage.TestSuiteTracker;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;
import org.tiatesting.report.ReportGenerator;
import org.tiatesting.report.TextFileReportGenerator;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TiaTestingJunit4Listener extends RunListener {

    private static final Logger log = LoggerFactory.getLogger(TiaTestingJunit4Listener.class);

    private final JacocoClient coverageClient;
    private final DataStore dataStore;
    private final VCSReader vcsReader;
    private final Map<String, TestSuiteTracker> testSuiteTrackers;
    private final Map<Integer, MethodImpactTracker> testRunMethodsImpacted;
    private Set<String> testSuitesFailed;
    /*
    Track all the test suites that were executed by the test runner. This includes those that were skipped/ignored.
     */
    private Set<String> runnerTestSuites;
    private final boolean enabled; // is TIA enabled?

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
        this.vcsReader = vcsReader; //new GitReader(System.getProperty("tiaProjectDir"));
        this.dataStore = new MapDataStore(System.getProperty("tiaDBFilePath"), vcsReader.getBranchName());
    }

    /**
     * Enable the TiaTestingJunit4Listener only if TIA is enabled and the test run is
     * marked to update the DB. i.e. only collect coverage metrics and update the
     * TIA DB when the test run is marked to update the DB (usually from CI).
     *
     * @return
     */
    private boolean isEnabled(){
        // TODO test this!
        boolean enabled = Boolean.parseBoolean(System.getProperty("tiaEnabled"));
        boolean updateDB = Boolean.parseBoolean(System.getProperty("tiaUpdateDB"));
        log.info("tiaEnabled: " + enabled);
        log.info("tiaUpdateDB: " + updateDB);
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
        this.dataStore.persistTestMapping(testSuiteTrackers, testSuitesFailed, runnerTestSuites, testRunMethodsImpacted,
                vcsReader.getHeadCommit());

        // TODO temp. Create a new maven/gradle task/mojo that generates the file
        ReportGenerator reportGenerator = new TextFileReportGenerator(this.vcsReader.getBranchName());
        reportGenerator.generateReport(this.dataStore);
    }

    private String getTestSuiteName(Description description){
        return description.getClassName();
    }
}
