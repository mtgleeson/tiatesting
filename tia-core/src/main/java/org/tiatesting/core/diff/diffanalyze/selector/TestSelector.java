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
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.diff.SourceFileDiffContext;
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
     *
     * Build the list of source files that have been changed since the previously analyzed commit.
     * For the source files that have changed, do a diff to find the methods that have changed.
     * Build the list of test suites that need to be run based on the methods that have been changed.
     * Add the tests that failed on the previous run - force them to be re-run.
     *
     * Finally, find the list of all known tracked test suites that are associated with the list of tests to run. This
     * is the ignore list returned.
     * i.e. only ignore test suites that we have previously tracked and haven't been impacted by the source changes.
     * This ensures any new test suites are executed.
     *
     * When a {@link LibraryImpactAnalysisConfig} is non-null and enabled, the reconciler synchronises
     * declared libraries against the persisted {@code tia_library} rows before proceeding with test selection.
     *
     * @param vcsReader the VCS reader
     * @param sourceFilesDirNames the dir names for the source files
     * @param testFilesDirNames the dir names for the test files
     * @param checkLocalChanges should local changes be checked by Tia.
     * @param libraryConfig the library impact analysis config, or {@code null} if not configured.
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
                                           final boolean updateDBMapping){
        TiaData tiaData = dataStore.getTiaData(true);

        if (updateDBMapping) {
            reconcileTrackedLibrariesIfConfigured(libraryConfig);
        }

        if (!hasStoredMapping(tiaData)){
            return new TestSelectorResult(new HashSet<>(), new HashSet<>(), null); // run all tests - don't ignore any
        }

        Set<String> testsToRun = selectTestsToRun(vcsReader, sourceFilesDirNames, testFilesDirNames, checkLocalChanges,
                tiaData, libraryConfig, updateDBMapping);

        LibraryImpactDrainResult drainResult = drainPendingLibraryMethodsIfConfigured(
                libraryConfig, checkLocalChanges, tiaData, testsToRun);

        // Get the list of tests from the stored mapping that aren't in the list of test suites to run.
        Set<String> testsToIgnore = getTestsToIgnore(tiaData, testsToRun);

        log.debug("Ignoring tests: {}", testsToIgnore);
        return new TestSelectorResult(testsToRun, testsToIgnore, drainResult);
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
     * Get the selected tests to run based on the changes to VCS since the last submit tracked by Tia.
     * It will add any previously failed tests tracked by Tia and any test files that have had changes since the last
     * commit.
     * Note: this represents the list of tests to run that Tia is aware of. There may be new test files in changes that
     * have been analysed that will be executed by the test runner in addition to this list of tests.
     *
     * @param vcsReader the VCS reader
     * @param sourceFilesDirNames the dir names for the source files
     * @param testFilesDirNames the dir names for the test files
     * @param checkLocalChanges should we check for local changes?
     * @return the selected tests to run
     */
    public Set<String> selectTestsToRun(final VCSReader vcsReader, final List<String> sourceFilesDirNames,
                                        final List<String> testFilesDirNames, final boolean checkLocalChanges){
        TiaData tiaData = dataStore.getTiaData(true);
        // No libraryConfig is supplied on this entry point, so the stamp/reconcile paths are not reachable —
        // updateDBMapping has no effect here today. Pass false defensively so any future wiring of
        // libraryConfig into this overload does not silently start writing on non-primary callers.
        return selectTestsToRun(vcsReader, sourceFilesDirNames, testFilesDirNames, checkLocalChanges,
                tiaData, null, false);
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
     */
    private Set<String> selectTestsToRun(final VCSReader vcsReader, final List<String> sourceFilesDirNames,
                                         final List<String> testFilesDirNames, final boolean checkLocalChanges,
                                         final TiaData tiaData, final LibraryImpactAnalysisConfig libraryConfig,
                                         final boolean updateDBMapping){
        List<String> sourceFilesDirs = getFullFilePaths(sourceFilesDirNames);
        List<String> testFilesDirs = getFullFilePaths(testFilesDirNames);

        // Include tracked library source dirs so library diffs pass the source/test dir filter
        // in the VCS analyzers and have their prefixes stripped correctly by MethodImpactAnalyzer.
        if (libraryConfig != null && libraryConfig.isEnabled()) {
            sourceFilesDirs = new ArrayList<>(sourceFilesDirs);
            sourceFilesDirs.addAll(collectTrackedLibraryDirs());
        }

        Set<SourceFileDiffContext> impactedSourceFiles = vcsReader.buildDiffFilesContext(tiaData.getCommitValue(),
                sourceFilesDirs, testFilesDirs, checkLocalChanges);
        Map<String, List<SourceFileDiffContext>> groupedImpactedFiles = fileImpactAnalyzer.groupImpactedTestFiles(impactedSourceFiles, testFilesDirs);

        List<SourceFileDiffContext> modifiedSourceDiffs = groupedImpactedFiles.get(FileImpactAnalyzer.SOURCE_FILE_MODIFIED);

        // Partition source diffs: library diffs go to pending stamp, source-project diffs to immediate selection.
        List<SourceFileDiffContext> sourceProjectDiffs = modifiedSourceDiffs;
        if (shouldPartitionLibraryDiffs(libraryConfig, checkLocalChanges)) {
            sourceProjectDiffs = partitionAndRecordLibraryDiffs(modifiedSourceDiffs, tiaData, sourceFilesDirs,
                    libraryConfig, updateDBMapping);
        }

        // Find all test suites that execute the source code methods that have changed
        Set<Integer> impactedMethods = findMethodsImpacted(sourceProjectDiffs, tiaData, sourceFilesDirs);
        Set<String> testsToRun = findTestSuitesForImpactedMethods(tiaData, impactedMethods);

        // If any test suite files were modified, always re-run these. So add them to the run list.
        addModifiedTestFilesToRunList(groupedImpactedFiles.get(FileImpactAnalyzer.TEST_FILE_MODIFIED), tiaData, testsToRun, testFilesDirs);

        // Add newly added test files to the run list.
        addNewTestFilesToRunList(groupedImpactedFiles.get(FileImpactAnalyzer.TEST_FILE_ADDED), tiaData, testsToRun, testFilesDirs);

        // Re-run tests that failed since the last successful full test run.
        addPreviouslyFailedTests(tiaData, testsToRun);

        return testsToRun;
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

    private void addModifiedTestFilesToRunList(List<SourceFileDiffContext> sourceFileDiffContexts, TiaData tiaData,
                                               Set<String> testsToRun, List<String> testFilesDirs){
        Set<String> testSuitesModified = new HashSet<>();

        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts){
            String testName = getTestNameFromFilePath(sourceFileDiffContext.getOldFilePath(), testFilesDirs);
            if (tiaData.getTestSuitesTracked().containsKey(testName)){
                testSuitesModified.add(testName);
            }
        }

        log.info("Selected tests to run from VCS test file changes: {}", testSuitesModified);
        testsToRun.addAll(testSuitesModified);
    }

    private void addNewTestFilesToRunList(List<SourceFileDiffContext> sourceFileDiffContexts, TiaData tiaData,
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
        testFilePath = testFilePath.startsWith("/") ? testFilePath.substring(1, testFilePath.length()) : testFilePath; // trim leading /
        testFilePath = testFilePath.replaceAll("\\/", ".");

        return testFilePath;
    }

    /**
     * For the source files that have changed, do a diff to find the methods that have changed.
     *
     * @param sourceFileDiffContexts
     * @param tiaData
     * @param sourceFilesDirs
     * @return set of method (hashcodes) that are impacted by the diff changes
     */
    private Set<Integer> findMethodsImpacted(List<SourceFileDiffContext> sourceFileDiffContexts,
                                             TiaData tiaData, List<String> sourceFilesDirs){
        return fileImpactAnalyzer.getMethodsForFilesChanged(sourceFileDiffContexts, tiaData, sourceFilesDirs);
    }

    /**
     * Build the list of test suites that need to be run based on the tracked methods that have been changed.
     *
     * @param tiaData
     * @param methodsImpacted
     * @return the tests that should be executed based on the methods changed in the source code.
     */
    private Set<String> findTestSuitesForImpactedMethods(TiaData tiaData, Set<Integer> methodsImpacted){
        Map<Integer, Set<String>> methodTestSuites = buildMethodToTestSuiteMap(tiaData);

        Set<String> testsToRun = new HashSet<>();
        methodsImpacted.forEach( ( methodImpacted ) -> {
            Set<String> methodTestsToRun = methodTestSuites.get(methodImpacted);
            log.debug("Tests to run ({}) for method {}: {}", methodTestsToRun.size(), tiaData.getMethodsTracked().get(methodImpacted).getMethodName(), methodTestsToRun);
            testsToRun.addAll(methodTestsToRun);
        });

        log.info("Selected tests to run from VCS source changes: {}", testsToRun);
        return testsToRun;
    }

    /**
     * Add the tests that failed on the previous run - force them to be re-run.
     *
     * @param tiaData
     * @param testsToRun
     */
    private void addPreviouslyFailedTests(TiaData tiaData, Set<String> testsToRun){
        testsToRun.addAll(tiaData.getTestSuitesFailed());
        log.info("Running previously failed tests: {}", tiaData.getTestSuitesFailed());
    }

    /**
     * Find the list of all known tracked test suites that are in the list of tests to run. This is the ignore list.
     * i.e. only ignore test suites that we have previously tracked and haven't been impacted by the source changes.
     * This ensures any new test suites are executed.
     *
     * @param tiaData
     * @param testsToRun
     * @return
     */
    private Set<String> getTestsToIgnore(TiaData tiaData, Set<String> testsToRun){
        Set<String> testsToIgnore = new HashSet<>();

        tiaData.getTestSuitesTracked().keySet().forEach( (testSuite) -> {
            if (!testsToRun.contains(testSuite)){
                testsToIgnore.add(testSuite);
            }
        });

        return testsToIgnore;
    }

    /**
     * Convert to a map containing a list of test suites for each impacted method.
     * Use this for convenience lookup when finding the list of test suites to ignore for previously tracked methods
     * that have been changed in the diff.
     *
     * @param tiaData keyed by method name, value is a list of test suites
     * @return
     */
    private Map<Integer, Set<String>> buildMethodToTestSuiteMap(TiaData tiaData){
        Map<Integer, Set<String>> methodTestSuites = new HashMap<>();

        tiaData.getTestSuitesTracked().forEach((testSuiteName, testSuiteTracker) -> {
            for (ClassImpactTracker classImpacted : testSuiteTracker.getClassesImpacted()) {
                for (Integer methodTrackedHashCode : classImpacted.getMethodsImpacted()) {
                    if (methodTestSuites.get(methodTrackedHashCode) == null) {
                        methodTestSuites.put(methodTrackedHashCode, new HashSet<>());
                    }

                    methodTestSuites.get(methodTrackedHashCode).add(testSuiteName);
                }
            }
        });

        return methodTestSuites;
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
     * @return the source-project diffs (those not belonging to any tracked library).
     */
    private List<SourceFileDiffContext> partitionAndRecordLibraryDiffs(
            List<SourceFileDiffContext> allModifiedSourceDiffs, TiaData tiaData,
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
                tiaData, sourceFilesDirs, libraryConfig, updateDBMapping);

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
     */
    private void recordImpactedMethodsForLibraryBuckets(
            Map<String, List<SourceFileDiffContext>> libraryDiffBuckets,
            Map<String, TrackedLibrary> trackedLibraries,
            TiaData tiaData, List<String> sourceFilesDirs,
            LibraryImpactAnalysisConfig libraryConfig,
            boolean updateDBMapping) {

        PendingLibraryImpactedMethodsRecorder recorder = new PendingLibraryImpactedMethodsRecorder();

        for (Map.Entry<String, List<SourceFileDiffContext>> entry : libraryDiffBuckets.entrySet()) {
            String groupArtifact = entry.getKey();
            List<SourceFileDiffContext> libraryDiffs = entry.getValue();
            TrackedLibrary trackedLibrary = trackedLibraries.get(groupArtifact);

            if (!updateDBMapping) {
                log.info("Library '{}' has {} modified source files — skipping pending-stamp persist (updateDBMapping=false).",
                        groupArtifact, libraryDiffs.size());
                continue;
            }

            log.info("Library '{}' has {} modified source files — analyzing impacted methods.",
                    groupArtifact, libraryDiffs.size());

            Set<Integer> impactedMethods = findMethodsImpacted(libraryDiffs, tiaData, sourceFilesDirs);
            recorder.recordPendingImpactedMethods(dataStore, trackedLibrary, impactedMethods, libraryConfig);
        }
    }

    /**
     * Drain pending library impacted methods when library impact analysis is configured
     * and we are not in local-changes mode. Resolved tests from drained batches are added
     * directly to {@code testsToRun}. The drain result is returned so the caller can carry
     * it to the post-test-run cleanup phase.
     */
    private LibraryImpactDrainResult drainPendingLibraryMethodsIfConfigured(
            LibraryImpactAnalysisConfig libraryConfig, boolean checkLocalChanges,
            TiaData tiaData, Set<String> testsToRun) {

        if (checkLocalChanges || libraryConfig == null || !libraryConfig.isEnabled()) {
            return null;
        }

        PendingLibraryImpactedMethodsDrainer drainer = new PendingLibraryImpactedMethodsDrainer();
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, libraryConfig, tiaData);

        if (!outcome.getTestsToAdd().isEmpty()) {
            log.info("Selected tests to run from pending library changes: {}", outcome.getTestsToAdd());
            testsToRun.addAll(outcome.getTestsToAdd());
        }

        return outcome.getDrainResult();
    }
}
