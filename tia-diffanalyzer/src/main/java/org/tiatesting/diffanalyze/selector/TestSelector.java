package org.tiatesting.diffanalyze.selector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.coverage.TestSuiteTracker;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.diffanalyze.FileImpactAnalyzer;
import org.tiatesting.diffanalyze.MethodImpactAnalyzer;
import org.tiatesting.persistence.StoredMapping;

import java.util.*;

import static org.tiatesting.core.sourcefile.FileExtensions.JAVA_FILE_EXTENSION;
import static org.tiatesting.core.sourcefile.FileExtensions.GROOVY_FILE_EXTENSION;

public class TestSelector {

    private static final Logger log = LoggerFactory.getLogger(TestSelector.class);

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
     * @param storedMapping
     * @param vcsReader
     * @param sourceFilesDirs
     * @return list of test suites to ignore in the current test run.
     */
    public Set<String> selectTestsToIgnore(StoredMapping storedMapping, VCSReader vcsReader, List<String> sourceFilesDirs){
        log.info("Stored DB commit: " + storedMapping.getCommitValue());

        if (storedMapping.getCommitValue() == null) {
            // If no stored commit value found it means Tia hasn't previously run. We need to run all tests, don't ignore any.
            log.info("No stored commit value found. Tia hasn't previously run. Running all tests.");
            return new HashSet<>();
        }

        List<SourceFileDiffContext> impactedSourceFiles = vcsReader.buildDiffFilesContext(storedMapping.getCommitValue());
        Set<String> impactedMethods = findMethodsImpacted(impactedSourceFiles, storedMapping, sourceFilesDirs);
        Set<String> testsToRun = findTestSuitesForImpactedMethods(storedMapping, impactedMethods);

        // Re-run tests that failed since the last successful full test run.
        addPreviouslyFailedTests(storedMapping, testsToRun);

        // If any test suite files were modified, always re-run these. So add them to the run list.
        addModifiedTestFilesToRunList(impactedSourceFiles, storedMapping, testsToRun);

        // Get the list of tests from the stored mapping that aren't in the list of test suites to run.
        Set<String> testsToIgnore = getTestsToIgnore(storedMapping, testsToRun);

        log.debug("Ignoring tests: {}", testsToIgnore);
        return testsToIgnore;
    }

    private void addModifiedTestFilesToRunList(List<SourceFileDiffContext> impactedSourceFiles, StoredMapping storedMapping,
                                               Set<String> testsToRun){
        Set<String> testSuitesAdded = new HashSet<>();
        for (SourceFileDiffContext sourceFileDiffContext : impactedSourceFiles){
            if (sourceFileDiffContext.getChangeType() == ChangeType.MODIFY){
                testSuitesAdded.add(getTestNameFromFilePath(sourceFileDiffContext.getOldFilePath(), storedMapping.getTestSuitesTracked()));
            }
        }

        log.debug("Selected tests to run from VCS test file changes: {}", testSuitesAdded);
        testsToRun.addAll(testSuitesAdded);
    }

    private String getTestNameFromFilePath(String testFilePath, Map<String, TestSuiteTracker> testSuitesTrackers){
        testFilePath = testFilePath.replaceAll(JAVA_FILE_EXTENSION, "");
        testFilePath = testFilePath.replaceAll(GROOVY_FILE_EXTENSION, "");

        for (TestSuiteTracker testSuiteTracker : testSuitesTrackers.values()){
            if (testFilePath.endsWith(testSuiteTracker.getSourceFilename())){
                return testSuiteTracker.getName();
            }
        }
        return null;
    }

    /**
     * For the source files that have changed, do a diff to find the methods that have changed.
     *
     * @param impactedSourceFiles
     * @param storedMapping
     * @param sourceFilesDirs
     * @return
     */
    private Set<String> findMethodsImpacted(List<SourceFileDiffContext> impactedSourceFiles,
                                            StoredMapping storedMapping, List<String> sourceFilesDirs){
        FileImpactAnalyzer fileImpactAnalyzer = new FileImpactAnalyzer(new MethodImpactAnalyzer());
        return fileImpactAnalyzer.getMethodsForFilesChanged(impactedSourceFiles, storedMapping, sourceFilesDirs);
    }

    /**
     * Build the list of test suites that need to be run based on the tracked methods that have been changed.
     *
     * @param storedMapping
     * @param methodsImpacted
     * @return the tests that should be executed based on the methods changed in the source code.
     */
    private Set<String> findTestSuitesForImpactedMethods(StoredMapping storedMapping, Set<String> methodsImpacted){
        Map<String, Set<String>> methodTestSuites = buildMethodToTestSuiteMap(storedMapping);

        Set<String> testsToRun = new HashSet<>();
        methodsImpacted.forEach( ( methodImpacted ) -> {
            testsToRun.addAll(methodTestSuites.get(methodImpacted));
        });

        log.debug("Selected tests to run from VCS source changes: {}", testsToRun);
        return testsToRun;
    }

    /**
     * Add the tests that failed on the previous run - force them to be re-run.
     *
     * @param storedMapping
     * @param testsToRun
     */
    private void addPreviouslyFailedTests(StoredMapping storedMapping, Set<String> testsToRun){
        testsToRun.addAll(storedMapping.getTestSuitesFailed());
        log.info("Running previously failed tests: {}", storedMapping.getTestSuitesFailed());
    }

    /**
     * Find the list of all known tracked test suites that are in the list of tests to run. This is the ignore list.
     * i.e. only ignore test suites that we have previously tracked and haven't been impacted by the source changes.
     * This ensures any new test suites are executed.
     *
     * @param storedMapping
     * @param testsToRun
     * @return
     */
    private Set<String> getTestsToIgnore(StoredMapping storedMapping, Set<String> testsToRun){
        Set<String> testsToIgnore = new HashSet<>();

        storedMapping.getTestSuitesTracked().keySet().forEach( (testSuite) -> {
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
     * @param storedMapping keyed by method name, value is a list of test suites
     * @return
     */
    private Map<String, Set<String>> buildMethodToTestSuiteMap(StoredMapping storedMapping){
        Map<String, Set<String>> methodTestSuites = new HashMap<>();

        storedMapping.getTestSuitesTracked().forEach((testSuiteName, testSuiteTracker) -> {
            for (ClassImpactTracker classImpacted : testSuiteTracker.getClassesImpacted()) {
                for (MethodImpactTracker methodImpactTracker : classImpacted.getMethodsImpacted()) {
                    if (methodTestSuites.get(methodImpactTracker.getMethodName()) == null) {
                        methodTestSuites.put(methodImpactTracker.getMethodName(), new HashSet<>());
                    }

                    methodTestSuites.get(methodImpactTracker.getMethodName()).add(testSuiteName);
                }
            }
        });

        //methodTestSuites.forEach( (key, val) -> {
        //    System.out.println(System.lineSeparator() + "key: " + key + " val: " + System.lineSeparator() + "\t" + val.stream().map( String::valueOf ).collect(Collectors.joining(System.lineSeparator() + "\t", "", "")));
        //});

        return methodTestSuites;
    }
}
