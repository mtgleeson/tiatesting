package org.tiatesting.core.diff.diffanalyze.selector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.diffanalyze.FileImpactAnalyzer;
import org.tiatesting.core.diff.diffanalyze.MethodImpactAnalyzer;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.library.PendingLibraryImpactedMethodsDrainer;
import org.tiatesting.core.library.PendingLibraryImpactedMethodsRecorder;
import org.tiatesting.core.library.TrackedLibraryReconciler;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.sourcefile.SourceFilenameUtil;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionResolver;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

import static org.tiatesting.core.sourcefile.FileExtensions.JAVA_FILE_EXT;
import static org.tiatesting.core.sourcefile.FileExtensions.GROOVY_FILE_EXT;

public class TestSelector {

    private static final Logger log = LoggerFactory.getLogger(TestSelector.class);

    private final DataStore dataStore;

    FileImpactAnalyzer fileImpactAnalyzer = new FileImpactAnalyzer(new MethodImpactAnalyzer());

    public TestSelector (final DataStore dataStore){
        this.dataStore = dataStore;
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
     * @param checkLocalChanges should local changes be checked by Tia.
     * @param libraryConfig the library impact analysis config, or {@code null} if not configured.
     * @param staticMappingConfig the static test selection config, or {@code null} if not configured.
     *                            When enabled, each rule's file-path regex is matched against the
     *                            changed file paths from the VCS reader, and any forced suites are
     *                            unioned into the dynamic test selection.
     * @param updateDBMapping whether this run owns mapping-DB updates. When {@code false} (non-primary
     *                       build / local workspace), test selection still runs but no mapping/library
     *                       writes are performed: tracked-library reconcile is skipped and pending
     *                       library impacted methods are not stamped. Stats writes are independent
     *                       and not affected by this flag.
     * @return list of test suites to ignore in the current test run.
     */
    public TestSelectorResult selectTestsToIgnore(final VCSReader vcsReader, final List<String> sourceFilesDirNames,
                                           final List<String> testFilesDirNames, final boolean checkLocalChanges,
                                           final LibraryImpactAnalysisConfig libraryConfig,
                                           final StaticTestSelectionConfig staticMappingConfig,
                                           final boolean updateDBMapping){
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
                    tiaCore.getTestStats().getAllTestsRunTime(), 0L);
        }

        // Suite names + stats only (no coverage edges): serves the modified-test-file check,
        // the ignore list and the run-time estimate.
        Map<String, TestSuiteTracker> testSuitesTracked = dataStore.getTestSuitesTracked();

        // Populated only on a preview build (updateDBMapping=false): the in-memory pending batches
        // built from library diffs in this run, keyed by library coordinate. Fed to the drain preview
        // below so first-seen library changes are reflected in the selected tests and the estimate.
        Map<String, List<PendingLibraryImpactedMethod>> syntheticLibraryBatches = new HashMap<>();

        Set<String> testsToRun = selectTestsToRun(vcsReader, sourceFilesDirNames, testFilesDirNames, checkLocalChanges,
                tiaCore.getCommitValue(), testSuitesTracked, libraryConfig, updateDBMapping, syntheticLibraryBatches);

        LibraryImpactDrainResult drainResult = drainPendingLibraryMethodsIfConfigured(
                libraryConfig, checkLocalChanges, updateDBMapping, testsToRun, syntheticLibraryBatches);

        applyStaticTestSelection(vcsReader, staticMappingConfig, tiaCore.getCommitValue(), testSuitesTracked,
                testsToRun, checkLocalChanges);

        // Get the list of tests from the stored mapping that aren't in the list of test suites to run.
        Set<String> testsToIgnore = getTestsToIgnore(testSuitesTracked, testsToRun);

        log.debug("Ignoring tests: {}", testsToIgnore);

