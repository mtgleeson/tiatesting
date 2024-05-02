package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.AbstractRunListener;
import org.spockframework.runtime.model.ErrorInfo;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.testrunner.TestRunResult;
import org.tiatesting.core.testrunner.TestRunnerService;
import org.tiatesting.core.vcs.VCSReader;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TiaSpockRunListener extends AbstractRunListener {

    private static final Logger log = LoggerFactory.getLogger(TiaSpockRunListener.class);

    private final TestRunnerService testRunnerService;
    private final JacocoClient coverageClient;
    private final DataStore dataStore;
    private final String headCommit;
    private final Map<String, TestSuiteTracker> testSuiteTrackers;
    private final Set<String> testSuitesFailed;
    private final Set<String> testSuitesProcessed;
    private final Map<Integer, MethodImpactTracker> testRunMethodsImpacted;
    private final Set<String> selectedTests;
    private final boolean updateDBMapping;
    private final boolean updateDBStats;
    private final SpecificationUtil specificationUtil;
    private boolean stopStepRan;

    public TiaSpockRunListener(final VCSReader vcsReader, final DataStore dataStore, Set<String> selectedTests,
                               final boolean updateDBMapping, final boolean updateDBStats){
        this.testRunnerService = new TestRunnerService(dataStore);
        this.coverageClient = new JacocoClient();
        this.testSuiteTrackers = new ConcurrentHashMap<>();
        this.testSuitesFailed = ConcurrentHashMap.newKeySet();
        this.testSuitesProcessed = ConcurrentHashMap.newKeySet();
        this.testRunMethodsImpacted = new ConcurrentHashMap<>();
        this.specificationUtil = new SpecificationUtil();
        this.dataStore = dataStore;
        this.selectedTests = selectedTests;
        this.updateDBMapping = updateDBMapping;
        this.updateDBStats = updateDBStats;
        this.headCommit = vcsReader.getHeadCommit();

        vcsReader.close();
        if (updateDBMapping){
            this.coverageClient.initialize();
        }
    }

    @Override
    public void beforeSpec(SpecInfo spec) {
        String specName = specificationUtil.getSpecName(spec);
        TestSuiteTracker testSuiteTracker = new TestSuiteTracker(specName);
        testSuiteTrackers.put(specName, testSuiteTracker);

        if (updateDBStats){
            // assume the test suite will run and succeed. Explicitly set to false on failure, or no runs if ignored.
            testSuiteTracker.getTestStats().setNumRuns(1);
            testSuiteTracker.getTestStats().setNumSuccessRuns(1);
            // track the start of the test run, do it in the test suite object to keep the class thread safe
            testSuiteTracker.getTestStats().setAvgRunTime(System.currentTimeMillis());
        }
    }

    @Override
    public void error(ErrorInfo error) {
        SpecInfo spec = error.getMethod().getFeature().getSpec();
        String specName = specificationUtil.getSpecName(spec);
        this.testSuitesFailed.add(specName);
        updateTrackerStatsForFailedRun(specName);
    }

    @Override
    public void specSkipped(SpecInfo spec) {
        String specName = specificationUtil.getSpecName(spec);
        log.info(specName + " was skipped!");

        /*
        Note, we don't need to reset stats for Ignore:
        1. when the test file spec is ignored, this method is not called.
        2. when at least 1 test in the suite is ignored, this method is not called either.
         */
    }

    @Override
    public void afterSpec(SpecInfo spec) {
        String specName = specificationUtil.getSpecName(spec);

        if (spec.isSkipped() || this.testSuitesProcessed.contains(specName)) {
            return;
        }

        log.debug("Collecting coverage and adding the mapping for the test suite: " + specName);
        TestSuiteTracker testSuiteTracker = testSuiteTrackers.get(specName);

        if (updateDBStats){
            testSuiteTracker.getTestStats().setAvgRunTime(calcTestSuiteRuntime(testSuiteTracker));
        }

        if (updateDBMapping) {
            try {
                CoverageResult coverageResult = this.coverageClient.collectCoverage();
                testSuiteTracker.setClassesImpacted(coverageResult.getClassesInvoked());
                testRunMethodsImpacted.putAll(coverageResult.getAllMethodsClassesInvoked());
            } catch (IOException e) {
                log.error("Error while collecting coverage", e);
                throw new RuntimeException(e);
            }
        }

        testSuitesProcessed.add(specName); // this method is called twice for some reason - avoid processing it twice.
    }

    public void finishAllTests(Set<String> runnerTestSuites, long testRunStartTime){
        if (stopStepRan){
            return;
        }

        stopStepRan = true; // this method is called twice for some reason - avoid processing it twice.
        log.info("Test run finished. Persisting the DB.");
        TestStats testStats = updateDBStats ? updateStatsForTestRun(testRunStartTime) : null;
        TestRunResult testRunResult = new TestRunResult(testSuiteTrackers, testSuitesFailed, runnerTestSuites,
                selectedTests, testRunMethodsImpacted, testStats);
        testRunnerService.persistTestRunData(updateDBMapping, updateDBStats, headCommit, testRunResult);
    }

    private TestStats updateStatsForTestRun(final long testRunStartTime){
        TestStats testRunStats = new TestStats();
        testRunStats.setNumRuns(1);
        testRunStats.setAvgRunTime(System.currentTimeMillis() - testRunStartTime);

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

    private void updateTrackerStatsForFailedRun(String specName) {
        if (updateDBStats) {
            // reset the stats - the tests wasn't run
            TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(specName);
            testSuiteTracker.getTestStats().setNumSuccessRuns(0);
            testSuiteTracker.getTestStats().setNumFailRuns(1);
        }
    }
}
