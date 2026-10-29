package org.tiatesting.core.diff.diffanalyze.selector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.diffanalyze.FileImpactAnalyzer;
import org.tiatesting.core.diff.diffanalyze.MethodImpactAnalyzer;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.library.PendingLibraryImpactedMethodsDrainer;
import org.tiatesting.core.library.TrackedLibraryReconciler;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.NestedTestSuites;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.sourcefile.SourceFilenameUtil;
import org.tiatesting.core.util.StringUtil;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionResolver;
import org.tiatesting.core.staticselection.StaticTestSelectionResult;
import org.tiatesting.core.staticselection.pom.PomChangeAnalyzer;
import org.tiatesting.core.staticselection.pom.PomChangeClassifier;
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.testrunner.TestSuiteRunnabilityClassifier;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

import static org.tiatesting.core.sourcefile.FileExtensions.JAVA_FILE_EXT;
import static org.tiatesting.core.sourcefile.FileExtensions.GROOVY_FILE_EXT;

public class TestSelector {

    private static final Logger log = LoggerFactory.getLogger(TestSelector.class);

    private final DataStore dataStore;

    /*
    The project whose configured source and test directories are resolved: the Gradle project's or
    Maven module's own directory. Never the JVM's working directory - a Gradle daemon keeps the
    directory it was first started in, which can be another project entirely, and a Maven build run
    from a multi-module root is not in the module's directory either.
     */
    private final File projectDir;

    FileImpactAnalyzer fileImpactAnalyzer = new FileImpactAnalyzer(new MethodImpactAnalyzer());

    /**
     * Create a selector for one project.
     *
     * @param dataStore the datastore holding the project's mapping
     * @param projectDir the project directory relative source and test directories are resolved
     *                   against - the Gradle project's or the Maven module's directory
     */
    public TestSelector(final DataStore dataStore, final File projectDir){
        this.dataStore = dataStore;
        this.projectDir = projectDir.getAbsoluteFile();
    }

    /**
     * Find the list of tests that should not be run.
     * <br>
     * Build the list of source files that have been changed since the previously analyzed commit.
     * For the source files that have changed, do a diff to find the methods that have changed.
     * Build the list of test suites that need to be run based on the methods that have been changed.
     * Add the tests that failed on the previous run - force them to be re-run.
     * <br>
     * Finally, find the list of all known tracked test suites that are associated with the list of tests to run. This
     * is the ignore list returned.
     * i.e. only ignore test suites that we have previously tracked and haven't been impacted by the source changes.
     * This ensures any new test suites are executed.
     * <br>
     * When a {@link LibraryImpactAnalysisConfig} is non-null and enabled, the reconciler synchronises
     * declared libraries against the persisted {@code tia_library} rows before proceeding with test selection.
     *
     * @param vcsReader the VCS reader
     * @param sourceFilesDirNames the dir names for the source files
     * @param testFilesDirNames the dir names for the test files
     * @param runnabilityClassifier classifies newly added test files against the compiled test
     *                              classes, so a new file proven not to be a test (a helper, an
     *                              abstract base, an interface) is not selected; pass
     *                              {@link TestSuiteRunnabilityClassifier#disabled()} when no compiled
     *                              test classes are available, which keeps every new file
     * @param checkLocalChanges should local changes be checked by Tia.
     * @param libraryConfig the library impact analysis config, or {@code null} if not configured.
     * @param staticMappingConfig the static test selection config, or {@code null} if not configured.
     *                            When enabled, each rule's file-path regex is matched against the
     *                            changed file paths from the VCS reader, and any forced suites are
     *                            unioned into the dynamic test selection.
     * @param updateDBMapping whether this run owns mapping-DB updates. When {@code false} (non-primary
     *                       build / local workspace), test selection still runs but no mapping/library
     *                       writes are performed: tracked-library reconcile is skipped. Library
     *                       stamping never happens on the app run - the library's own publish owns
     *                       it. Stats writes are independent and not affected by this flag.
     * @param requestedMode {@link SelectionMode#SELECTIVE} for ordinary selection, or
     *                      {@link SelectionMode#SELECT_ALL} / {@link SelectionMode#RESEED} to
     *                      override it and run every test: the diff, the targeted mapping queries
     *                      and the static rules are skipped, while the library reconcile and drain
     *                      still run. A request on a database with no stored mapping is a seed.
     *                      See the "Forced runs and re-seed" chapter in {@code WIKI.md}.
     * @return list of test suites to ignore in the current test run.
     */
    public TestSelectorResult selectTestsToIgnore(final VCSReader vcsReader, final List<String> sourceFilesDirNames,
                                           final List<String> testFilesDirNames,
                                           final TestSuiteRunnabilityClassifier runnabilityClassifier,
                                           final boolean checkLocalChanges,
                                           final LibraryImpactAnalysisConfig libraryConfig,
                                           final StaticTestSelectionConfig staticMappingConfig,
                                           final boolean updateDBMapping,
                                           final SelectionMode requestedMode){
        // Targeted read path: only the single-row core data is loaded up front. The mapping
        // is queried per diff-slice (the changed-files-to-tracked-methods and
        // methods-to-covering-suites lookups) inside selectTestsToRun, and the suite-level
        // metadata (names + stats, no coverage edges) is loaded once below. The full
        // suite-class-method mapping is never bulk-loaded on this path.
        TiaData tiaCore = dataStore.getTiaCore();

        if (updateDBMapping) {
            reconcileTrackedLibrariesIfConfigured(libraryConfig);
        }

        if (!hasStoredMapping(tiaCore)){
            // run all tests - don't ignore any
            return new TestSelectorResult(new HashSet<>(), new HashSet<>(), null,
                    0L, Collections.emptySet(), 0L, Collections.emptyMap(),
                    tiaCore.getTestStats().getAllTestsRunTime(), 0L, 0L, SelectionMode.SEED,
                    TestRunSelectionDetails.forFullRun(SelectionMode.SEED));
        }

        if (requestedMode.isForced()) {
            return selectAllTests(requestedMode, tiaCore, libraryConfig);
        }

        // Suite names + stats only (no coverage edges): serves the modified-test-file check,
        // the ignore list and the run-time estimate.
        Map<String, TestSuiteTracker> testSuitesTracked = dataStore.getTestSuitesTracked();

        SelectTestsToRunResult runResult = selectTestsToRun(vcsReader, sourceFilesDirNames, testFilesDirNames,
                runnabilityClassifier, checkLocalChanges, tiaCore.getCommitValue(), testSuitesTracked, libraryConfig);
        Set<String> testsToRun = runResult.getTestsToRun();

        PendingLibrarySelection librarySelection = drainPendingLibraryMethodsIfConfigured(
                libraryConfig, testsToRun, testSuitesTracked);

        List<TestRunTrigger> staticRuleTriggers = applyStaticTestSelection(vcsReader, staticMappingConfig,
                tiaCore.getCommitValue(), testSuitesTracked, testsToRun, checkLocalChanges, libraryConfig);

        // @Nested families are selected whole - added after every other source of selection, so it
        // covers all of them. See NestedTestSuites#addFamilies for why.
        NestedTestSuites.addFamilies(testsToRun, testSuitesTracked);

        // Get the list of tests from the stored mapping that aren't in the list of test suites to run.
        Set<String> testsToIgnore = getTestsToIgnore(testSuitesTracked, testsToRun);

        if (log.isDebugEnabled()) {
            log.debug("Ignoring {} test suite(s): {}", testsToIgnore.size(),
                    StringUtil.sampleSorted(testsToIgnore, StringUtil.LOG_SAMPLE_SIZE));
        }

        RunTimeEstimate estimate = estimateRunTime(testsToRun, testSuitesTracked,
                tiaCore.getTestStats());

        List<TestRunTrigger> triggers = new ArrayList<>(runResult.getSourceMethodTriggers());
        triggers.addAll(staticRuleTriggers);
        TestRunSelectionDetails selectionDetails = new TestRunSelectionDetails(triggers,
                runResult.getNumModifiedTestFiles(), runResult.getNumNewTestFiles(),
                runResult.getNumTestSupportDependants(),
                runResult.getNumPreviouslyFailed(), runResult.getNumUnsealedMapping(),
                librarySelection.getNumPendingLibrary(), SelectionMode.SELECTIVE);

        return new TestSelectorResult(testsToRun, testsToIgnore, librarySelection.getDrainResult(),
                estimate.getEstimatedRunTimeMs(), estimate.getSelectedTestsWithoutStats(),
                estimate.getMedianRunTimeMsAppliedToMissing(),
                estimate.getSelectedTestRunTimesMs(),
                tiaCore.getTestStats().getAllTestsRunTime(), estimate.getCaptureOverheadMs(),
                estimate.getFixedOverheadMs(), SelectionMode.SELECTIVE, selectionDetails);
    }

