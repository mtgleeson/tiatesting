package org.tiatesting.core.diff.diffanalyze;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;

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
     * @param methodsTrackedByFile the tracked methods (with line ranges) for the changed source
     *                             files, keyed by mapping-key filename then method id - the
     *                             targeted changed-files-to-tracked-methods read for this run's diff
     * @param sourceFilesDirs Locations of the source files for the project being tested
     * @return the set of ids for the methods that have changed
     */
    public Set<Integer> getMethodsForFilesChanged(final List<SourceFileDiffContext> sourceFileDiffContexts,
                                                  final Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
                                                  final List<String> sourceFilesDirs){
        Set<Integer> methodsInvokedByChanges = new HashSet<>();

        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts){
            methodImpactAnalyzer.getMethodsForImpactedFile(sourceFileDiffContext.getSourceContentOriginal(),
                    sourceFileDiffContext.getSourceContentNew(), sourceFileDiffContext.getOldFilePath(),
                    sourceFileDiffContext.getNewFilePath(), methodsInvokedByChanges, methodsTrackedByFile,
                    sourceFilesDirs);
        }

        if (log.isDebugEnabled()) {
            log.debug("Methods impacted: " + methodsInvokedByChanges.stream()
                    .map(methodId -> methodNameForId(methodId, methodsTrackedByFile))
                    .collect(Collectors.joining(",")));
        }

        return methodsInvokedByChanges;
    }

    /**
     * Resolve a method id to its display name by scanning the per-file tracked-method maps.
     * Only used for debug logging, so the linear scan across the diff's files is acceptable.
     *
     * @param methodId the tracked method id to resolve
     * @param methodsTrackedByFile the tracked methods for the changed files, keyed by filename
     * @return the method name, or the id itself when not found
     */
    private static String methodNameForId(final Integer methodId,
                                          final Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile){
        for (Map<Integer, MethodImpactTracker> fileMethods : methodsTrackedByFile.values()){
            MethodImpactTracker tracker = fileMethods.get(methodId);
            if (tracker != null){
                return tracker.getMethodName();
            }
        }
        return String.valueOf(methodId);
    }

    private String getDiffFilePath(SourceFileDiffContext sourceFileDiffContext){
        if (sourceFileDiffContext.getChangeType() == ChangeType.ADD){
            return sourceFileDiffContext.getNewFilePath();
        } else {
            return sourceFileDiffContext.getOldFilePath();
        }
    }
}
