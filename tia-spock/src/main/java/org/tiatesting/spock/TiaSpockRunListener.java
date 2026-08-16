package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.AbstractRunListener;
import org.spockframework.runtime.model.ErrorInfo;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.distributed.DistributedRunnerContext;
import org.tiatesting.core.library.LibraryImpactDrainResult;
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
    private final String branch;
    private final Map<String, TestSuiteTracker> testSuiteTrackers;
    private final Set<String> testSuitesFailed;
    private final Set<String> testSuitesProcessed;
    /*
    The suites this JVM has actually observed - those it has seen finish (afterSpec) - with no
    directory-scan or spec-visiting override, unlike the runnerTestSuites the global extension hands
    to finishAllTests (which tracks every spec visitSpec sees, whether or not this JVM ever got to
    run it). Spec-level skips are NOT recorded here directly: Spock never calls IRunListener's own
    specSkipped hook (verified against the compiled spock-core jar - only the delegating wrapper
    listeners call it, the engine never does), so that observation is sourced instead from the JUnit
    Platform's executionSkipped via TiaSpockSkipExecutionListener, which writes into the JVM-static
    SharedSpockSkipObservation; finishAllTests merges that set in below before persisting. This
    field, once merged, is what feeds the distributed completeness guard: see
    TestRunResult#getSuitesObserved. One listener instance is used for the whole JVM (no
    Surefire-retry-style re-creation on the Gradle/Spock side), so a plain field is enough.
     */
    private final Set<String> suitesObserved;
    private final Map<Integer, MethodImpactTracker> testRunMethodsImpacted;
    private final Set<String> selectedTests;
    private final int ignoredTestSuiteCount;
    private final boolean updateDBMapping;
    private final boolean updateDBStats;
    private final boolean updateDBTestRunHistory;
    private final SpecificationUtil specificationUtil;
    private final LibraryImpactDrainResult libraryImpactDrainResult;
    private final DistributedRunnerContext distributedRunnerContext;
    private boolean stopStepRan;

    /**
     * Construct the Spock run listener.
     *
     * <p>The distributed context is handed in rather than resolved here, and it must be the one
     * built from the assignment the extension already claimed. A Gradle runner claims inside this
     * same test JVM, so re-deriving it here would claim a second group, leave the first one open
     * forever and the run would never seal.
     *
     * @param vcsReader               VCS reader (provides branch + head commit; closed here)
     * @param dataStore               persistence backend
     * @param selectedTests           tests Tia selected to run
     * @param ignoredTestSuiteCount   number of test suites Tia chose to ignore for this run;
     *                                persisted as {@code tia_test_run_history.num_suites_ignored}
     * @param updateDBMapping         persist test-suite ↔ method mapping
     * @param updateDBStats           persist run stats
     * @param updateDBTestRunHistory  log a row to {@code tia_test_run_history}
     * @param libraryImpactDrainResult drain result from selection (may be {@code null})
     * @param distributedRunnerContext the run id, runner identity and claimed group when this build
     *                                 is one runner of a distributed run, or {@code null} for an
     *                                 ordinary single-host build
     */
    public TiaSpockRunListener(final VCSReader vcsReader, final DataStore dataStore, Set<String> selectedTests,
                               final int ignoredTestSuiteCount,
                               final boolean updateDBMapping, final boolean updateDBStats,
                               final boolean updateDBTestRunHistory,
                               final LibraryImpactDrainResult libraryImpactDrainResult,
                               final DistributedRunnerContext distributedRunnerContext){
        this.testRunnerService = new TestRunnerService(dataStore);
        this.coverageClient = new JacocoClient();
        this.testSuiteTrackers = new ConcurrentHashMap<>();
        this.testSuitesFailed = ConcurrentHashMap.newKeySet();
        this.testSuitesProcessed = ConcurrentHashMap.newKeySet();
        this.suitesObserved = ConcurrentHashMap.newKeySet();
        this.testRunMethodsImpacted = new ConcurrentHashMap<>();
        this.specificationUtil = new SpecificationUtil();
        this.dataStore = dataStore;
        this.selectedTests = selectedTests;
        this.ignoredTestSuiteCount = ignoredTestSuiteCount;
        this.updateDBMapping = updateDBMapping;
        this.updateDBStats = updateDBStats;
        this.updateDBTestRunHistory = updateDBTestRunHistory;
        this.libraryImpactDrainResult = libraryImpactDrainResult;
        this.distributedRunnerContext = distributedRunnerContext;
        this.headCommit = vcsReader.getHeadCommit();
        this.branch = vcsReader.getBranchName();

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

    /**
     * Called once a spec has finished running - not called at all when the spec was skipped, since
     * Spock's engine never invokes {@code IRunListener.specSkipped} (verified against the compiled
     * spock-core jar; see {@link TiaSpockSkipExecutionListener} for where the skip observation comes
     * from instead). Collects its coverage and records it as observed by this JVM, mirroring the
     * JUnit5 listener's {@code executionFinished}/{@code testSuiteFinished}.
     *
     * @param spec the spec that finished
     */
    @Override
    public void afterSpec(SpecInfo spec) {
        String specName = specificationUtil.getSpecName(spec);

        if (spec.isSkipped() || this.testSuitesProcessed.contains(specName)) {
            return;
        }

        TestSuiteTracker testSuiteTracker = testSuiteTrackers.get(specName);

        if (updateDBStats){
            testSuiteTracker.getTestStats().setAvgRunTime(calcTestSuiteRuntime(testSuiteTracker));
        }

        if (updateDBMapping) {
            log.debug("Collecting coverage and adding the mapping for the test suite: " + specName);
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
        // this JVM has observed the spec (as finished) - see the field's javadoc.
        suitesObserved.add(specName);
    }

    /**
     * Persist everything this test JVM accumulated, once the Spock global extension reports that
     * every spec has finished. An ordinary build persists as a single host; a distributed runner
     * persists only its own share, completes its group and seals the build only if it turns out to
     * be the last runner to finish.
     *
     * @param runnerTestSuites every suite the runner discovered, executed or skipped, used to spot
     *                         suites that have been deleted since the last run
     * @param testRunStartTime UTC epoch millis when the test run started, used as both the history
     *                         row's timestamp and the duration baseline
     */
    public void finishAllTests(Set<String> runnerTestSuites, long testRunStartTime){
        if (stopStepRan){
            return;
        }

        stopStepRan = true; // this method is called twice for some reason - avoid processing it twice.
        log.info("Test run finished. Persisting the DB.");
        TestStats testStats = updateDBStats ? updateStatsForTestRun(testRunStartTime) : null;
        // Merge in the specs this JVM's JUnit Platform listener saw skipped - see the field comment
        // above and TiaSpockSkipExecutionListener for why that observation cannot come from Spock's
        // own specSkipped hook. Merged here, once, rather than as each skip happens, since this is
        // the one point every skip this JVM will ever see has already been recorded by.
        suitesObserved.addAll(SharedSpockSkipObservation.suitesObservedViaSkip());
        // Spock is not affected by the JUnit5/JUnit4 retry-inflation bug: finishAllTests fires
        // exactly once per JVM (guarded by stopStepRan) and beforeSpec uses Map.put so retried
        // specs overwrite the same key. So the cumulative testSuiteTrackers.size() equals the
        // per-attempt count - there's no separate counter to thread through.
        TestRunResult testRunResult = new TestRunResult(testSuiteTrackers, testSuitesFailed, runnerTestSuites,
                suitesObserved, selectedTests, testRunMethodsImpacted, testStats, libraryImpactDrainResult,
                ignoredTestSuiteCount, testSuiteTrackers.size());
        // Null context on an ordinary build, which persists as a single host - suite mapping,
        // failed set, seal and history row. A distributed runner instead persists only its own
        // share and completes its group, and seals the build only if it turns out to be the last
        // runner to finish.
        testRunnerService.persistTestRunData(updateDBMapping, updateDBStats, updateDBTestRunHistory,
                headCommit, branch, testRunStartTime, testRunResult, distributedRunnerContext);
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
