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
        // is queried per diff-slice (Phase A/B) inside selectTestsToRun, and the suite-level
        // metadata (names + stats, no coverage edges) is loaded once below. The full
        // suite-class-method mapping is never bulk-loaded on this path.
        TiaData tiaCore = dataStore.getTiaCore();

        if (updateDBMapping) {
            reconcileTrackedLibrariesIfConfigured(libraryConfig);
        }

        if (!hasStoredMapping(tiaCore)){
            // run all tests - don't ignore any
            return new TestSelectorResult(new HashSet<>(), new HashSet<>(), null,
                    0L, Collections.emptySet(), 0L, Collections.emptyMap());
        }

        // Suite names + stats only (no coverage edges): serves the modified-test-file check,
        // the ignore list and the run-time estimate.
        Map<String, TestSuiteTracker> testSuitesTracked = dataStore.getTestSuitesTracked();

        Set<String> testsToRun = selectTestsToRun(vcsReader, sourceFilesDirNames, testFilesDirNames, checkLocalChanges,
                tiaCore.getCommitValue(), testSuitesTracked, libraryConfig, updateDBMapping);

        LibraryImpactDrainResult drainResult = drainPendingLibraryMethodsIfConfigured(
                libraryConfig, checkLocalChanges, testsToRun);

        applyStaticTestSelection(vcsReader, staticMappingConfig, tiaCore.getCommitValue(), testSuitesTracked,
                testsToRun, checkLocalChanges);

        // Get the list of tests from the stored mapping that aren't in the list of test suites to run.
        Set<String> testsToIgnore = getTestsToIgnore(testSuitesTracked, testsToRun);

        log.debug("Ignoring tests: {}", testsToIgnore);

        RunTimeEstimate estimate = estimateRunTime(testsToRun, testSuitesTracked);
        return new TestSelectorResult(testsToRun, testsToIgnore, drainResult,
                estimate.getEstimatedRunTimeMs(), estimate.getSelectedTestsWithoutStats(),
                estimate.getMedianRunTimeMsAppliedToMissing(),
                estimate.getSelectedTestRunTimesMs());
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
     * @param testsToRun the names of the selected test suites
     * @param tracked the tracked test suites (names + stats) keyed by suite name
     * @return a {@link RunTimeEstimate} carrying the total estimated runtime (ms), the names
     *         of selected tests with no stats, and the median value applied to those tests
     */
    static RunTimeEstimate estimateRunTime(final Set<String> testsToRun, final Map<String, TestSuiteTracker> tracked){
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

        return new RunTimeEstimate(totalMs, withoutStats, median, perTestRunTimes);
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

        /**
         * @param estimatedRunTimeMs total estimated runtime (ms) for the selected tests,
         *                           including the median-fallback contribution
         * @param selectedTestsWithoutStats names of selected tests with no recorded stats
         * @param medianRunTimeMsAppliedToMissing median {@code avgRunTime} (ms) applied to
         *                                        each test in {@code selectedTestsWithoutStats},
         *                                        or {@code 0} when no fallback was applied
         * @param selectedTestRunTimesMs per-test runtime (ms) keyed by test suite name; covers
         *                               every entry in {@code testsToRun}, with the median
         *                               value (or {@code 0} when no median is available) used
         *                               for tests without stats
         */
        RunTimeEstimate(long estimatedRunTimeMs, Set<String> selectedTestsWithoutStats,
                        long medianRunTimeMsAppliedToMissing,
                        Map<String, Long> selectedTestRunTimesMs) {
            this.estimatedRunTimeMs = estimatedRunTimeMs;
            this.selectedTestsWithoutStats = selectedTestsWithoutStats;
            this.medianRunTimeMsAppliedToMissing = medianRunTimeMsAppliedToMissing;
            this.selectedTestRunTimesMs = selectedTestRunTimesMs;
        }

        /** @return the total estimated runtime (ms) for the selected tests */
        long getEstimatedRunTimeMs() { return estimatedRunTimeMs; }

        /** @return names of selected tests with no recorded run-time stats */
        Set<String> getSelectedTestsWithoutStats() { return selectedTestsWithoutStats; }

        /** @return median {@code avgRunTime} (ms) substituted for tests without stats */
        long getMedianRunTimeMsAppliedToMissing() { return medianRunTimeMsAppliedToMissing; }

        /** @return per-test runtime (ms) keyed by test suite name */
        Map<String, Long> getSelectedTestRunTimesMs() { return selectedTestRunTimesMs; }
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
     * <p>When {@code updateDBMapping} is {@code false}, library-diff partitioning still runs (so that
     * library-owned diffs are not misclassified as source-project diffs) but the pending-stamp persist
     * is skipped — non-primary builds read the existing tracked-library snapshot without mutating it.
     *
     * <p>Mapping reads are targeted to the diff: one Phase A query
     * ({@link DataStore#getMethodsTrackedForFiles}) resolves the changed files to their tracked
     * methods (shared by the source-impact and library-stamp paths), and one Phase B query
     * ({@link DataStore#getTestSuitesForMethods}) resolves the impacted methods to suites.
     *
     * @param vcsReader the VCS reader used to build the diff contexts
     * @param sourceFilesDirNames the dir names for the source files
     * @param testFilesDirNames the dir names for the test files
     * @param checkLocalChanges should local changes be checked by Tia
     * @param storedCommitValue the commit the stored mapping was built at (diff baseline)
     * @param testSuitesTracked the tracked test suites (names + stats) keyed by suite name
     * @param libraryConfig the library impact analysis config, or {@code null} if not configured
     * @param updateDBMapping whether this run owns mapping-DB updates
     * @return the test suites that should be executed for the current changes
     */
    private Set<String> selectTestsToRun(final VCSReader vcsReader, final List<String> sourceFilesDirNames,
                                         final List<String> testFilesDirNames, final boolean checkLocalChanges,
                                         final String storedCommitValue,
                                         final Map<String, TestSuiteTracker> testSuitesTracked,
                                         final LibraryImpactAnalysisConfig libraryConfig,
                                         final boolean updateDBMapping){
        List<String> sourceFilesDirs = getFullFilePaths(sourceFilesDirNames);
        List<String> testFilesDirs = getFullFilePaths(testFilesDirNames);

        // Include tracked library source dirs so library diffs pass the source/test dir filter
        // in the VCS analyzers and have their prefixes stripped correctly by MethodImpactAnalyzer.
        if (libraryConfig != null && libraryConfig.isEnabled()) {
            sourceFilesDirs = new ArrayList<>(sourceFilesDirs);
            sourceFilesDirs.addAll(collectTrackedLibraryDirs());
        }

        Set<SourceFileDiffContext> impactedSourceFiles = vcsReader.buildDiffFilesContext(storedCommitValue,
                sourceFilesDirs, testFilesDirs, checkLocalChanges);
        Map<String, List<SourceFileDiffContext>> groupedImpactedFiles = fileImpactAnalyzer.groupImpactedTestFiles(impactedSourceFiles, testFilesDirs);

        List<SourceFileDiffContext> modifiedSourceDiffs = groupedImpactedFiles.get(FileImpactAnalyzer.SOURCE_FILE_MODIFIED);

        // Phase A: one targeted query for the tracked methods of ALL modified source files
        // (source-project and library buckets alike) - both consumers below share the result.
        Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile =
                loadMethodsTrackedForDiffs(modifiedSourceDiffs, sourceFilesDirs);

        // Partition source diffs: library diffs go to pending stamp, source-project diffs to immediate selection.
        List<SourceFileDiffContext> sourceProjectDiffs = modifiedSourceDiffs;
        if (shouldPartitionLibraryDiffs(libraryConfig, checkLocalChanges)) {
            sourceProjectDiffs = partitionAndRecordLibraryDiffs(modifiedSourceDiffs, methodsTrackedByFile,
                    sourceFilesDirs, libraryConfig, updateDBMapping);
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
     * Run the targeted Phase A read for a set of modified-source diffs: normalize each diff's
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
     * @param methodsTrackedByFile the Phase A result: tracked methods for the changed files,
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
     * been changed, using the targeted Phase B query
     * ({@link DataStore#getTestSuitesForMethods}) instead of an in-memory reverse index over
     * the full mapping.
     *
     * @param methodsImpacted the set of method ids that the diff implicates
     * @param methodsTrackedByFile the Phase A result, used to resolve method names for debug logging
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
     * Resolve a method id to its display name from the Phase A per-file result map. Debug
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
     * @param testSuitesTracked the tracked test suites keyed by suite name
     * @param testsToRun the test suites selected to run
     * @return the tracked suites not selected to run - the ignore list
     */
    private Set<String> getTestsToIgnore(Map<String, TestSuiteTracker> testSuitesTracked, Set<String> testsToRun){
        Set<String> testsToIgnore = new HashSet<>();

        testSuitesTracked.keySet().forEach( (testSuite) -> {
            if (!testsToRun.contains(testSuite)){
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

        // The resolver consumes TiaData but only reads the tracked-suite map from it. The
        // targeted select path no longer materializes a full TiaData, so build the carrier
        // from the metadata already loaded; one instance serves both calls (the resolver
        // caches its suite-name index per TiaData instance).
        TiaData resolverData = new TiaData();
        resolverData.setCommitValue(storedCommitValue);
        resolverData.setTestSuitesTracked(testSuitesTracked);

        StaticTestSelectionResolver resolver = new StaticTestSelectionResolver(staticMappingConfig);
        resolver.warnOnEmptyRules(resolverData);

        Set<String> changedPaths = vcsReader.getChangedFilePaths(storedCommitValue, checkLocalChanges);
        Set<String> forced = resolver.resolve(changedPaths, resolverData);
        if (!forced.isEmpty()) {
            log.info("Selected tests to run from static test selection rules: {}", forced);
            testsToRun.addAll(forced);
        }
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
     * @param methodsTrackedByFile the Phase A result covering all modified source files
     *                             (library files included - their dirs are part of the
     *                             normalization roots)
     * @param sourceFilesDirs the configured source root directories
     * @param libraryConfig the library impact analysis config
     * @param updateDBMapping whether this run owns mapping-DB updates
     * @return the source-project diffs (those not belonging to any tracked library).
     */
    private List<SourceFileDiffContext> partitionAndRecordLibraryDiffs(
            List<SourceFileDiffContext> allModifiedSourceDiffs,
            Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
            List<String> sourceFilesDirs, LibraryImpactAnalysisConfig libraryConfig,
            boolean updateDBMapping) {

        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        if (trackedLibraries.isEmpty()) {
            return allModifiedSourceDiffs;
        }

        List<SourceFileDiffContext> sourceProjectDiffs = new ArrayList<>();
        Map<String, List<SourceFileDiffContext>> libraryDiffBuckets = new HashMap<>();

        partitionDiffsByLibraryProjectDir(allModifiedSourceDiffs, trackedLibraries,
                sourceProjectDiffs, libraryDiffBuckets);

        recordImpactedMethodsForLibraryBuckets(libraryDiffBuckets, trackedLibraries,
                methodsTrackedByFile, sourceFilesDirs, libraryConfig, updateDBMapping);

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
     * For each library bucket, run method-impact analysis on the library's diffs
     * and record the impacted method IDs as pending via the recorder.
     *
     * <p>When {@code updateDBMapping} is {@code false} the partitioning still runs (so library-owned
     * diffs are kept out of the source-project bucket and don't trigger immediate test selection),
     * but the recorder is not invoked — non-primary builds must not write pending rows or advance
     * the high water mark on a shared mapping DB.
     *
     * @param libraryDiffBuckets the modified diffs grouped per owning library
     * @param trackedLibraries the tracked libraries keyed by coordinate
     * @param methodsTrackedByFile the Phase A result covering all modified source files
     * @param sourceFilesDirs the configured source root directories
     * @param libraryConfig the library impact analysis config
     * @param updateDBMapping whether this run owns mapping-DB updates
     */
    private void recordImpactedMethodsForLibraryBuckets(
            Map<String, List<SourceFileDiffContext>> libraryDiffBuckets,
            Map<String, TrackedLibrary> trackedLibraries,
            Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
            List<String> sourceFilesDirs,
            LibraryImpactAnalysisConfig libraryConfig,
            boolean updateDBMapping) {

        PendingLibraryImpactedMethodsRecorder recorder = new PendingLibraryImpactedMethodsRecorder();

        for (Map.Entry<String, List<SourceFileDiffContext>> entry : libraryDiffBuckets.entrySet()) {
            String groupArtifact = entry.getKey();
            List<SourceFileDiffContext> libraryDiffs = entry.getValue();
            TrackedLibrary trackedLibrary = trackedLibraries.get(groupArtifact);

            if (!updateDBMapping) {
                log.info("Library '{}' has {} modified source files - skipping pending-stamp persist (updateDBMapping=false).",
                        groupArtifact, libraryDiffs.size());
                continue;
            }

            log.info("Library '{}' has {} modified source files - analyzing impacted methods.",
                    groupArtifact, libraryDiffs.size());

            Set<Integer> impactedMethods = findMethodsImpacted(libraryDiffs, methodsTrackedByFile, sourceFilesDirs);
            recorder.recordPendingImpactedMethods(dataStore, trackedLibrary, impactedMethods, libraryConfig);
        }
    }

    /**
     * Drain pending library impacted methods when library impact analysis is configured
     * and we are not in local-changes mode. Resolved tests from drained batches are added
     * directly to {@code testsToRun}. The drain result is returned so the caller can carry
     * it to the post-test-run cleanup phase.
     *
     * @param libraryConfig the library impact analysis configuration, or null when not configured
     * @param checkLocalChanges whether this run analyzes local (uncommitted) changes - draining
     *                          is skipped in that mode
     * @param testsToRun the run set to add drained tests to
     * @return the drain result for post-run cleanup, or null when draining was skipped
     */
    private LibraryImpactDrainResult drainPendingLibraryMethodsIfConfigured(
            LibraryImpactAnalysisConfig libraryConfig, boolean checkLocalChanges,
            Set<String> testsToRun) {

        if (checkLocalChanges || libraryConfig == null || !libraryConfig.isEnabled()) {
            return null;
        }

        PendingLibraryImpactedMethodsDrainer drainer = new PendingLibraryImpactedMethodsDrainer();
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, libraryConfig);

        if (!outcome.getTestsToAdd().isEmpty()) {
            log.info("Selected tests to run from pending library changes: {}", outcome.getTestsToAdd());
            testsToRun.addAll(outcome.getTestsToAdd());
        }

        return outcome.getDrainResult();
    }
}
