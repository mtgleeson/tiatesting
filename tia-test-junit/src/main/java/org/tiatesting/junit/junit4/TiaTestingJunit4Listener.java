package org.tiatesting.junit.junit4;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;
import org.tiatesting.persistence.PersistenceStrategy;
import org.tiatesting.report.ReportGenerator;
import org.tiatesting.report.TextFileReportGenerator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TiaTestingJunit4Listener extends RunListener {

    private static final Logger log = LoggerFactory.getLogger(TiaTestingJunit4Listener.class);

    private final JacocoClient coverageClient;
    private final DataStore dataStore;
    private final VCSReader vcsReader;
    private final Map<String, List<ClassImpactTracker>> testMethodsCalled;
    private Set<String> testSuitesFailed;

    public TiaTestingJunit4Listener(VCSReader vcsReader) {
        this.coverageClient = new JacocoClient();
        this.testMethodsCalled = new ConcurrentHashMap<>();
        this.testSuitesFailed = ConcurrentHashMap.newKeySet();
        this.vcsReader = vcsReader; //new GitReader(System.getProperty("tiaProjectDir"));
        this.dataStore = new MapDataStore(System.getProperty("tiaDBFilePath"), vcsReader.getBranchName(),
                System.getProperty("tiaDBPersistenceStrategy"));
    }

/*
    @Override
    public void testStarted(Description description) {
        System.out.println("test started: " + description.getClassName() + " " + description.getMethodName());
    }

    @Override
    public void testFinished(Description description) {
        System.out.println("test finished: " + description.getClassName() + " " + description.getMethodName());
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        System.out.println("test run started: " + description.getClassName() + " " + description.getMethodName());
    }
*/

    /**
     * Called when all tests have finished.
     *
     * @param result
     * @throws Exception
     */
    @Override
    public void testRunFinished(Result result) {
        if (dataStore.getDBPersistenceStrategy() == PersistenceStrategy.ALL){
            log.info("Test run finished. Persisting the test mapping.");
            this.dataStore.persistTestMapping(this.testMethodsCalled, this.testSuitesFailed, vcsReader.getHeadCommit());
        }

        // TODO temp. Create a new maven/gradle task/mojo that generates the file
        ReportGenerator reportGenerator = new TextFileReportGenerator(this.vcsReader.getBranchName());
        reportGenerator.generateReport(this.dataStore);
    }

    /**
     * Test suite maps to a test class.
     */
    /*
    @Override
    public void testSuiteStarted(Description description) throws Exception {
        System.out.println("test suite started: " + description.getClassName() + " " + description.getTestClass() + " " + description.getDisplayName());
    }
*/

    @Override
    public void testFailure(Failure failure) {
        this.testSuitesFailed.add(failure.getDescription().getClassName());
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        this.testSuitesFailed.add(failure.getDescription().getClassName());
    }

    /**
     * Called when a test suite (class) has finished, whether the test suite succeeds or fails.
     * Track the list of methods called for the tests in the suite (only if all the tests in the suite were successful).
     *
     * If the persistence strategy is incremental then also persist the method mapping for the test suite.
     *
     * @param description
     * @throws Exception
     */
    @Override
    public void testSuiteFinished(Description description) throws Exception {
        if (!this.testSuitesFailed.contains(description.getClassName())) {
            log.debug("Collecting coverage and adding the mapping for the test suite: " + description.getClassName());
            List<ClassImpactTracker> methodsCalledForTest = this.coverageClient.collectCoverage();
            this.testMethodsCalled.put(description.getClassName(), methodsCalledForTest);
        }

        if (dataStore.getDBPersistenceStrategy() == PersistenceStrategy.INCREMENTAL){
            log.info("Test suite finished for " + description.getClassName() + ". Persisting the incremental test mapping.");
            this.dataStore.persistTestMapping(this.testMethodsCalled, this.testSuitesFailed, vcsReader.getHeadCommit());
            this.testMethodsCalled.remove(description.getClassName());
            this.testSuitesFailed.remove(description.getClassName());
        }
    }
}
