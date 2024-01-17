package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.AbstractRunListener;
import org.spockframework.runtime.model.ErrorInfo;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.coverage.TestSuiteTracker;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TiaSpockRunListener extends AbstractRunListener {

    private static final Logger log = LoggerFactory.getLogger(TiaSpockRunListener.class);

    private final JacocoClient coverageClient;
    private final DataStore dataStore;
    private final VCSReader vcsReader;
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
        this.coverageClient = new JacocoClient();
        this.testSuiteTrackers = new ConcurrentHashMap<>();
        this.testSuitesFailed = ConcurrentHashMap.newKeySet();
        this.testSuitesProcessed = ConcurrentHashMap.newKeySet();
        this.testRunMethodsImpacted = new ConcurrentHashMap<>();
        this.specificationUtil = new SpecificationUtil();
        this.vcsReader = vcsReader;
        this.dataStore = dataStore;
        this.selectedTests = selectedTests;
        this.updateDBMapping = updateDBMapping;
        this.updateDBStats = updateDBStats;

        if (updateDBMapping){
            this.coverageClient.initialize();
        }
    }

    @Override
    public void beforeSpec(SpecInfo spec) {
        String specName = specificationUtil.getSpecName(spec);
        TestSuiteTracker testSuiteTracker = new TestSuiteTracker(specName);
        testSuiteTracker.setSourceFilename(specificationUtil.getSpecSourceFileName(spec));
        testSuiteTrackers.put(specName, testSuiteTracker);

        if (updateDBStats){
            testSuiteTracker.setNumRuns(1); // assume it will run. Explicitly set to 0 if ignored.
            testSuiteTracker.setNumSuccessRuns(1); // assume it will succeed. Explicitly set to false on failure.
            testSuiteTracker.setTotalRunTime(System.currentTimeMillis()); // track the start of the test run, do it in this object to keep the class thread safe
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

        if (updateDBStats) {
            // reset the stats - the tests wasn't run
            TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(specName);
            testSuiteTracker.setNumRuns(0);
            testSuiteTracker.setNumSuccessRuns(0);
            testSuiteTracker.setNumFailRuns(0);
        }
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
            testSuiteTracker.setTotalRunTime(calcTestSuiteRuntime(testSuiteTracker));
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

        if (updateDBMapping){
            this.dataStore.updateTestMapping(testSuiteTrackers, testSuitesFailed, runnerTestSuites, selectedTests,
                    testRunMethodsImpacted, vcsReader.getHeadCommit(), true);
        }

        if (updateDBStats){
            long totalRunTime = System.currentTimeMillis() - testRunStartTime;
            boolean getLatestDB = !updateDBMapping; // don't re-read the DB from disk if we've already loaded it for updating the mapping
            this.dataStore.updateStats(testSuiteTrackers, totalRunTime, getLatestDB);
        }

        this.dataStore.persistStoreMapping();
    }

    private long calcTestSuiteRuntime(TestSuiteTracker testSuiteTracker) {
        long totalRunTime = System.currentTimeMillis() - testSuiteTracker.getTotalRunTime();
        totalRunTime = totalRunTime > 1000 ? (totalRunTime / 1000) : 1;
        return totalRunTime;
    }

    private void updateTrackerStatsForFailedRun(String specName) {
        if (updateDBStats) {
            // reset the stats - the tests wasn't run
            TestSuiteTracker testSuiteTracker = this.testSuiteTrackers.get(specName);
            testSuiteTracker.setNumSuccessRuns(0);
            testSuiteTracker.setNumFailRuns(1);
        }
    }
}