    /**
     * Build the result of a forced full run: nothing ignored, every tracked suite the developer has
     * not disabled listed as selected (so the run-time estimate and the developer-disabled
     * bookkeeping see the real run set), and the pending library drain still evaluated so the seal
     * can clean up exactly the stamps this run applied. The VCS diff, the targeted mapping queries
     * and the static rules are skipped - they could only add suites that are running anyway. New
     * suites still run: the ignore list is the only filter, and it is empty. See the "Forced runs
     * and re-seed" chapter in {@code WIKI.md}.
     *
     * @param mode the forced mode, {@link SelectionMode#SELECT_ALL} or {@link SelectionMode#RESEED}
     * @param tiaCore the core data, for the stats the estimate is built from
     * @param libraryConfig the library impact analysis config, or null when not configured
     * @return the run-all result carrying {@code mode}
     */
    private TestSelectorResult selectAllTests(final SelectionMode mode, final TiaData tiaCore,
                                              final LibraryImpactAnalysisConfig libraryConfig) {
        log.info("Test selection overridden ({}): running all tests.", mode.getLabel());
        Map<String, TestSuiteTracker> testSuitesTracked = dataStore.getTestSuitesTracked();
        Set<String> testsToRun = new HashSet<>();
        for (Map.Entry<String, TestSuiteTracker> entry : testSuitesTracked.entrySet()) {
            if (!entry.getValue().isDeveloperDisabled()) {
                testsToRun.add(entry.getKey());
            }
        }

        PendingLibrarySelection librarySelection = drainPendingLibraryMethodsIfConfigured(
                libraryConfig, testsToRun, testSuitesTracked);
        RunTimeEstimate estimate = estimateRunTime(testsToRun, testSuitesTracked,
                tiaCore.getTestStats());

        return new TestSelectorResult(testsToRun, new HashSet<>(), librarySelection.getDrainResult(),
                estimate.getEstimatedRunTimeMs(), estimate.getSelectedTestsWithoutStats(),
                estimate.getMedianRunTimeMsAppliedToMissing(), estimate.getSelectedTestRunTimesMs(),
                tiaCore.getTestStats().getAllTestsRunTime(), estimate.getCaptureOverheadMs(),
                estimate.getFixedOverheadMs(), mode, TestRunSelectionDetails.forFullRun(mode));
    }

    /**
     * Estimate the total runtime for the given set of selected tests.
     *
     * <p>For each test in {@code testsToRun} that has a tracked {@link TestSuiteTracker} in
     * {@code tracked}, the test's persisted {@code avgRunTime} (ms) contributes to the total.
     * Tests not present in {@code testSuitesTracked} (typically newly-added test files) are
     * collected into the {@code selectedTestsWithoutStats} set. When at least one such test
     * exists, the median {@code avgRunTime} across all tracked suites with a positive
     * {@code avgRunTime} is computed and added once per missing test. If no tracked suite has
     * a positive {@code avgRunTime}, the median is {@code 0} and missing tests contribute
     * nothing to the total.
     *
     * <p>The recorded times are each suite's own - an enclosing class's excludes the
     * {@code @Nested} classes that ran inside it - so the total is a plain sum.
     *
     * <p>The base estimate above is pure per-suite execution time. A mapping-update run also pays
     * JaCoCo coverage capture plus whole-run costs (JVM/agent startup, the final persist), none of
     * which is in {@code avgRunTime} (that is measured before coverage collection). Those are
     * returned separately, and as <b>two</b> figures rather than one - see {@link #overheadModel} -
     * so a caller can add them to the base only when the run being estimated collects coverage, and
     * so a caller splitting the run across runners can charge the per-JVM part once per runner
     * rather than dividing it across them.
     *
     * <p>The fixed part is reported as {@code 0} for an empty selection: a build that runs no suites
     * starts no test JVM, and charging it would put a non-zero estimate on a nothing-impacted build.
     *
     * @param testsToRun the names of the selected test suites
     * @param tracked the tracked test suites (names + stats) keyed by suite name
     * @param tiaStats the Tia-level stats: the full-suite baseline and, once a distributed build has
     *                 measured them, the two stored overhead constants
     * @return a {@link RunTimeEstimate} carrying the base estimate, the names of selected tests
     *         with no stats, the median applied to those tests, and the two overhead figures
     */
    static RunTimeEstimate estimateRunTime(final Set<String> testsToRun, final Map<String, TestSuiteTracker> tracked,
                                           final TestStats tiaStats){
        Set<String> withoutStats = new HashSet<>();
        Map<String, Long> perTestRunTimes = new HashMap<>();

        for (String testName : testsToRun){
            TestSuiteTracker tracker = tracked.get(testName);
            if (tracker == null){
                withoutStats.add(testName);
                perTestRunTimes.put(testName, 0L); // placeholder, replaced with median below
            } else {
                perTestRunTimes.put(testName, tracker.getTestStats().getAvgRunTime());
            }
        }

        long median = 0L;
        if (!withoutStats.isEmpty()){
            median = computeMedianAvgRunTime(tracked);
            for (String testName : withoutStats){
                perTestRunTimes.put(testName, median);
            }
        }
        // Recorded times are each suite's own (the listener excludes @Nested classes from their
        // enclosing class), so they simply add up.
        long totalMs = 0L;
        for (long suiteMs : perTestRunTimes.values()) {
            totalMs += suiteMs;
        }

        OverheadModel overhead = overheadModel(tracked, tiaStats);
        long captureOverheadMs = overhead.getCapturePerSuiteMs() * (long) testsToRun.size();
        long fixedOverheadMs = testsToRun.isEmpty() ? 0L : overhead.getFixedMs();

        log.debug("Run time estimate: {} suite(s) selected taking {}ms of test time, plus {}ms of "
                        + "coverage capture ({}ms per suite x {}) and {}ms of fixed per-JVM "
                        + "overhead charged once. Serial-equivalent total {}ms, for a run that "
                        + "collects coverage.", testsToRun.size(), totalMs, captureOverheadMs,
                overhead.getCapturePerSuiteMs(), testsToRun.size(), fixedOverheadMs,
                totalMs + captureOverheadMs + fixedOverheadMs);

        return new RunTimeEstimate(totalMs, withoutStats, median, perTestRunTimes,
                captureOverheadMs, fixedOverheadMs);
    }

    /**
     * Derive the two parts a run's overhead is made of: the per-suite cost of JaCoCo's coverage
     * collection, and the cost a test JVM pays once however few suites it runs (engine start-up,
     * class loading, the final coverage dump). Neither is in any suite's {@code avgRunTime}, which
     * the listeners freeze before coverage is collected.
     *
     * <p><b>Why two numbers and not one.</b> Tia only ever had the whole-run figure - the full-suite
     * baseline minus the sum of the tracked suite averages - and amortised it across the tracked
     * suites. That is this model with the fixed part deleted, and it is self-consistent for a
     * single-host run: dividing by the tracked count and multiplying back up by the selected count
     * recovers the right total. It is wrong the moment a build is split, because every runner is its
     * own JVM and pays the fixed cost <em>in full</em> - so the cost is duplicated per group while
     * the estimate divided it away.
     *
     * <p><b>Where the split comes from.</b> One equation cannot separate two unknowns, so the pair
     * is measured rather than derived here: a distributed build supplies a second equation at a
     * different suite count, and {@code DistributedRunOverheadModel} solves it at seal time into the
     * rolling averages this reads back. Until a project has run one distributed build there is
     * nothing to read, and the fall-back is exactly the previous behaviour - the whole overhead
     * amortised per suite, with no fixed part - so an estimate is never worse than it was.
     *
     * <p><b>Neither debug line names distributed runs</b>, deliberately. This runs on every
     * selection, and the overwhelming majority of them are ordinary single-host builds for which the
     * fixed part is irrelevant - it only changes an estimate once a build is split across JVMs.
     * Explaining a mode the build is not in reads as a missing feature rather than as normal
     * operation. {@code DistributedRunPlanner} says where the fixed part comes from, and what it
     * means when it is still zero, at the one point where that actually matters.
     *
     * <p>The fall-back returns nothing at all when there is no baseline, no tracked suites, or a
     * baseline below the per-suite sum. That last case means the build ran its suites in parallel
     * (wall clock under the serial sum); this only models sequential builds, so it clamps rather
     * than subtracting.
     *
     * @param tracked the tracked test suites (names + stats) keyed by suite name
     * @param tiaStats the Tia-level stats carrying the full-suite baseline and the stored constants
     * @return the two overhead parts, either of which may be {@code 0}
     */
    static OverheadModel overheadModel(final Map<String, TestSuiteTracker> tracked,
                                       final TestStats tiaStats){
        if (tiaStats.getNumOverheadMeasurements() > 0){
            log.debug("Overhead model: {}ms of coverage capture per suite and {}ms fixed per test "
                            + "JVM, measured over {} build(s) that reported a per-group split.",
                    tiaStats.getCaptureOverheadPerSuiteMs(), tiaStats.getFixedOverheadMs(),
                    tiaStats.getNumOverheadMeasurements());
            return new OverheadModel(tiaStats.getFixedOverheadMs(),
                    tiaStats.getCaptureOverheadPerSuiteMs());
        }

        long allTestsRunTimeMs = tiaStats.getAllTestsRunTime();
        if (allTestsRunTimeMs <= 0 || tracked.isEmpty()){
            log.debug("Overhead model: none - {}, so no overhead is added to the estimate.",
                    allTestsRunTimeMs <= 0
                            ? "no all-tests baseline has been recorded yet"
                            : "no test suites are tracked yet");
            return new OverheadModel(0L, 0L);
        }
        long sumAvg = 0L;
        for (TestSuiteTracker tracker : tracked.values()){
            sumAvg += tracker.getTestStats().getAvgRunTime();
        }
        long overhead = allTestsRunTimeMs - sumAvg;
        if (overhead <= 0){
            log.debug("Overhead model: none - the {}ms all-tests baseline does not exceed the {}ms "
                            + "sum of the {} tracked suite average(s), which is what a build that "
                            + "runs its suites in parallel records. The overhead clamps to zero "
                            + "rather than going negative.", allTestsRunTimeMs, sumAvg,
                    tracked.size());
            return new OverheadModel(0L, 0L);
        }
        long capturePerSuiteMs = overhead / tracked.size();
        log.debug("Overhead model: {}ms of overhead ({}ms all-tests baseline less {}ms of tracked "
                        + "suite averages) amortised across {} tracked suite(s) at {}ms each.",
                overhead, allTestsRunTimeMs, sumAvg, tracked.size(), capturePerSuiteMs);
        return new OverheadModel(0L, capturePerSuiteMs);
    }