        RunTimeEstimate estimate = estimateRunTime(testsToRun, testSuitesTracked,
                tiaCore.getTestStats().getAllTestsRunTime());
        return new TestSelectorResult(testsToRun, testsToIgnore, drainResult,
                estimate.getEstimatedRunTimeMs(), estimate.getSelectedTestsWithoutStats(),
                estimate.getMedianRunTimeMsAppliedToMissing(),
                estimate.getSelectedTestRunTimesMs(),
                tiaCore.getTestStats().getAllTestsRunTime(), estimate.getMappingOverheadMs());
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
     * <p>The base estimate above is pure per-suite execution time. A mapping-update run also pays
     * per-suite JaCoCo coverage capture plus other whole-run costs (JVM/agent startup, the final
     * persist), none of which is in {@code avgRunTime} (that is measured before coverage
     * collection). That amount is returned separately as
     * {@link RunTimeEstimate#getMappingOverheadMs()} - see {@link #computeOverheadPerSuiteMs} -
     * so the caller can add it to the base only when the run being estimated collects coverage.
     *
     * @param testsToRun the names of the selected test suites
     * @param tracked the tracked test suites (names + stats) keyed by suite name
     * @param allTestsRunTimeMs the recorded full-suite run time (ms); the basis for the overhead
     * @return a {@link RunTimeEstimate} carrying the base estimate, the names of selected tests
     *         with no stats, the median applied to those tests, and the mapping overhead
     */
    static RunTimeEstimate estimateRunTime(final Set<String> testsToRun, final Map<String, TestSuiteTracker> tracked,
                                           final long allTestsRunTimeMs){
        long totalMs = 0L;
        Set<String> withoutStats = new HashSet<>();
        Map<String, Long> perTestRunTimes = new HashMap<>();

        for (String testName : testsToRun){
            TestSuiteTracker tracker = tracked.get(testName);
            if (tracker == null){
                withoutStats.add(testName);
                perTestRunTimes.put(testName, 0L); // placeholder, replaced with median below
            } else {
                long avg = tracker.getTestStats().getAvgRunTime();
                totalMs += avg;
                perTestRunTimes.put(testName, avg);
            }
        }

        long median = 0L;
        if (!withoutStats.isEmpty()){
            median = computeMedianAvgRunTime(tracked);
            totalMs += median * (long) withoutStats.size();
            for (String testName : withoutStats){
                perTestRunTimes.put(testName, median);
            }
        }

        long mappingOverheadMs = computeOverheadPerSuiteMs(tracked, allTestsRunTimeMs) * (long) testsToRun.size();

        return new RunTimeEstimate(totalMs, withoutStats, median, perTestRunTimes, mappingOverheadMs);
    }

    /**
     * Derive the per-suite overhead a mapping-update run pays beyond pure test execution: the
     * recorded full-suite run time minus the sum of every tracked suite's {@code avgRunTime},
     * amortised across the tracked suites. The difference captures JaCoCo coverage capture plus
     * the whole-run fixed costs (JVM/agent startup, the final persist) that no per-suite
     * {@code avgRunTime} includes.
     *
     * <p>Returns {@code 0} when there is no baseline, no tracked suites, or the baseline is below
     * the per-suite sum. The last case means the build ran suites in parallel (wall clock less
     * than the serial sum); this heuristic only models sequential builds, so it clamps rather
     * than subtracting.
     *
     * @param tracked the tracked test suites (names + stats) keyed by suite name
     * @param allTestsRunTimeMs the recorded full-suite run time (ms)
     * @return the amortised overhead per suite (ms), or {@code 0} when it can't be derived
     */
    private static long computeOverheadPerSuiteMs(final Map<String, TestSuiteTracker> tracked, final long allTestsRunTimeMs){
        if (allTestsRunTimeMs <= 0 || tracked.isEmpty()){
            return 0L;
        }
        long sumAvg = 0L;
        for (TestSuiteTracker tracker : tracked.values()){
            sumAvg += tracker.getTestStats().getAvgRunTime();
        }
        long overhead = allTestsRunTimeMs - sumAvg;
        if (overhead <= 0){
            return 0L;
        }
        return overhead / tracked.size();
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
        private final long mappingOverheadMs;

        /**
         * @param estimatedRunTimeMs base estimated runtime (ms) for the selected tests - the sum
         *                           of per-suite times including the median fallback, with no
         *                           mapping overhead added
         * @param selectedTestsWithoutStats names of selected tests with no recorded stats
         * @param medianRunTimeMsAppliedToMissing median {@code avgRunTime} (ms) applied to
         *                                        each test in {@code selectedTestsWithoutStats},
         *                                        or {@code 0} when no fallback was applied
         * @param selectedTestRunTimesMs per-test runtime (ms) keyed by test suite name; covers
         *                               every entry in {@code testsToRun}, with the median
         *                               value (or {@code 0} when no median is available) used
         *                               for tests without stats
         * @param mappingOverheadMs the additional time (ms) a mapping-update run would pay for the
         *                          selected suites (coverage capture + amortised whole-run costs);
         *                          callers add it to {@code estimatedRunTimeMs} only when the run
         *                          being estimated collects coverage
         */
        RunTimeEstimate(long estimatedRunTimeMs, Set<String> selectedTestsWithoutStats,
                        long medianRunTimeMsAppliedToMissing,
                        Map<String, Long> selectedTestRunTimesMs, long mappingOverheadMs) {
            this.estimatedRunTimeMs = estimatedRunTimeMs;
            this.selectedTestsWithoutStats = selectedTestsWithoutStats;
            this.medianRunTimeMsAppliedToMissing = medianRunTimeMsAppliedToMissing;
            this.selectedTestRunTimesMs = selectedTestRunTimesMs;
            this.mappingOverheadMs = mappingOverheadMs;
        }

        /** @return the base estimated runtime (ms) for the selected tests, without mapping overhead */
        long getEstimatedRunTimeMs() { return estimatedRunTimeMs; }

        /** @return names of selected tests with no recorded run-time stats */
        Set<String> getSelectedTestsWithoutStats() { return selectedTestsWithoutStats; }

        /** @return median {@code avgRunTime} (ms) substituted for tests without stats */
        long getMedianRunTimeMsAppliedToMissing() { return medianRunTimeMsAppliedToMissing; }

        /** @return per-test runtime (ms) keyed by test suite name */
        Map<String, Long> getSelectedTestRunTimesMs() { return selectedTestRunTimesMs; }

        /** @return the mapping overhead (ms) for the selected suites; added only for coverage runs */
        long getMappingOverheadMs() { return mappingOverheadMs; }
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
     * enabled, modified source diffs are partitioned into source-project vs per-library buckets.
     * Library-bucket methods are stamped as pending (not added to the run set in this stage).
     * In {@code checkLocalChanges} mode, library diff partitioning is bypassed entirely — all
     * diffs are treated as source-project diffs so tests run immediately against local changes.
     *
     * <p>When {@code updateDBMapping} is {@code false} (a preview build such as {@code select-tests}),
     * library-diff partitioning still runs (so that library-owned diffs are not misclassified as
     * source-project diffs) and the impacted methods are still analyzed, but instead of persisting a
     * pending stamp the batch is built in memory and collected into {@code syntheticLibraryBatchesOut}
     * for the drain preview. No pending rows are written and no tracked-library state is advanced.
     *
     * <p>Mapping reads are targeted to the diff: one changed-files-to-tracked-methods query
     * ({@link DataStore#getMethodsTrackedForFiles}) resolves the changed files to their tracked
     * methods (shared by the source-impact and library-stamp paths), and one
     * methods-to-covering-suites query ({@link DataStore#getTestSuitesForMethods}) resolves the
     * impacted methods to suites.
     *
     * @param vcsReader the VCS reader used to build the diff contexts
     * @param sourceFilesDirNames the dir names for the source files
     * @param testFilesDirNames the dir names for the test files
     * @param checkLocalChanges should local changes be checked by Tia
     * @param storedCommitValue the commit the stored mapping was built at (diff baseline)
     * @param testSuitesTracked the tracked test suites (names + stats) keyed by suite name
     * @param libraryConfig the library impact analysis config, or {@code null} if not configured
     * @param updateDBMapping whether this run owns mapping-DB updates
     * @param syntheticLibraryBatchesOut out-param populated only on a preview build
     *                                   ({@code updateDBMapping == false}) with the in-memory pending
     *                                   batches built from this run's library diffs, keyed by library
     *                                   coordinate; left empty on a primary build (which persists them
     *                                   instead)
     * @return the test suites that should be executed for the current changes
     */
    private Set<String> selectTestsToRun(final VCSReader vcsReader, final List<String> sourceFilesDirNames,
                                         final List<String> testFilesDirNames, final boolean checkLocalChanges,
                                         final String storedCommitValue,
                                         final Map<String, TestSuiteTracker> testSuitesTracked,
                                         final LibraryImpactAnalysisConfig libraryConfig,
                                         final boolean updateDBMapping,
                                         final Map<String, List<PendingLibraryImpactedMethod>> syntheticLibraryBatchesOut){
        List<String> sourceFilesDirs = getFullFilePaths(sourceFilesDirNames);
        List<String> testFilesDirs = getFullFilePaths(testFilesDirNames);

        // Include tracked library source dirs so library diffs pass the source/test dir filter
        // in the VCS analyzers and have their prefixes stripped correctly by MethodImpactAnalyzer.
        if (libraryConfig != null && libraryConfig.isEnabled()) {
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
        vcsReader.loadContentForDiffs(trackedModifiedSourceDiffs, storedCommitValue, checkLocalChanges);

        // Partition source diffs: library diffs go to pending stamp, source-project diffs to immediate selection.
        List<SourceFileDiffContext> sourceProjectDiffs = trackedModifiedSourceDiffs;
        if (shouldPartitionLibraryDiffs(libraryConfig, checkLocalChanges)) {
            sourceProjectDiffs = partitionAndRecordLibraryDiffs(trackedModifiedSourceDiffs, methodsTrackedByFile,
                    sourceFilesDirs, libraryConfig, updateDBMapping, syntheticLibraryBatchesOut);
        }

        // Find all test suites that execute the source code methods that have changed
        Set<Integer> impactedMethods = findMethodsImpacted(sourceProjectDiffs, methodsTrackedByFile, sourceFilesDirs);
        Set<String> testsToRun = findTestSuitesForImpactedMethods(impactedMethods, methodsTrackedByFile);

        // If any test suite files were modified, always re-run these. So add them to the run list.
        addModifiedTestFilesToRunList(groupedImpactedFiles.get(FileImpactAnalyzer.TEST_FILE_MODIFIED), testSuitesTracked, testsToRun, testFilesDirs);

        // Add newly added test files to the run list.
        addNewTestFilesToRunList(groupedImpactedFiles.get(FileImpactAnalyzer.TEST_FILE_ADDED), testsToRun, testFilesDirs);

        // Re-run tests that failed since the last successful full test run.
        addPreviouslyFailedTests(testsToRun);

        return testsToRun;
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
     * Get the full path names for a given list of directories.
     * The input directories could be relative paths (from the current path), or full paths.
     *
     * @param filePaths should be source code or test file directories configured by the user
     * @return
     */
    private List<String> getFullFilePaths(List<String> filePaths){
        List<String> fullFilePaths = new ArrayList<>();
        String currentPath = Paths.get(".").toAbsolutePath().normalize().toString();

        for (String sourceAndTestFilesDir : filePaths){
            File file = loadFileOnDiskFromPath(currentPath, sourceAndTestFilesDir);
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

    private File loadFileOnDiskFromPath(String currentPath, String sourceAndTestFilesDir) {
        // first assume it's a relative path and check if it exists
        String filePath = sourceAndTestFilesDir.startsWith("/") ? sourceAndTestFilesDir : "/" + sourceAndTestFilesDir;
        filePath = currentPath + filePath;

        File file = new File(filePath);
        if (!file.exists()){
            // relative path not found, assume it's a full path from root and try load it
            file = new File(sourceAndTestFilesDir);
            if (!file.exists()){
                file = null;
                log.warn("Can't find configured source of test directory on disk: {}", sourceAndTestFilesDir);
            }
        }

        return file;
    }

    /**
     * Add modified test suites to the run list. Only suites present in the tracked set are
     * added - an untracked modified test file is new to Tia and is picked up by the
     * new-test-file path instead.
     *
     * @param sourceFileDiffContexts the modified test-file diff contexts
     * @param testSuitesTracked the tracked test suites keyed by suite name
     * @param testsToRun the run set to add the modified suites to
     * @param testFilesDirs the configured test file directories
     */
    private void addModifiedTestFilesToRunList(List<SourceFileDiffContext> sourceFileDiffContexts,
                                               Map<String, TestSuiteTracker> testSuitesTracked,
                                               Set<String> testsToRun, List<String> testFilesDirs){
        Set<String> testSuitesModified = new HashSet<>();

        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts){
            String testName = getTestNameFromFilePath(sourceFileDiffContext.getOldFilePath(), testFilesDirs);
            if (testSuitesTracked.containsKey(testName)){
                testSuitesModified.add(testName);
            }
        }

        log.info("Selected tests to run from VCS test file changes: {}", testSuitesModified);
        testsToRun.addAll(testSuitesModified);
    }

    /**
     * Add newly added test suites to the run list. New test files are always run - they have
     * no stored mapping yet.
     *
     * @param sourceFileDiffContexts the added test-file diff contexts
     * @param testsToRun the run set to add the new suites to
     * @param testFilesDirs the configured test file directories
     */
    private void addNewTestFilesToRunList(List<SourceFileDiffContext> sourceFileDiffContexts,
                                          Set<String> testsToRun, List<String> testFilesDirs){
        Set<String> testSuitesAdded = new HashSet<>();
        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts){
            String testName = getTestNameFromFilePath(sourceFileDiffContext.getNewFilePath(), testFilesDirs);
            testSuitesAdded.add(testName);
        }

        log.info("Selected tests to run from new test files: {}", testSuitesAdded);
        testsToRun.addAll(testSuitesAdded);
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
     * @return the tests that should be executed based on the methods changed in the source code.
     */
    private Set<String> findTestSuitesForImpactedMethods(Set<Integer> methodsImpacted,
                                                         Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile){
        Map<Integer, Set<String>> methodTestSuites = dataStore.getTestSuitesForMethods(methodsImpacted);

        Set<String> testsToRun = new HashSet<>();
        for (Map.Entry<Integer, Set<String>> entry : methodTestSuites.entrySet()){
            if (log.isDebugEnabled()){
                log.debug("Tests to run ({}) for method {}: {}", entry.getValue().size(),
                        methodNameForId(entry.getKey(), methodsTrackedByFile), entry.getValue());
            }
            testsToRun.addAll(entry.getValue());
        }

        log.info("Selected tests to run from VCS source changes: {}", testsToRun);
        return testsToRun;
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
     */
    private void addPreviouslyFailedTests(Set<String> testsToRun){
        Set<String> testSuitesFailed = dataStore.getTestSuitesFailed();
        testsToRun.addAll(testSuitesFailed);
        log.info("Running previously failed tests: {}", testSuitesFailed);
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
     */
    private void applyStaticTestSelection(final VCSReader vcsReader,
                                          final StaticTestSelectionConfig staticMappingConfig,
                                          final String storedCommitValue,
                                          final Map<String, TestSuiteTracker> testSuitesTracked,
                                          final Set<String> testsToRun,
                                          final boolean checkLocalChanges) {
        if (staticMappingConfig == null || !staticMappingConfig.isEnabled()) {
            return;
        }

        StaticTestSelectionResolver resolver = new StaticTestSelectionResolver(staticMappingConfig);
        resolver.warnOnEmptyRules(testSuitesTracked);

        Set<String> changedPaths = vcsReader.getChangedFilePaths(storedCommitValue, checkLocalChanges);
        Set<String> forced = resolver.resolve(changedPaths, testSuitesTracked);
        // Always log the static selection outcome when rules are configured - an empty result
        // is as informative as a hit, and this matches the unconditional logging of the other
        // "Selected tests to run from ..." selection sources above.
        log.info("Selected tests to run from static test selection rules: {}", forced);
        testsToRun.addAll(forced);
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
     * Collect the source directories of every tracked library, falling back to the
     * library's project directory when no source dirs are recorded. Used to augment
     * the source-file dirs passed to the VCS analyzers and the method impact analyzer.
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
     * Partition modified source diffs into source-project and per-library buckets.
     * For each library bucket, run method-impact analysis and (when {@code updateDBMapping} is
     * {@code true}) record the impacted method IDs as pending via
     * {@link PendingLibraryImpactedMethodsRecorder}.
     *
     * @param allModifiedSourceDiffs all modified source-file diff contexts for this run
     * @param methodsTrackedByFile the changed-files-to-tracked-methods result covering all modified source files
     *                             (library files included - their dirs are part of the
     *                             normalization roots)
     * @param sourceFilesDirs the configured source root directories
     * @param libraryConfig the library impact analysis config
     * @param updateDBMapping whether this run owns mapping-DB updates
     * @param syntheticLibraryBatchesOut out-param populated with in-memory pending batches on a
     *                                   preview build; left empty on a primary build
     * @return the source-project diffs (those not belonging to any tracked library).
     */
    private List<SourceFileDiffContext> partitionAndRecordLibraryDiffs(
            List<SourceFileDiffContext> allModifiedSourceDiffs,
            Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
            List<String> sourceFilesDirs, LibraryImpactAnalysisConfig libraryConfig,
            boolean updateDBMapping,
            Map<String, List<PendingLibraryImpactedMethod>> syntheticLibraryBatchesOut) {

        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        if (trackedLibraries.isEmpty()) {
            return allModifiedSourceDiffs;
        }

        List<SourceFileDiffContext> sourceProjectDiffs = new ArrayList<>();
        Map<String, List<SourceFileDiffContext>> libraryDiffBuckets = new HashMap<>();

        partitionDiffsByLibraryProjectDir(allModifiedSourceDiffs, trackedLibraries,
                sourceProjectDiffs, libraryDiffBuckets);

        analyzeLibraryBuckets(libraryDiffBuckets, trackedLibraries,
                methodsTrackedByFile, sourceFilesDirs, libraryConfig, updateDBMapping,
                syntheticLibraryBatchesOut);

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
     * For each library bucket, run method-impact analysis on the library's diffs and turn the
     * impacted method IDs into a pending batch. The batch's fate depends on the run mode:
     * <ul>
     *   <li><b>Primary build</b> ({@code updateDBMapping == true}) — the batch is persisted as
     *       pending rows and the library high water mark is advanced via the recorder.</li>
     *   <li><b>Preview build</b> ({@code updateDBMapping == false}, e.g. {@code select-tests}) — the
     *       batch is built in memory only and accumulated into {@code syntheticLibraryBatchesOut}
     *       for the drain preview, without writing any pending rows or advancing library state.</li>
     * </ul>
     *
     * <p>Method-impact analysis runs in both modes: the preview needs the impacted methods to build
     * an accurate synthetic batch, and the analysis is read-only.
     *
     * @param libraryDiffBuckets the modified diffs grouped per owning library
     * @param trackedLibraries the tracked libraries keyed by coordinate
     * @param methodsTrackedByFile the changed-files-to-tracked-methods result covering all modified source files
     * @param sourceFilesDirs the configured source root directories
     * @param libraryConfig the library impact analysis config
     * @param updateDBMapping whether this run owns mapping-DB updates
     * @param syntheticLibraryBatchesOut out-param that collects the in-memory batches on a preview
     *                                   build; left untouched on a primary build
     */
    private void analyzeLibraryBuckets(
            Map<String, List<SourceFileDiffContext>> libraryDiffBuckets,
            Map<String, TrackedLibrary> trackedLibraries,
            Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
            List<String> sourceFilesDirs,
            LibraryImpactAnalysisConfig libraryConfig,
            boolean updateDBMapping,
            Map<String, List<PendingLibraryImpactedMethod>> syntheticLibraryBatchesOut) {

        PendingLibraryImpactedMethodsRecorder recorder = new PendingLibraryImpactedMethodsRecorder();

        for (Map.Entry<String, List<SourceFileDiffContext>> entry : libraryDiffBuckets.entrySet()) {
            String groupArtifact = entry.getKey();
            List<SourceFileDiffContext> libraryDiffs = entry.getValue();
            TrackedLibrary trackedLibrary = trackedLibraries.get(groupArtifact);

            log.info("Library '{}' has {} modified source files - analyzing impacted methods (updateDBMapping={}).",
                    groupArtifact, libraryDiffs.size(), updateDBMapping);
            Set<Integer> impactedMethods = findMethodsImpacted(libraryDiffs, methodsTrackedByFile, sourceFilesDirs);

            if (updateDBMapping) {
                recorder.recordPendingImpactedMethods(dataStore, trackedLibrary, impactedMethods, libraryConfig);
            } else {
                PendingLibraryImpactedMethod syntheticBatch =
                        recorder.buildPendingBatch(trackedLibrary, impactedMethods, libraryConfig);
                if (syntheticBatch != null) {
                    syntheticLibraryBatchesOut
                            .computeIfAbsent(groupArtifact, k -> new ArrayList<>())
                            .add(syntheticBatch);
                }
            }
        }
    }

    /**
     * Drain pending library impacted methods when library impact analysis is configured and we are
     * not in local-changes mode. Resolved tests from qualifying batches are added directly to
     * {@code testsToRun} in both modes; the behaviour otherwise splits on {@code updateDBMapping}:
     * <ul>
     *   <li><b>Primary build</b> — drains the persisted pending batches and returns a
     *       {@link LibraryImpactDrainResult} so the caller can delete the drained rows and advance
     *       {@code tia_library} after the test run.</li>
     *   <li><b>Preview build</b> — evaluates the union of the persisted batches and the in-memory
     *       {@code syntheticLibraryBatches} built earlier in this run, returning the tests that
     *       <em>would</em> drain now without mutating anything. Returns {@code null} because there is
     *       no cleanup to carry. This is what keeps the {@code select-tests} run set (and its runtime
     *       estimate) accurate for first-seen library changes that have never been persisted.</li>
     * </ul>
     *
     * @param libraryConfig the library impact analysis configuration, or null when not configured
     * @param checkLocalChanges whether this run analyzes local (uncommitted) changes - draining
     *                          is skipped in that mode
     * @param updateDBMapping whether this run owns mapping-DB updates (primary vs preview build)
     * @param testsToRun the run set to add drained/previewed tests to
     * @param syntheticLibraryBatches the in-memory batches built on a preview build; ignored on a
     *                                primary build
     * @return the drain result for post-run cleanup on a primary build, or null on a preview build
     *         or when draining was skipped
     */
    private LibraryImpactDrainResult drainPendingLibraryMethodsIfConfigured(
            LibraryImpactAnalysisConfig libraryConfig, boolean checkLocalChanges, boolean updateDBMapping,
            Set<String> testsToRun, Map<String, List<PendingLibraryImpactedMethod>> syntheticLibraryBatches) {

        if (checkLocalChanges || libraryConfig == null || !libraryConfig.isEnabled()) {
            return null;
        }

        PendingLibraryImpactedMethodsDrainer drainer = new PendingLibraryImpactedMethodsDrainer();

        if (!updateDBMapping) {
            Set<String> previewTests = drainer.previewTestsForBatches(dataStore, libraryConfig, syntheticLibraryBatches);
            if (!previewTests.isEmpty()) {
                log.info("Selected tests to run from pending library changes (preview): {}", previewTests);
                testsToRun.addAll(previewTests);
            }
            return null;
        }

        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, libraryConfig);

        if (!outcome.getTestsToAdd().isEmpty()) {
            log.info("Selected tests to run from pending library changes: {}", outcome.getTestsToAdd());
            testsToRun.addAll(outcome.getTestsToAdd());
        }

        return outcome.getDrainResult();
    }
}
