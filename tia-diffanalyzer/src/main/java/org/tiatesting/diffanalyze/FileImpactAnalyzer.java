package org.tiatesting.diffanalyze;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.coverage.TestSuiteTracker;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.persistence.StoredMapping;

import java.util.*;

/**
 * Analyze
 */
public class FileImpactAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FileImpactAnalyzer.class);

    final MethodImpactAnalyzer methodImpactAnalyzer;

    public FileImpactAnalyzer(MethodImpactAnalyzer methodImpactAnalyzer){
        this.methodImpactAnalyzer = methodImpactAnalyzer;
    }

    /**
     *
     *
     * @param sourceFileDiffContexts
     * @param storedMapping
     * @param sourceFilesDirs Locations of the source files for the project being tested
     */
    public Set<String> getMethodsForFilesChanged(final List<SourceFileDiffContext> sourceFileDiffContexts,
                                                 final StoredMapping storedMapping, final List<String> sourceFilesDirs){
        Set<String> methodsInvokedByChanges = new HashSet<>();
        Map<String, Set<MethodImpactTracker>> sourceFilesTracked = buildTrackedSourceFileMethods(storedMapping);

        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts){
            switch(sourceFileDiffContext.getChangeType()){
                case MODIFY:
                    methodImpactAnalyzer.getMethodsForImpactedFile(sourceFileDiffContext.getSourceContentOriginal(),
                            sourceFileDiffContext.getSourceContentNew(), sourceFileDiffContext.getOldFilePath(),
                            sourceFileDiffContext.getNewFilePath(), methodsInvokedByChanges, sourceFilesTracked,
                            sourceFilesDirs);
                    break;
            }
        }

        log.debug("Methods impacted: " + methodsInvokedByChanges);
        return methodsInvokedByChanges;
    }

    /**
     * Build a convenience map showing a list of impacted methods for each impacted source file (ignore test suite information).
     * Used for convenience in analyzing the diff files: Tracked Source File, List<MethodImpactTracker>
     *
     * @param storedMapping
     * @return
     */
    private static Map<String, Set<MethodImpactTracker>> buildTrackedSourceFileMethods(final StoredMapping storedMapping){
        Map<String, Set<MethodImpactTracker>> sourceFilesTracked = new HashMap<>();

        for (TestSuiteTracker testSuiteTracker : storedMapping.getTestSuitesTracked().values()){
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
}