    /**
     * The two constants a run's overhead is modelled with. Package-private, like
     * {@link RunTimeEstimate}, so tests can assert on the split without going through the full
     * selection entry point.
     */
    static class OverheadModel {
        private final long fixedMs;
        private final long capturePerSuiteMs;

        /**
         * @param fixedMs the cost a test JVM pays once, however few suites it runs; charged once per
         *                runner, so a split build pays it once per group rather than once in total
         * @param capturePerSuiteMs the coverage-collection cost every executed suite pays, which
         *                          scales with the number of suites wherever they run
         */
        OverheadModel(long fixedMs, long capturePerSuiteMs) {
            this.fixedMs = fixedMs;
            this.capturePerSuiteMs = capturePerSuiteMs;
        }

        /** @return the per-JVM cost in ms, charged once per runner; 0 when never measured */
        long getFixedMs() { return fixedMs; }

        /** @return the per-suite coverage-collection cost in ms; 0 when it cannot be derived */
        long getCapturePerSuiteMs() { return capturePerSuiteMs; }
    }

    /**
     * Compute the median {@code avgRunTime} (ms) across all tracked test suites that have a
     * positive recorded {@code avgRunTime}. Suites with {@code avgRunTime == 0} (tracked but
     * never recorded a real run) are excluded so they don't drag the median to zero.
     *
     * <p>For an even-sized population, the lower of the two middle values is returned. This
     * keeps the calculation deterministic and integer-only and avoids overstating the median
     * when the population is small.
     *
     * @param tracked the map of tracked test suites keyed by name
     * @return the median {@code avgRunTime} (ms), or {@code 0} when no tracked suite has a
     *         positive {@code avgRunTime}
     */
    private static long computeMedianAvgRunTime(final Map<String, TestSuiteTracker> tracked){
        List<Long> positive = new ArrayList<>(tracked.size());
        for (TestSuiteTracker tracker : tracked.values()){
            long avg = tracker.getTestStats().getAvgRunTime();
            if (avg > 0){
                positive.add(avg);
            }
        }
        if (positive.isEmpty()){
            return 0L;
        }
        Collections.sort(positive);
        return positive.get((positive.size() - 1) / 2);
    }

    /**
     * Holder for the result of {@link #estimateRunTime(Set, TiaData)}. Package-private so
     * unit tests can destructure it without going through the full {@code selectTestsToIgnore}
     * entry point.
     */
    static class RunTimeEstimate {
        private final long estimatedRunTimeMs;
        private final Set<String> selectedTestsWithoutStats;
        private final long medianRunTimeMsAppliedToMissing;
        private final Map<String, Long> selectedTestRunTimesMs;
        private final long captureOverheadMs;
        private final long fixedOverheadMs;

        /**
         * @param estimatedRunTimeMs base estimated runtime (ms) for the selected tests - the sum
         *                           of per-suite times including the median fallback, with no
         *                           overhead added
         * @param selectedTestsWithoutStats names of selected tests with no recorded stats
         * @param medianRunTimeMsAppliedToMissing median {@code avgRunTime} (ms) applied to
         *                                        each test in {@code selectedTestsWithoutStats},
         *                                        or {@code 0} when no fallback was applied
         * @param selectedTestRunTimesMs per-test runtime (ms) keyed by test suite name; covers
         *                               every entry in {@code testsToRun}, with the median
         *                               value (or {@code 0} when no median is available) used
         *                               for tests without stats
         * @param captureOverheadMs the coverage-collection time (ms) the selected suites would add
         *                          between them - per-suite, so it scales with the selection and is
         *                          the part a distributed build divides across its groups
         * @param fixedOverheadMs the time (ms) a test JVM pays once however few suites it runs;
         *                        charged once per runner, so a distributed build pays a copy per
         *                        group rather than dividing it. {@code 0} for an empty selection,
         *                        which starts no test JVM at all
         */
        RunTimeEstimate(long estimatedRunTimeMs, Set<String> selectedTestsWithoutStats,
                        long medianRunTimeMsAppliedToMissing,
                        Map<String, Long> selectedTestRunTimesMs, long captureOverheadMs,
                        long fixedOverheadMs) {
            this.estimatedRunTimeMs = estimatedRunTimeMs;
            this.selectedTestsWithoutStats = selectedTestsWithoutStats;
            this.medianRunTimeMsAppliedToMissing = medianRunTimeMsAppliedToMissing;
            this.selectedTestRunTimesMs = selectedTestRunTimesMs;
            this.captureOverheadMs = captureOverheadMs;
            this.fixedOverheadMs = fixedOverheadMs;
        }

        /** @return the base estimated runtime (ms) for the selected tests, without any overhead */
        long getEstimatedRunTimeMs() { return estimatedRunTimeMs; }

        /** @return names of selected tests with no recorded run-time stats */
        Set<String> getSelectedTestsWithoutStats() { return selectedTestsWithoutStats; }

        /** @return median {@code avgRunTime} (ms) substituted for tests without stats */
        long getMedianRunTimeMsAppliedToMissing() { return medianRunTimeMsAppliedToMissing; }

        /** @return per-test runtime (ms) keyed by test suite name */
        Map<String, Long> getSelectedTestRunTimesMs() { return selectedTestRunTimesMs; }

        /**
         * @return the coverage-capture overhead (ms) across the selected suites; added only for
         *         coverage runs, and divided back out per suite when weighting a distributed split
         */
        long getCaptureOverheadMs() { return captureOverheadMs; }

        /**
         * @return the per-JVM overhead (ms) charged once per runner; added only for coverage runs,
         *         and never divided across a distributed build's groups - each pays a full copy
         */
        long getFixedOverheadMs() { return fixedOverheadMs; }
    }

    private boolean hasStoredMapping(TiaData tiaData){
        log.info("Stored DB commit: " + tiaData.getCommitValue());

        if (tiaData.getCommitValue() == null) {
            // If no stored commit value found it means Tia hasn't previously run. We need to run all tests, don't ignore any.
            log.info("No stored commit value found. Tia hasn't previously run. Running all tests.");
            return false;
        }

        return true;
    }

