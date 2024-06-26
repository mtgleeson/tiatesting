package org.tiatesting.core.diff.diffanalyze;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.TiaData;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyze
 */
public class FileImpactAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FileImpactAnalyzer.class);
    public static final String SOURCE_FILE_ADDED = "sourceFileAdded";
    public static final String SOURCE_FILE_MODIFIED = "sourceFileModified";
    public static final String SOURCE_FILE_DELETED = "sourceFileDeleted";
    public static final String TEST_FILE_ADDED = "testFileAdded";
    public static final String TEST_FILE_MODIFIED = "testFileModified";
    public static final String TEST_FILE_DELETED = "testFileDeleted";

    final MethodImpactAnalyzer methodImpactAnalyzer;

    public FileImpactAnalyzer(MethodImpactAnalyzer methodImpactAnalyzer){
        this.methodImpactAnalyzer = methodImpactAnalyzer;
    }

    /**
     * Loop over impactedSourceFiles once and create a map containing a list of modified source code diffs, modified test
     * file diffs, deleted test file diffs.
     *
     * @param sourceFileDiffContexts the set of diff file contexts
     * @param testFilesDirs the list of test file directories
     * @return the impacted test files
     */
    public Map<String, List<SourceFileDiffContext>> groupImpactedTestFiles(Set<SourceFileDiffContext> sourceFileDiffContexts,
                                                                           final List<String> testFilesDirs){
        Map<String, List<SourceFileDiffContext>> groupedImpactedFiles = new HashMap<>();
        groupedImpactedFiles.put(SOURCE_FILE_ADDED, new ArrayList<>());
        groupedImpactedFiles.put(SOURCE_FILE_MODIFIED, new ArrayList<>());
        groupedImpactedFiles.put(SOURCE_FILE_DELETED, new ArrayList<>());
        groupedImpactedFiles.put(TEST_FILE_ADDED, new ArrayList<>());
        groupedImpactedFiles.put(TEST_FILE_MODIFIED, new ArrayList<>());
        groupedImpactedFiles.put(TEST_FILE_DELETED, new ArrayList<>());

        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts) {
            boolean isTestFile = isTestFile(sourceFileDiffContext, testFilesDirs);

            switch (sourceFileDiffContext.getChangeType()) {
                case ADD:
                    groupedImpactedFiles.get((isTestFile ? TEST_FILE_ADDED : SOURCE_FILE_ADDED)).add(sourceFileDiffContext);
                    break;
                case MODIFY:
                    groupedImpactedFiles.get((isTestFile ? TEST_FILE_MODIFIED : SOURCE_FILE_MODIFIED)).add(sourceFileDiffContext);
                    break;
                case DELETE:
                    groupedImpactedFiles.get((isTestFile ? TEST_FILE_DELETED : SOURCE_FILE_DELETED)).add(sourceFileDiffContext);
                    break;
            }
        }

        return groupedImpactedFiles;
    }

    private boolean isTestFile(SourceFileDiffContext sourceFileDiffContext, final List<String> testFilesDirs){
        for (String testFilesDir: testFilesDirs){
            if (getDiffFilePath(sourceFileDiffContext).startsWith(testFilesDir)){
                return true;
            }
        }
        return false;
    }

    /**
     * For the source files that have changed, do a diff to find the methods that have changed.
     *
     * @param sourceFileDiffContexts the set of diff file contexts
     * @param tiaData the Tia DB as an object
     * @param sourceFilesDirs Locations of the source files for the project being tested
     * @return the set of ids for the methods that have changed
     */
    public Set<Integer> getMethodsForFilesChanged(final List<SourceFileDiffContext> sourceFileDiffContexts,
                                                  final TiaData tiaData, final List<String> sourceFilesDirs){
        Set<Integer> methodsInvokedByChanges = new HashSet<>();
        Map<String, Set<Integer>> sourceFilesTracked = buildTrackedSourceFileMethods(tiaData);
        Map<Integer, MethodImpactTracker> methodImpactTrackers = tiaData.getMethodsTracked();

        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts){
            methodImpactAnalyzer.getMethodsForImpactedFile(sourceFileDiffContext.getSourceContentOriginal(),
                    sourceFileDiffContext.getSourceContentNew(), sourceFileDiffContext.getOldFilePath(),
                    sourceFileDiffContext.getNewFilePath(), methodsInvokedByChanges, sourceFilesTracked,
                    sourceFilesDirs, methodImpactTrackers);
        }

        log.debug("Methods impacted: " +
                methodsInvokedByChanges.stream().map( hc -> methodImpactTrackers.get(hc).getMethodName() ).collect(Collectors.joining(",")));

        return methodsInvokedByChanges;
    }

    /**
     * Build a convenience map showing a list of impacted methods for each impacted source file (ignore test suite information).
     * Used for convenience in analyzing the diff files: Tracked Source File: List<MethodImpactTracker>
     *
     * @param tiaData the Tia DB as an object
     * @return the tracked source file methods
     */
    private static Map<String, Set<Integer>> buildTrackedSourceFileMethods(final TiaData tiaData){
        Map<String, Set<Integer>> sourceFilesTracked = new HashMap<>();

        for (TestSuiteTracker testSuiteTracker : tiaData.getTestSuitesTracked().values()){
            for (ClassImpactTracker classImpacted : testSuiteTracker.getClassesImpacted()) {
                String sourceFilename = classImpacted.getSourceFilename();

                if (sourceFilesTracked.get(sourceFilename) == null){
                    sourceFilesTracked.put(sourceFilename, new HashSet<>());
                }

                sourceFilesTracked.get(sourceFilename).addAll(classImpacted.getMethodsImpacted());
            }
        }

        return sourceFilesTracked;
    }

    private String getDiffFilePath(SourceFileDiffContext sourceFileDiffContext){
        if (sourceFileDiffContext.getChangeType() == ChangeType.ADD){
            return sourceFileDiffContext.getNewFilePath();
        } else {
            return sourceFileDiffContext.getOldFilePath();
        }
    }
}
