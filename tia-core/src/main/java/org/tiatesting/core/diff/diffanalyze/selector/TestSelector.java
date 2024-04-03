package org.tiatesting.core.diff.diffanalyze.selector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.diffanalyze.FileImpactAnalyzer;
import org.tiatesting.core.diff.diffanalyze.MethodImpactAnalyzer;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
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
     * @param vcsReader
     * @param sourceFilesDirNames
     * @param testFilesDirNames
     * @param checkLocalChanges should local changes be checked by Tia.
     * @return list of test suites to ignore in the current test run.
     */
    public TestSelectorResult selectTestsToIgnore(final VCSReader vcsReader, final List<String> sourceFilesDirNames,
                                           final List<String> testFilesDirNames, final boolean checkLocalChanges){
        TiaData tiaData = dataStore.getTiaData(true);
        if (!hasStoredMapping(tiaData)){
            return new TestSelectorResult(new HashSet<>(), new HashSet<>()); // run all tests - don't ignore any
        }

        Set<String> testsToRun = selectTestsToRun(vcsReader, sourceFilesDirNames, testFilesDirNames, checkLocalChanges,
                tiaData);

        // Get the list of tests from the stored mapping that aren't in the list of test suites to run.
        Set<String> testsToIgnore = getTestsToIgnore(tiaData, testsToRun);

        log.debug("Ignoring tests: {}", testsToIgnore);
        return new TestSelectorResult(testsToRun, testsToIgnore);
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
     * @param vcsReader
     * @param sourceFilesDirNames
     * @param testFilesDirNames
     * @param checkLocalChanges
     * @return
     */
    public Set<String> selectTestsToRun(final VCSReader vcsReader, final List<String> sourceFilesDirNames,
                                        final List<String> testFilesDirNames, final boolean checkLocalChanges){
        TiaData tiaData = dataStore.getTiaData(true);
        return selectTestsToRun(vcsReader, sourceFilesDirNames, testFilesDirNames, checkLocalChanges,
                tiaData);
    }

    private Set<String> selectTestsToRun(final VCSReader vcsReader, final List<String> sourceFilesDirNames,
                                         final List<String> testFilesDirNames, final boolean checkLocalChanges,
                                         final TiaData tiaData){
        List<String> sourceFilesDirs = getFullFilePaths(sourceFilesDirNames);
        List<String> testFilesDirs = getFullFilePaths(testFilesDirNames);

        Set<SourceFileDiffContext> impactedSourceFiles = vcsReader.buildDiffFilesContext(tiaData.getCommitValue(),
                sourceFilesDirs, testFilesDirs, checkLocalChanges);
        Map<String, List<SourceFileDiffContext>> groupedImpactedFiles = fileImpactAnalyzer.groupImpactedTestFiles(impactedSourceFiles, testFilesDirs);

        // Find all test suites that execute the source code methods that have changed
        Set<Integer> impactedMethods = findMethodsImpacted(groupedImpactedFiles.get(FileImpactAnalyzer.SOURCE_FILE_MODIFIED), tiaData, sourceFilesDirs);
        Set<String> testsToRun = findTestSuitesForImpactedMethods(tiaData, impactedMethods);

        // Re-run tests that failed since the last successful full test run.
        addPreviouslyFailedTests(tiaData, testsToRun);

        // If any test suite files were modified, always re-run these. So add them to the run list.
        addModifiedTestFilesToRunList(groupedImpactedFiles.get(FileImpactAnalyzer.TEST_FILE_MODIFIED), tiaData, testsToRun, testFilesDirs);

        // Add newly added test files to the run list.
        addNewTestFilesToRunList(groupedImpactedFiles.get(FileImpactAnalyzer.TEST_FILE_ADDED), tiaData, testsToRun, testFilesDirs);
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
            testsToRun.addAll(methodTestSuites.get(methodImpacted));
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
}