    /**
     * Core test selection logic. When a {@link LibraryImpactAnalysisConfig} is provided and
     * enabled, modified source diffs are partitioned into source-project vs per-library buckets
     * and the library-owned diffs are excluded from source selection - under publish-time
     * stamping the library's own publish records their impacted methods, and the drain selects
     * the covering tests once the app resolves a build that contains them. The tracked library
     * source dirs are only added to the diff in {@code checkLocalChanges} mode, where library
     * diff partitioning is bypassed entirely - all diffs are treated as source-project diffs so
     * tests run immediately against local changes. Outside that mode the partition remains as a
     * guard for configured source dirs that cover a tracked library.
     *
     * <p>Mapping reads are targeted to the diff: one changed-files-to-tracked-methods query
     * ({@link DataStore#getMethodsTrackedForFiles}) resolves the changed files to their tracked
     * methods, and one methods-to-covering-suites query
     * ({@link DataStore#getTestSuitesForMethods}) resolves the impacted methods to suites.
     *
     * @param vcsReader the VCS reader used to build the diff contexts
     * @param sourceFilesDirNames the dir names for the source files
     * @param testFilesDirNames the dir names for the test files
     * @param runnabilityClassifier classifies newly added test files against the compiled test
     *                              classes
     * @param checkLocalChanges should local changes be checked by Tia
     * @param storedCommitValue the commit the stored mapping was built at (diff baseline)
     * @param testSuitesTracked the tracked test suites (names + stats) keyed by suite name
     * @param libraryConfig the library impact analysis config, or {@code null} if not configured
     * @return the test suites that should be executed for the current changes, together with the
     *         source-method triggers and the scalar counts for each dynamic selection source, for
     *         the run-history selection breakdown
     */
    private SelectTestsToRunResult selectTestsToRun(final VCSReader vcsReader, final List<String> sourceFilesDirNames,
                                         final List<String> testFilesDirNames,
                                         final TestSuiteRunnabilityClassifier runnabilityClassifier,
                                         final boolean checkLocalChanges,
                                         final String storedCommitValue,
                                         final Map<String, TestSuiteTracker> testSuitesTracked,
                                         final LibraryImpactAnalysisConfig libraryConfig){
        requireConfigured(sourceFilesDirNames, "source", "tiaSourceFilesDirs", "sourceFilesDirs");
        requireConfigured(testFilesDirNames, "test", "tiaTestFilesDirs", "testFilesDirs");
        List<String> sourceFilesDirs = getFullFilePaths(sourceFilesDirNames);
        List<String> testFilesDirs = getFullFilePaths(testFilesDirNames);

        // In local-changes mode, include tracked library source dirs so local library edits pass
        // the source/test dir filter in the VCS analyzers and have their prefixes stripped to
        // mapping keys - they feed direct selection there. Outside it, library changes reach the
        // app through the publish stamp + drain and would only be partitioned out again below, so
        // diffing them (and loading their content) is wasted work on the select-tests path.
        if (shouldDiffTrackedLibraryDirs(libraryConfig, checkLocalChanges)) {
            sourceFilesDirs = new ArrayList<>(sourceFilesDirs);
            sourceFilesDirs.addAll(collectTrackedLibraryDirs());
        }

        // Get the changed files (paths + change type, NO content yet). Content is fetched further
        // down, for only the files that turn out to be tracked in the mapping.
        Set<SourceFileDiffContext> impactedSourceFiles = vcsReader.getDiffFiles(storedCommitValue,
                sourceFilesDirs, testFilesDirs, checkLocalChanges);
        Map<String, List<SourceFileDiffContext>> groupedImpactedFiles = fileImpactAnalyzer.groupImpactedTestFiles(impactedSourceFiles, testFilesDirs);

        List<SourceFileDiffContext> modifiedSourceDiffs = groupedImpactedFiles.get(FileImpactAnalyzer.SOURCE_FILE_MODIFIED);

        // Changed-files-to-tracked-methods lookup: one targeted query for the tracked methods of
        // ALL modified source files (source-project and library buckets alike) - both consumers
        // below share the result.
        Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile =
                loadMethodsTrackedForDiffs(modifiedSourceDiffs, sourceFilesDirs);

        // Fetch file content only for the modified source files that are actually tracked in the
        // mapping. A changed file with no tracked coverage cannot select any test, so diffing it is
        // wasted work - and on Perforce fetching its content from the server is the dominant cost
        // of select-tests over a large changelist range. Selection is unchanged: the files dropped
        // here are exactly the ones findMethodsImpacted would have found nothing for (their key is
        // absent from the changed-files-to-tracked-methods result). Test-file diffs need only their
        // path, so they are never content-loaded.
        List<SourceFileDiffContext> trackedModifiedSourceDiffs =
                filterToTrackedFiles(modifiedSourceDiffs, methodsTrackedByFile, sourceFilesDirs);

        // A locally changed file whose base differs from its mapped version can't be matched to
        // tracked methods by line number, so all of its tracked methods are treated as impacted
        // instead. Its content isn't needed for that, so it is left out of the content load.
        List<SourceFileDiffContext> driftedDiffs = checkLocalChanges
                ? findDriftedDiffs(vcsReader, storedCommitValue, trackedModifiedSourceDiffs,
                        methodsTrackedByFile, sourceFilesDirs)
                : Collections.emptyList();
        List<SourceFileDiffContext> lineMatchedDiffs = trackedModifiedSourceDiffs;
        if (!driftedDiffs.isEmpty()) {
            Set<SourceFileDiffContext> excluded = Collections.newSetFromMap(new IdentityHashMap<>());
            excluded.addAll(driftedDiffs);
            lineMatchedDiffs = new ArrayList<>();
            for (SourceFileDiffContext diff : trackedModifiedSourceDiffs) {
                if (!excluded.contains(diff)) {
                    lineMatchedDiffs.add(diff);
                }
            }
        }
        vcsReader.loadContentForDiffs(lineMatchedDiffs, storedCommitValue, checkLocalChanges);

        // Partition source diffs: library diffs are excluded (stamped at publish time by the
        // library's own build), source-project diffs go to immediate selection.
        List<SourceFileDiffContext> sourceProjectDiffs = lineMatchedDiffs;
        if (shouldPartitionLibraryDiffs(libraryConfig, checkLocalChanges)) {
            sourceProjectDiffs = partitionOutLibraryDiffs(lineMatchedDiffs);
        }

        // Find all test suites that execute the source code methods that have changed
        Set<Integer> impactedMethods = findMethodsImpacted(sourceProjectDiffs, methodsTrackedByFile, sourceFilesDirs);
        impactedMethods.addAll(allTrackedMethodIds(driftedDiffs, methodsTrackedByFile, sourceFilesDirs));
        SourceMethodTriggerResult methodResult = findTestSuitesForImpactedMethods(impactedMethods, methodsTrackedByFile);
        Set<String> testsToRun = methodResult.getTestsToRun();

        // Changed test files that aren't tracked suites - helpers, base classes, fixtures - are
        // collected by the next three steps, to select the suites that depend on them.
        Set<String> changedSupportClasses = new TreeSet<>();

        // If any test suite files were modified, always re-run these. So add them to the run list.
        int numModifiedTestFiles = addModifiedTestFilesToRunList(
                groupedImpactedFiles.get(FileImpactAnalyzer.TEST_FILE_MODIFIED), testSuitesTracked, testsToRun,
                testFilesDirs, changedSupportClasses);

        // Add newly added test files to the run list.
        int numNewTestFiles = addNewTestFilesToRunList(
                groupedImpactedFiles.get(FileImpactAnalyzer.TEST_FILE_ADDED), testsToRun, testFilesDirs,
                runnabilityClassifier, changedSupportClasses);

        collectDeletedSupportClasses(groupedImpactedFiles.get(FileImpactAnalyzer.TEST_FILE_DELETED),
                testSuitesTracked, testFilesDirs, changedSupportClasses);

        // Add the suites that use a changed test support class, directly or through other test classes.
        int numTestSupportDependants = addTestSupportDependantsToRunList(changedSupportClasses, testFilesDirs,
                testSuitesTracked, testsToRun);

        // Re-run tests that failed since the last successful full test run.
        int numPreviouslyFailed = addPreviouslyFailedTests(testsToRun);

        // Re-run suites whose mapping rows were written by a run that never sealed - those rows
        // describe a later commit than the stored one.
        int numUnsealedMapping = addUnsealedTests(testSuitesTracked, testsToRun);

        return new SelectTestsToRunResult(testsToRun, methodResult.getTriggers(), numModifiedTestFiles,
                numNewTestFiles, numTestSupportDependants, numPreviouslyFailed, numUnsealedMapping);
    }

    /**
     * Holder for the result of {@link #selectTestsToRun}: the run set together with the
     * source-method triggers and the scalar counts for each dynamic selection source that feed
     * the run-history {@link TestRunSelectionDetails} breakdown. Package-private, like
     * {@link RunTimeEstimate}, so tests can assert on the breakdown inputs without going through
     * the full {@code selectTestsToIgnore} entry point.
     */
    static class SelectTestsToRunResult {
        private final Set<String> testsToRun;
        private final List<TestRunTrigger> sourceMethodTriggers;
        private final int numModifiedTestFiles;
        private final int numNewTestFiles;
        private final int numTestSupportDependants;
        private final int numPreviouslyFailed;
        private final int numUnsealedMapping;

        /**
         * @param testsToRun the test suites selected to run from the dynamic sources
         * @param sourceMethodTriggers one trigger per impacted method, carrying its covering-suite count
         * @param numModifiedTestFiles count of modified test files added to the run set
         * @param numNewTestFiles count of newly-added test files added to the run set
         * @param numTestSupportDependants count of suites selected because they use a changed test
         *                                 support class
         * @param numPreviouslyFailed count of previously-failed suites re-added to the run set
         * @param numUnsealedMapping count of suites re-added from unsealed mapping rows
         */
        SelectTestsToRunResult(Set<String> testsToRun, List<TestRunTrigger> sourceMethodTriggers,
                               int numModifiedTestFiles, int numNewTestFiles, int numTestSupportDependants,
                               int numPreviouslyFailed, int numUnsealedMapping) {
            this.testsToRun = testsToRun;
            this.sourceMethodTriggers = sourceMethodTriggers;
            this.numModifiedTestFiles = numModifiedTestFiles;
            this.numNewTestFiles = numNewTestFiles;
            this.numTestSupportDependants = numTestSupportDependants;
            this.numPreviouslyFailed = numPreviouslyFailed;
            this.numUnsealedMapping = numUnsealedMapping;
        }

        /** @return the test suites selected to run from the dynamic sources */
        Set<String> getTestsToRun() { return testsToRun; }

        /** @return one trigger per impacted method, carrying its covering-suite count */
        List<TestRunTrigger> getSourceMethodTriggers() { return sourceMethodTriggers; }

        /** @return count of modified test files added to the run set */
        int getNumModifiedTestFiles() { return numModifiedTestFiles; }

        /** @return count of newly-added test files added to the run set */
        int getNumNewTestFiles() { return numNewTestFiles; }

        /** @return count of suites selected because they use a changed test support class */
        int getNumTestSupportDependants() { return numTestSupportDependants; }

        /** @return count of previously-failed suites re-added to the run set */
        int getNumPreviouslyFailed() { return numPreviouslyFailed; }

        /** @return count of suites re-added from unsealed mapping rows */
        int getNumUnsealedMapping() { return numUnsealedMapping; }
    }

    /**
     * Add suites whose stored mapping rows are unsealed - written by a run that did not reach its
     * commit seal, so they describe a later commit than the stored one. Re-running them recaptures
     * their coverage against this run's commit and lets the seal clear the flag.
     *
     * <p>The flag is read from the suite metadata already loaded for this selection, so this adds
     * no query to the read path.
     *
     * @param testSuitesTracked the tracked test suites keyed by suite name
     * @param testsToRun the run set to add the unsealed suites to
     * @return the number of unsealed suites added to {@code testsToRun}
     */
    private int addUnsealedTests(final Map<String, TestSuiteTracker> testSuitesTracked,
                                  final Set<String> testsToRun){
        Set<String> unsealed = new HashSet<>();
        for (Map.Entry<String, TestSuiteTracker> entry : testSuitesTracked.entrySet()){
            if (entry.getValue().isUnsealed()){
                unsealed.add(entry.getKey());
            }
        }

        if (!unsealed.isEmpty()){
            log.info("Selected tests to run from unsealed mapping rows (a previous run did not complete): {}", unsealed);
            testsToRun.addAll(unsealed);
        }

        return unsealed.size();
    }

    /**
     * Run the targeted changed-files-to-tracked-methods read for a set of modified-source diffs: normalize each diff's
     * original (pre-change) file path to its stored mapping key, then query the tracked methods
     * (with line ranges) for those files in one {@link DataStore#getMethodsTrackedForFiles} call.
     * The original path is used because the stored mapping was built before the change.
     *
     * @param modifiedSourceDiffs the modified source-file diff contexts
     * @param sourceFilesDirs the configured source root directories (used for key normalization)
     * @return the tracked methods for the changed files, keyed by mapping key then method id
     */
    private Map<String, Map<Integer, MethodImpactTracker>> loadMethodsTrackedForDiffs(
            final List<SourceFileDiffContext> modifiedSourceDiffs, final List<String> sourceFilesDirs){
        Set<String> changedFileKeys = new HashSet<>();
        for (SourceFileDiffContext diff : modifiedSourceDiffs){
            changedFileKeys.add(SourceFilenameUtil.normalizeToMappingKey(diff.getOldFilePath(), sourceFilesDirs));
        }

        return dataStore.getMethodsTrackedForFiles(changedFileKeys);
    }

    /**
     * Filter modified-source diffs down to those tracked in the mapping - i.e. whose normalized
     * mapping key is present in the changed-files-to-tracked-methods result. Untracked changed files cannot select any
     * test, so they are dropped before the (potentially expensive) content fetch; each drop is
     * logged at debug. Uses the same {@link SourceFilenameUtil#normalizeToMappingKey} call as
     * {@link #loadMethodsTrackedForDiffs}, so the keys are guaranteed to line up.
     *
     * @param modifiedSourceDiffs the modified source-file diff contexts
     * @param methodsTrackedByFile the changed-files-to-tracked-methods result, keyed by stored mapping key
     * @param sourceFilesDirs the configured source root directories (used for key normalization)
     * @return the subset of diffs whose files are tracked in the mapping
     */
    private List<SourceFileDiffContext> filterToTrackedFiles(
            final List<SourceFileDiffContext> modifiedSourceDiffs,
            final Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
            final List<String> sourceFilesDirs){
        List<SourceFileDiffContext> tracked = new ArrayList<>();
        for (SourceFileDiffContext diff : modifiedSourceDiffs){
            String mappingKey = SourceFilenameUtil.normalizeToMappingKey(diff.getOldFilePath(), sourceFilesDirs);
            if (methodsTrackedByFile.containsKey(mappingKey)){
                tracked.add(diff);
            } else {
                log.debug("Skipping content fetch for changed source file not tracked in the mapping: {}",
                        diff.getOldFilePath());
            }
        }
        return tracked;
    }

    /**
     * In local-changes mode, find the tracked locally changed files whose stored method line
     * ranges can't be trusted because the file has drifted from the mapping: the version the local
     * edit is based on differs from the version at the commit the stored mapping was built at.
     * The workspace copy is either missing changes the mapping has (behind) or has changes the
     * mapping doesn't (ahead, e.g. synced past the mapping commit while the mapping job catches
     * up), or both. Tia matches a local diff to tracked methods by line number, and those line
     * numbers were recorded from the mapped version of the file, so they may not line up with the
     * workspace copy and the changed methods could be missed. When the stored commit isn't in the
     * local repository at all, the workspace is almost certainly behind it and the files can't be
     * checked, so every file is treated this way. Each affected file is logged at WARN with the
     * reason, because all of its tracked methods are treated as impacted and that widens the
     * selection.
     *
     * <p>See the "Local changes and a workspace out of step with the mapping" chapter in
     * {@code WIKI.md}.
     *
     * @param vcsReader the VCS reader used to look up the drift
     * @param storedCommitValue the commit the stored mapping was built at
     * @param trackedModifiedSourceDiffs the tracked modified source files from the local diff
     * @param methodsTrackedByFile the tracked methods for the changed files, keyed by mapping key
     * @param sourceFilesDirs the configured source root directories (used for key normalization)
     * @return the diffs whose tracked methods should all be treated as impacted; empty when none
     */
    private List<SourceFileDiffContext> findDriftedDiffs(
            final VCSReader vcsReader, final String storedCommitValue,
            final List<SourceFileDiffContext> trackedModifiedSourceDiffs,
            final Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
            final List<String> sourceFilesDirs){
        if (trackedModifiedSourceDiffs.isEmpty()){
            return Collections.emptyList();
        }

        MappingDrift drift = vcsReader.findMappingDrift(storedCommitValue, trackedModifiedSourceDiffs);
        List<SourceFileDiffContext> affected = new ArrayList<>();
        StringBuilder fileLines = new StringBuilder();
        boolean anyBehind = false;
        boolean anyAhead = false;
        for (SourceFileDiffContext diff : trackedModifiedSourceDiffs){
            MappingDrift.FileDrift fileDrift = drift.getDriftByFetchKey().get(diff.getVcsFetchKey());
            if (drift.isBaselineFound() && fileDrift == null){
                continue;
            }
            affected.add(diff);
            String mappingKey = SourceFilenameUtil.normalizeToMappingKey(diff.getOldFilePath(), sourceFilesDirs);
            fileLines.append(System.lineSeparator()).append("  ").append(mappingKey)
                    .append(" (").append(methodsTrackedByFile.get(mappingKey).size()).append(" tracked methods");
            if (fileDrift != null && !fileDrift.getChangesNotInWorkspace().isEmpty()){
                anyBehind = true;
                fileLines.append("; in the mapping but not your copy: ")
                        .append(String.join(", ", fileDrift.getChangesNotInWorkspace()));
            }
            if (fileDrift != null && !fileDrift.getChangesNotInMapping().isEmpty()){
                anyAhead = true;
                fileLines.append("; in your copy but not the mapping: ")
                        .append(String.join(", ", fileDrift.getChangesNotInMapping()));
            }
            fileLines.append(")");
        }

        if (affected.isEmpty()){
            return affected;
        }

        log.warn("Treating every tracked method in {} locally changed file(s) as impacted, so all of their tests"
                + " will run:{}{}Why: {} Tia finds the methods you changed by matching the line numbers of your"
                + " edits against the method line numbers stored in the mapping, and those were recorded from a"
                + " different version of each file, so they may not line up with your copy. Treating every method"
                + " in these files as impacted makes sure no affected test is skipped.{}",
                affected.size(), fileLines, System.lineSeparator(),
                driftCause(drift.isBaselineFound(), abbreviateCommit(storedCommitValue)),
                driftAdvice(anyBehind, anyAhead));
        return affected;
    }

    /**
     * Explain why the files in the drift WARN were treated as fully impacted.
     *
     * @param baselineFound whether the stored mapping commit is in the local repository
     * @param mappingCommit the stored mapping commit, as shown in the message
     * @return the cause sentence(s) for the WARN
     */
    private static String driftCause(final boolean baselineFound, final String mappingCommit){
        if (baselineFound){
            return "Your copy of these files isn't the version the Tia mapping was built at (" + mappingCommit + ").";
        }
        return "The commit the Tia mapping was built at (" + mappingCommit + ") isn't in your local repository,"
                + " so your workspace is probably behind it (fetch to bring it in) and Tia can't check which of"
                + " your changed files are affected.";
    }

    /**
     * Say how to get a precise selection back, for each direction the listed files drifted in.
     *
     * @param anyBehind whether any listed file is missing changes the mapping has
     * @param anyAhead whether any listed file has changes the mapping doesn't
     * @return the advice sentences for the end of the WARN, each with a leading space; empty when
     *         neither applies (the baseline was not found and the cause already says to fetch)
     */
    private static String driftAdvice(final boolean anyBehind, final boolean anyAhead){
        StringBuilder advice = new StringBuilder();
        if (anyBehind){
            advice.append(" For files with changes in the mapping but not your copy, sync to the latest and resolve"
                    + " them to get a precise selection.");
        }
        if (anyAhead){
            advice.append(" For files with changes in your copy but not the mapping, the selection becomes precise"
                    + " again once the mapping is rebuilt with those changes.");
        }
        return advice.toString();
    }

    /**
     * Shorten a commit value for a log message: a 40-character hex Git commit id is cut to its
     * first 8 characters, matching the abbreviated ids the Git reader reports per file, and any
     * other value, such as a Perforce changelist number, is returned unchanged.
     *
     * @param commitValue the stored commit value
     * @return the commit value to show in a log message
     */
    static String abbreviateCommit(final String commitValue){
        if (commitValue.length() == 40 && commitValue.chars().allMatch(c -> Character.digit(c, 16) >= 0)){
            return commitValue.substring(0, 8);
        }
        return commitValue;
    }

    /**
     * Collect the ids of every tracked method in the given diffs' files.
     *
     * @param diffs the changed-file diff contexts
     * @param methodsTrackedByFile the tracked methods for the changed files, keyed by mapping key
     * @param sourceFilesDirs the configured source root directories (used for key normalization)
     * @return the tracked method ids across the diffs' files
     */
    private Set<Integer> allTrackedMethodIds(final List<SourceFileDiffContext> diffs,
                                             final Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
                                             final List<String> sourceFilesDirs){
        Set<Integer> methodIds = new HashSet<>();
        for (SourceFileDiffContext diff : diffs){
            String mappingKey = SourceFilenameUtil.normalizeToMappingKey(diff.getOldFilePath(), sourceFilesDirs);
            methodIds.addAll(methodsTrackedByFile.get(mappingKey).keySet());
        }
        return methodIds;
    }

    /**
     * Fail with a clear error when a required directory list is not configured. Selection filters
     * the diff by the source and test directories, so without them it cannot work out what changed;
     * treating the list as empty would instead select nothing for every change. Only a selective run
     * reads them - a seed or forced run runs every test - so the check sits here rather than at the
     * start of {@link #selectTestsToIgnore}.
     *
     * @param dirNames the configured directories, {@code null} when the setting is absent
     * @param kind which directories they are, {@code source} or {@code test}, named in the error
     * @param mavenName the setting's name in the Maven plugin
     * @param gradleName the setting's name in the Gradle plugin
     * @throws IllegalStateException if {@code dirNames} is {@code null}
     */
    static void requireConfigured(final List<String> dirNames, final String kind, final String mavenName,
                                  final String gradleName) {
        if (dirNames == null) {
            throw new IllegalStateException("Tia cannot select tests: no " + kind + " files directories are "
                    + "configured. Set " + mavenName + " (Maven) or " + gradleName + " (Gradle), e.g. "
                    + "/src/" + (kind.equals("source") ? "main" : "test") + "/java.");
        }
    }

    /**
     * Get the full path names for a given list of directories. An entry is resolved against the
     * project directory first - a leading {@code /} is allowed, as the README's examples use - and
     * otherwise taken as an absolute path. Entries that exist neither way are logged and skipped.
     * Package-private so the resolution can be tested on its own.
     *
     * @param filePaths should be source code or test file directories configured by the user
     * @return the canonical paths of the directories that exist
     */
    List<String> getFullFilePaths(List<String> filePaths){
        List<String> fullFilePaths = new ArrayList<>();

        for (String sourceAndTestFilesDir : filePaths){
            File file = loadFileOnDiskFromPath(sourceAndTestFilesDir);
            if (file != null){
                try {
                    fullFilePaths.add(file.getCanonicalPath());
                } catch (IOException e) {
                    throw new VCSAnalyzerException(e);
                }
            }
        }

        return fullFilePaths;
    }

    /**
     * Find a configured directory on disk: under the project directory first, then as an absolute
     * path.
     *
     * @param sourceAndTestFilesDir the configured directory
     * @return the directory, or null when it exists neither way
     */
    private File loadFileOnDiskFromPath(String sourceAndTestFilesDir) {
        // first assume it's relative to the project directory and check if it exists
        File file = new File(projectDir, sourceAndTestFilesDir);
        if (!file.exists()){
            // not under the project: an absolute path, if it is one (a relative one is never resolved
            // against the working directory)
            File asConfigured = new File(sourceAndTestFilesDir);
            file = asConfigured.isAbsolute() && asConfigured.exists() ? asConfigured : null;
            if (file == null){
                log.warn("Can't find configured source or test directory on disk: {} (resolved against "
                        + "the project directory {})", sourceAndTestFilesDir, projectDir);
            }
        }

        return file;
    }

    /**
     * Add modified test suites to the run list. Only suites present in the tracked set are
     * added. Any other modified test file is a test support class - a helper, base class or
     * fixture the suites use - or a test Tia hasn't tracked, which runs anyway since it is in no
     * ignore list. Either way it is collected as a changed support class, so the suites that use it
     * are selected.
     *
     * @param sourceFileDiffContexts the modified test-file diff contexts
     * @param testSuitesTracked the tracked test suites keyed by suite name
     * @param testsToRun the run set to add the modified suites to
     * @param testFilesDirs the configured test file directories
     * @param changedSupportClasses accumulator for the modified test files that aren't tracked suites
     * @return the number of modified test suites added to {@code testsToRun}
     */
    private int addModifiedTestFilesToRunList(List<SourceFileDiffContext> sourceFileDiffContexts,
                                               Map<String, TestSuiteTracker> testSuitesTracked,
                                               Set<String> testsToRun, List<String> testFilesDirs,
                                               Set<String> changedSupportClasses){
        Set<String> testSuitesModified = new HashSet<>();

        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts){
            String testName = getTestNameFromFilePath(sourceFileDiffContext.getOldFilePath(), testFilesDirs);
            if (testSuitesTracked.containsKey(testName)){
                testSuitesModified.add(testName);
            } else {
                changedSupportClasses.add(testName);
            }
        }

        log.info("Selected tests to run from VCS test file changes: {}", testSuitesModified);
        testsToRun.addAll(testSuitesModified);
        return testSuitesModified.size();
    }

    /**
     * Add newly added test suites to the run list. New test files are always run - they have no
     * stored mapping yet - unless their compiled class proves they are not a test: an interface, an
     * abstract class, or a class with no test methods. Those are dropped, since on a distributed run
     * they would otherwise be assigned to a group that can never observe them, and they inflate the
     * selection log, the run-history breakdown and the run-time estimate on any run.
     *
     * <p>Only a class file that exists can prove that: a new file with no class file is kept, since
     * at selection time that usually means the tests are not compiled yet. Dropping a real test here
     * would be worse than keeping a helper - on a distributed run a new suite in no group is in no
     * runner's ignore list, so every runner would run it. See "Which suites a runner skips" in the
     * distributed test runs chapter of {@code WIKI.md}.
     *
     * <p>A dropped file is collected as a changed support class: a new abstract base class or helper
     * can be added in the same change as the existing suites that start using it.
     *
     * @param sourceFileDiffContexts the added test-file diff contexts
     * @param testsToRun the run set to add the new suites to
     * @param testFilesDirs the configured test file directories
     * @param runnabilityClassifier classifies the new suites against the compiled test classes
     * @param changedSupportClasses accumulator for the new test files proven not to be a test
     * @return the number of new test suites added to {@code testsToRun}
     */
    private int addNewTestFilesToRunList(List<SourceFileDiffContext> sourceFileDiffContexts,
                                          Set<String> testsToRun, List<String> testFilesDirs,
                                          TestSuiteRunnabilityClassifier runnabilityClassifier,
                                          Set<String> changedSupportClasses){
        Set<String> testSuitesAdded = new HashSet<>();
        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts){
            String testName = getTestNameFromFilePath(sourceFileDiffContext.getNewFilePath(), testFilesDirs);
            TestSuiteRunnabilityClassifier.Verdict verdict = runnabilityClassifier.classify(testName);
            if (verdict.isProvenNotATest()) {
                log.debug("New test file {} skipped: not a runnable test suite ({})", testName,
                        verdict.getReason());
                changedSupportClasses.add(testName);
                continue;
            }
            testSuitesAdded.add(testName);
        }

        log.info("Selected tests to run from new test files: {}", testSuitesAdded);
        testsToRun.addAll(testSuitesAdded);
        return testSuitesAdded.size();
    }

    /**
     * Collect the deleted test files that weren't tracked suites as changed support classes, by
     * their old class name. The suites that used a deleted helper still refer to it in their
     * sources until they are changed too, and if they no longer compile the build fails anyway.
     * A deleted tracked suite is left out: it no longer runs, and its removal from the mapping is
     * handled when the run's results are persisted.
     *
     * @param sourceFileDiffContexts the deleted test-file diff contexts
     * @param testSuitesTracked the tracked test suites keyed by suite name
     * @param testFilesDirs the configured test file directories
     * @param changedSupportClasses accumulator for the deleted test files that weren't tracked suites
     */
    private void collectDeletedSupportClasses(List<SourceFileDiffContext> sourceFileDiffContexts,
                                              Map<String, TestSuiteTracker> testSuitesTracked,
                                              List<String> testFilesDirs, Set<String> changedSupportClasses){
        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts){
            String testName = getTestNameFromFilePath(sourceFileDiffContext.getOldFilePath(), testFilesDirs);
            if (!testSuitesTracked.containsKey(testName)){
                changedSupportClasses.add(testName);
            }
        }
    }

    /**
     * Add the tracked suites that use a changed test support class - a base class, helper, fixture
     * or API client under a test dir - directly or through other test classes. The coverage mapping
     * can't see these, since test code is never in it. The test sources are only read when at least
     * one support class changed, so builds that change only suites or source code pay nothing. See
     * {@link TestSourceDependencyIndex} for how a dependency is found, and the "Changed test support
     * files" chapter of {@code WIKI.md}.
     *
     * <p>Logs the suites selected at INFO, the suite count per changed class at DEBUG, and at INFO
     * the changed classes no tracked suite uses, so no changed test file is dropped silently.
     *
     * @param changedSupportClasses the changed test files that aren't tracked suites, as class names
     * @param testFilesDirs the configured test file directories, as full paths
     * @param testSuitesTracked the tracked test suites keyed by suite name
     * @param testsToRun the run set to add the dependant suites to
     * @return the number of suites that use a changed support class
     */
    private int addTestSupportDependantsToRunList(Set<String> changedSupportClasses, List<String> testFilesDirs,
                                                  Map<String, TestSuiteTracker> testSuitesTracked,
                                                  Set<String> testsToRun){
        if (changedSupportClasses.isEmpty()) {
            return 0;
        }

        TestSourceDependencyIndex index = TestSourceDependencyIndex.build(testFilesDirs, changedSupportClasses,
                testSuitesTracked.keySet());
        Set<String> dependants = new HashSet<>();
        List<String> unused = new ArrayList<>();
        for (String changedSupportClass : changedSupportClasses) {
            Set<String> suites = index.dependantSuites(changedSupportClass);
            log.debug("Changed test support file {} is used by {} suite(s)", changedSupportClass, suites.size());
            if (suites.isEmpty()) {
                unused.add(changedSupportClass);
            }
            dependants.addAll(suites);
        }

        log.info("Selected {} test(s) to run from changed test support files: {}", dependants.size(),
                StringUtil.sampleSorted(dependants, StringUtil.LOG_SAMPLE_SIZE));
        if (!unused.isEmpty()) {
            log.info("Changed test file(s) not used by any tracked suite: {}", unused);
        }
        testsToRun.addAll(dependants);
        return dependants.size();
    }

    /**
     * Convert a test filename from a diff context to a full class name if it exists in the known tracked test suites.
     * For example:
     * Diff filename: /usr/project/src/test/groovy/com/example/DoorServiceSpec.groovy
     * Converts to: com.example.DoorServiceSpec
     *
     * @param testFilePath
     * @param testFilesDirs
     * @return
     */
    private String getTestNameFromFilePath(String testFilePath, List<String> testFilesDirs){
        testFilePath = testFilePath.replaceAll("\\." + JAVA_FILE_EXT, "");
        testFilePath = testFilePath.replaceAll("\\." + GROOVY_FILE_EXT, "");

        for(String testFilesDir : testFilesDirs){
            if (testFilePath.startsWith(testFilesDir)){
                testFilePath = testFilePath.replace(testFilesDir, "");
                break;
            }
        }
        testFilePath = testFilePath.replaceAll("\\\\", "/"); // if on Windows, change back slash to forward slash
        testFilePath = testFilePath.startsWith("/") ? testFilePath.substring(1) : testFilePath; // trim leading /
        testFilePath = testFilePath.replaceAll("\\/", ".");

        return testFilePath;
    }

    /**
     * For the source files that have changed, do a diff to find the methods that have changed.
     *
     * @param sourceFileDiffContexts the changed-file diff contexts to analyze
     * @param methodsTrackedByFile the changed-files-to-tracked-methods result: tracked methods for the changed files,
     *                             keyed by mapping key then method id
     * @param sourceFilesDirs the configured source root directories
     * @return set of method (hashcodes) that are impacted by the diff changes
     */
    private Set<Integer> findMethodsImpacted(List<SourceFileDiffContext> sourceFileDiffContexts,
                                             Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
                                             List<String> sourceFilesDirs){
        return fileImpactAnalyzer.getMethodsForFilesChanged(sourceFileDiffContexts, methodsTrackedByFile, sourceFilesDirs);
    }

    /**
     * Build the list of test suites that need to be run based on the tracked methods that have
     * been changed, using the targeted methods-to-covering-suites query
     * ({@link DataStore#getTestSuitesForMethods}) instead of an in-memory reverse index over
     * the full mapping.
     *
     * @param methodsImpacted the set of method ids that the diff implicates
     * @param methodsTrackedByFile the changed-files-to-tracked-methods result, used to resolve method names for debug logging
     * @return the tests that should be executed based on the methods changed in the source code,
     *         together with one {@link TestRunTrigger} per impacted method carrying its
     *         covering-suite count, for the run-history selection breakdown
     */
    private SourceMethodTriggerResult findTestSuitesForImpactedMethods(Set<Integer> methodsImpacted,
                                                         Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile){
        Map<Integer, Set<String>> methodTestSuites = dataStore.getTestSuitesForMethods(methodsImpacted);

        Set<String> testsToRun = new HashSet<>();
        List<TestRunTrigger> triggers = new ArrayList<>();
        for (Map.Entry<Integer, Set<String>> entry : methodTestSuites.entrySet()){
            if (log.isDebugEnabled()){
                log.debug("Tests to run ({}) for method {}: {}", entry.getValue().size(),
                        methodNameForId(entry.getKey(), methodsTrackedByFile), entry.getValue());
            }
            testsToRun.addAll(entry.getValue());
            triggers.add(new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD,
                    methodNameForId(entry.getKey(), methodsTrackedByFile), entry.getKey(),
                    entry.getValue().size()));
        }

        log.info("Selected tests to run from VCS source changes: {}", testsToRun);
        return new SourceMethodTriggerResult(testsToRun, triggers);
    }

    /**
     * Holder for the result of {@link #findTestSuitesForImpactedMethods}: the run set together
     * with one {@link TestRunTrigger} per impacted method, for the run-history
     * {@link TestRunSelectionDetails} breakdown. Package-private, like {@link RunTimeEstimate},
     * so tests can assert on the triggers without going through the full
     * {@code selectTestsToIgnore} entry point.
     */
    static class SourceMethodTriggerResult {
        private final Set<String> testsToRun;
        private final List<TestRunTrigger> triggers;

        /**
         * @param testsToRun the test suites selected to run from the impacted methods
         * @param triggers one trigger per impacted method, carrying its covering-suite count
         */
        SourceMethodTriggerResult(Set<String> testsToRun, List<TestRunTrigger> triggers) {
            this.testsToRun = testsToRun;
            this.triggers = triggers;
        }

        /** @return the test suites selected to run from the impacted methods */
        Set<String> getTestsToRun() { return testsToRun; }

        /** @return one trigger per impacted method, carrying its covering-suite count */
        List<TestRunTrigger> getTriggers() { return triggers; }
    }

    /**
     * Resolve a method id to its display name from the changed-files-to-tracked-methods per-file result map. Debug
     * logging only - the linear scan across the diff's files is acceptable there.
     *
     * @param methodId the tracked method id to resolve
     * @param methodsTrackedByFile the tracked methods for the changed files, keyed by filename
     * @return the method name, or the id itself when not found
     */
    private static String methodNameForId(Integer methodId,
                                          Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile){
        for (Map<Integer, MethodImpactTracker> fileMethods : methodsTrackedByFile.values()){
            MethodImpactTracker tracker = fileMethods.get(methodId);
            if (tracker != null){
                return tracker.getMethodName();
            }
        }
        return String.valueOf(methodId);
    }

    /**
     * Add the tests that failed on the previous run - force them to be re-run. The failed
     * set is read directly from the data store (it is small and changes every run, so it is
     * not part of the up-front metadata load).
     *
     * @param testsToRun the run set to add the previously failed tests to
     * @return the number of previously-failed suites added to {@code testsToRun}
     */
    private int addPreviouslyFailedTests(Set<String> testsToRun){
        Set<String> testSuitesFailed = dataStore.getTestSuitesFailed();
        testsToRun.addAll(testSuitesFailed);
        log.info("Running previously failed tests: {}", testSuitesFailed);
        return testSuitesFailed.size();
    }

    /**
     * Find the list of all known tracked test suites that are in the list of tests to run. This is the ignore list.
     * i.e. only ignore test suites that we have previously tracked and haven't been impacted by the source changes.
     * This ensures any new test suites are executed.
     *
     * <p>Suites flagged developer-disabled are excluded from the ignore set: the developer
     * disabled them in source, so they would not run even without Tia, and Tia saves no time by
     * skipping them. Excluding them keeps the Tia-ignored count a true count of suites Tia chose
     * to skip that could otherwise have run.
     *
     * @param testSuitesTracked the tracked test suites keyed by suite name
     * @param testsToRun the test suites selected to run
     * @return the tracked, non-developer-disabled suites not selected to run - the ignore list
     */
    static Set<String> getTestsToIgnore(Map<String, TestSuiteTracker> testSuitesTracked, Set<String> testsToRun){
        Set<String> testsToIgnore = new HashSet<>();

        testSuitesTracked.forEach( (testSuite, tracker) -> {
            if (!testsToRun.contains(testSuite) && !tracker.isDeveloperDisabled()){
                testsToIgnore.add(testSuite);
            }
        });
        return testsToIgnore;
    }

    /**
     * Apply static test selection rules. When {@code staticMappingConfig} is non-null and
     * enabled, fetch the changed file paths from the VCS reader, run each rule's eager
     * empty-resolution sanity check (logs a WARN per rule that resolves to zero suites in
     * the current Tia data snapshot), and union the resolver's forced suites into
     * {@code testsToRun}. Static rules are additive only: they can add suites to the run set
     * but never remove them.
     *
     * @param vcsReader the VCS reader used to fetch changed file paths.
     * @param staticMappingConfig the static test selection config; may be {@code null}.
     * @param storedCommitValue the commit the stored mapping was built at; the baseline for
     *                          the changed-paths query.
     * @param testSuitesTracked the tracked test suites (names + stats) keyed by suite name,
     *                          used to resolve each rule's forced suite set.
     * @param testsToRun the dynamic run set; forced suites are added in place.
     * @param checkLocalChanges whether to query the local workspace instead of the commit range.
     * @param libraryConfig the library impact analysis config; its tracked-library coordinates
     *                      make those libraries' version changes inert for POM-aware rules. May be
     *                      {@code null}.
     * @return one {@link TestRunTrigger} per fired rule, for the run-history selection breakdown;
     *         empty when no rule fired or {@code staticMappingConfig} is null/disabled.
     */
    private List<TestRunTrigger> applyStaticTestSelection(final VCSReader vcsReader,
                                          final StaticTestSelectionConfig staticMappingConfig,
                                          final String storedCommitValue,
                                          final Map<String, TestSuiteTracker> testSuitesTracked,
                                          final Set<String> testsToRun,
                                          final boolean checkLocalChanges,
                                          final LibraryImpactAnalysisConfig libraryConfig) {
        if (staticMappingConfig == null || !staticMappingConfig.isEnabled()) {
            return Collections.emptyList();
        }

        StaticTestSelectionResolver resolver = new StaticTestSelectionResolver(staticMappingConfig);
        resolver.warnOnEmptyRules(testSuitesTracked);

        Set<String> changedPaths = vcsReader.getChangedFilePaths(storedCommitValue, checkLocalChanges);
        // POM content is fetched lazily by the analyzer, only if a POM-aware rule matches a changed path.
        List<String> trackedLibraries = libraryConfig != null && libraryConfig.isEnabled()
                ? libraryConfig.getCoordinates() : Collections.<String>emptyList();
        PomChangeAnalyzer pomAnalyzer = new PomChangeAnalyzer(vcsReader, storedCommitValue, checkLocalChanges, changedPaths,
                new PomChangeClassifier(trackedLibraries), staticMappingConfig.getExternalPomSource());
        StaticTestSelectionResult staticResult = resolver.resolve(changedPaths, testSuitesTracked, pomAnalyzer);
        Set<String> forced = staticResult.getForcedSuites();
        // Always log the static selection outcome when rules are configured - an empty result
        // is as informative as a hit, and this matches the unconditional logging of the other
        // "Selected tests to run from ..." selection sources above.
        log.info("Selected tests to run from static test selection rules: {}", forced);
        testsToRun.addAll(forced);
        return staticResult.getRuleTriggers();
    }

    /**
     * If library impact analysis is configured, reconcile the declared libraries against
     * the persisted tracked-library rows so that additions, removals, and config changes
     * are reflected before test selection proceeds.
     */
    private void reconcileTrackedLibrariesIfConfigured(LibraryImpactAnalysisConfig libraryConfig) {
        if (libraryConfig == null || !libraryConfig.isEnabled()) {
            return;
        }

        log.info("Reconciling tracked libraries against tiaSourceLibs configuration.");
        TrackedLibraryReconciler reconciler = new TrackedLibraryReconciler();
        reconciler.reconcile(dataStore, libraryConfig);
    }

    /**
     * Decide whether the tracked libraries' source dirs are added to the dirs the app run diffs.
     * Only local-changes mode consumes library diffs (they feed direct selection there); on any
     * other run the library-diff partition would discard them, so they are not diffed at all.
     *
     * @param libraryConfig the library impact analysis config, or {@code null} if not configured
     * @param checkLocalChanges whether this run analyzes local workspace changes
     * @return true when library impact analysis is enabled and the run is in local-changes mode
     */
    private boolean shouldDiffTrackedLibraryDirs(LibraryImpactAnalysisConfig libraryConfig,
                                                 boolean checkLocalChanges) {
        return checkLocalChanges && libraryConfig != null && libraryConfig.isEnabled();
    }

    /**
     * Collect the source directories of every tracked library, falling back to the
     * library's project directory when no source dirs are recorded. Used in local-changes mode to
     * augment the source-file dirs passed to the VCS analyzers and the method impact analyzer.
     *
     * @return the tracked libraries' source dirs (or project dirs when none are recorded)
     */
    private List<String> collectTrackedLibraryDirs() {
        List<String> libDirs = new ArrayList<>();
        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        for (TrackedLibrary lib : trackedLibraries.values()) {
            if (lib.getSourceDirsCsv() != null && !lib.getSourceDirsCsv().isEmpty()) {
                for (String srcDir : lib.getSourceDirsCsv().split(",")) {
                    String trimmed = srcDir.trim();
                    if (!trimmed.isEmpty()) {
                        libDirs.add(trimmed);
                    }
                }
            } else if (lib.getProjectDir() != null && !lib.getProjectDir().isEmpty()) {
                libDirs.add(lib.getProjectDir());
            }
        }
        return libDirs;
    }

    /**
     * Determine whether library diff partitioning should be performed.
     * Partitioning is skipped in local-changes mode (all diffs run immediately)
     * and when no library config is present.
     */
    private boolean shouldPartitionLibraryDiffs(LibraryImpactAnalysisConfig libraryConfig, boolean checkLocalChanges) {
        if (checkLocalChanges) {
            return false;
        }
        return libraryConfig != null && libraryConfig.isEnabled();
    }

    /**
     * Partition modified source diffs into source-project and per-library buckets, returning the
     * source-project diffs. The tracked library dirs are not diffed outside local-changes mode, so
     * library diffs only reach here when the configured source dirs themselves cover a tracked
     * library. Library-owned diffs are deliberately excluded from source selection -
     * a library change is not a source-project change - and, under publish-time stamping, the app
     * run does not stamp them either: the library's own publish records the impacted methods, and
     * the drain picks them up once the app resolves a build that contains them.
     * See the publish-stamp-task section of the library publish-time stamping chapter in {@code WIKI.md}.
     *
     * @param allModifiedSourceDiffs all modified source-file diff contexts for this run
     * @return the source-project diffs (those not belonging to any tracked library).
     */
    private List<SourceFileDiffContext> partitionOutLibraryDiffs(
            List<SourceFileDiffContext> allModifiedSourceDiffs) {

        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        if (trackedLibraries.isEmpty()) {
            return allModifiedSourceDiffs;
        }

        List<SourceFileDiffContext> sourceProjectDiffs = new ArrayList<>();
        Map<String, List<SourceFileDiffContext>> libraryDiffBuckets = new HashMap<>();

        partitionDiffsByLibraryProjectDir(allModifiedSourceDiffs, trackedLibraries,
                sourceProjectDiffs, libraryDiffBuckets);

        for (Map.Entry<String, List<SourceFileDiffContext>> entry : libraryDiffBuckets.entrySet()) {
            log.info("Library '{}' has {} modified source files in range - excluded from source "
                    + "selection (library changes are stamped at publish time).",
                    entry.getKey(), entry.getValue().size());
        }

        return sourceProjectDiffs;
    }

    /**
     * Walk each modified source diff and assign it to a library bucket if its file path
     * falls under a tracked library's project directory. Otherwise assign it to the
     * source-project bucket.
     */
    private void partitionDiffsByLibraryProjectDir(
            List<SourceFileDiffContext> diffs, Map<String, TrackedLibrary> trackedLibraries,
            List<SourceFileDiffContext> sourceProjectDiffs,
            Map<String, List<SourceFileDiffContext>> libraryDiffBuckets) {

        for (SourceFileDiffContext diff : diffs) {
            String diffPath = diff.getNewFilePath() != null ? diff.getNewFilePath() : diff.getOldFilePath();
            String matchedLibrary = findLibraryForDiffPath(diffPath, trackedLibraries);

            if (matchedLibrary != null) {
                libraryDiffBuckets.computeIfAbsent(matchedLibrary, k -> new ArrayList<>()).add(diff);
            } else {
                sourceProjectDiffs.add(diff);
            }
        }
    }

    /**
     * Find which tracked library (if any) owns a given diff file path. When source directories
     * are available, matches against those for precision; otherwise falls back to the project directory.
     *
     * @return the {@code groupArtifact} key of the matching library, or {@code null}.
     */
    private String findLibraryForDiffPath(String diffPath, Map<String, TrackedLibrary> trackedLibraries) {
        for (TrackedLibrary lib : trackedLibraries.values()) {
            if (lib.getProjectDir() == null) {
                continue;
            }
            if (lib.getSourceDirsCsv() != null && !lib.getSourceDirsCsv().isEmpty()) {
                for (String srcDir : lib.getSourceDirsCsv().split(",")) {
                    if (diffPath.startsWith(srcDir.trim())) {
                        return lib.getGroupArtifact();
                    }
                }
            } else if (diffPath.startsWith(lib.getProjectDir())) {
                return lib.getGroupArtifact();
            }
        }
        return null;
    }

    /**
     * Drain pending library impacted-method stamps when library impact analysis is configured.
     * The drain is identical for every run mode - primary, preview and local-changes - because it
     * is a read-only evaluation of the persisted stamps against the build the app actually
     * resolved: it can only select tests for changes that build provably contains, so running it
     * on a local dev machine is safe and desirable (a published library change the dev's
     * classpath picked up should be tested locally too). The returned drain result only takes
     * effect on a primary run: {@code TestRunnerService} applies the cleanup under its own
     * {@code updateDBMapping} gate, and preview/local flows never reach the persist phase.
     *
     * <p>Local UNPUBLISHED library edits are handled separately: in {@code checkLocalChanges}
     * mode the library-diff partition is bypassed, so those diffs feed direct source selection -
     * the in-memory equivalent of a stamp+drain, with no version identity needed.
     *
     * @param libraryConfig the library impact analysis configuration, or null when not configured
     * @param testsToRun the run set to add drained tests to
     * @param testSuitesTracked the consumer's currently tracked test suites, forwarded to the
     *                          drainer so drained forced-selection batches resolve
     *                          {@code RUN_ALL} / {@code SUITE_NAMES} against this project's own
     *                          suite set rather than the library's.
     * @return the drain result for post-run cleanup (null when draining was skipped) together
     *         with the count of suites selected from pending library changes, for the
     *         run-history selection breakdown
     */
    private PendingLibrarySelection drainPendingLibraryMethodsIfConfigured(
            LibraryImpactAnalysisConfig libraryConfig, Set<String> testsToRun,
            Map<String, TestSuiteTracker> testSuitesTracked) {

        if (libraryConfig == null || !libraryConfig.isEnabled()) {
            return new PendingLibrarySelection(null, 0);
        }

        PendingLibraryImpactedMethodsDrainer drainer = new PendingLibraryImpactedMethodsDrainer();
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, libraryConfig, testSuitesTracked);

        if (!outcome.getTestsToAdd().isEmpty()) {
            log.info("Selected tests to run from pending library changes: {}", outcome.getTestsToAdd());
            testsToRun.addAll(outcome.getTestsToAdd());
        }

        return new PendingLibrarySelection(outcome.getDrainResult(), outcome.getTestsToAdd().size());
    }

    /**
     * Holder for the result of {@link #drainPendingLibraryMethodsIfConfigured}: the drain result
     * for post-run cleanup together with the count of suites selected from pending library
     * changes, for the run-history {@link TestRunSelectionDetails} breakdown. Package-private,
     * like {@link RunTimeEstimate}, so tests can assert on the count without going through the
     * full {@code selectTestsToIgnore} entry point.
     */
    static class PendingLibrarySelection {
        private final LibraryImpactDrainResult drainResult;
        private final int numPendingLibrary;

        /**
         * @param drainResult the drain result for post-run cleanup, or null when draining was skipped
         * @param numPendingLibrary the number of suites selected from pending library changes
         */
        PendingLibrarySelection(LibraryImpactDrainResult drainResult, int numPendingLibrary) {
            this.drainResult = drainResult;
            this.numPendingLibrary = numPendingLibrary;
        }

        /** @return the drain result for post-run cleanup, or null when draining was skipped */
        LibraryImpactDrainResult getDrainResult() { return drainResult; }

        /** @return the number of suites selected from pending library changes */
        int getNumPendingLibrary() { return numPendingLibrary; }
    }
}
