package com.tiatesting.agent.fileanalyze;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.persistence.StoredMapping;

import java.util.*;

/**
 * Analyze
 */
public class FileImpactAnalyzer {

    private static final Log log = LogFactory.getLog(FileImpactAnalyzer.class);

    final MethodImpactAnalyzer methodImpactAnalyzer;

    public FileImpactAnalyzer(MethodImpactAnalyzer methodImpactAnalyzer){
        this.methodImpactAnalyzer = methodImpactAnalyzer;
    }

    /**
     *
     *
     * @param sourceFileDiffContexts
     * @param storedMapping
     */
    public Set<String> getMethodsForFilesChanged(List<SourceFileDiffContext> sourceFileDiffContexts,
                                                 StoredMapping storedMapping){
        Set<String> methodsImpacted = new HashSet<>();
        Map<String, List<MethodImpactTracker>> classesImpacted = getClassesImpacted(storedMapping);

        for (SourceFileDiffContext sourceFileDiffContext : sourceFileDiffContexts){
            switch(sourceFileDiffContext.getChangeType()){
                case MODIFY:
                    methodImpactAnalyzer.getMethodsForImpactedFile(sourceFileDiffContext.getSourceContentOriginal(),
                            sourceFileDiffContext.getSourceContentNew(), sourceFileDiffContext.getOldFilePath(),
                            sourceFileDiffContext.getNewFilePath(), methodsImpacted, classesImpacted);
                    break;
            }
        }

        log.info("Methods impacted: " + methodsImpacted);
        return methodsImpacted;
    }

    /**
     * Build a convenience map showing a list of impacted methods for each impacted class (ignore test suite information).
     * Used for convenience in analyzing the diff files: Tracked Class Name, List<MethodImpactTracker>
     *
     * @param storedMapping
     * @return
     */
    private static Map<String, List<MethodImpactTracker>> getClassesImpacted(StoredMapping storedMapping){
        Map<String, List<MethodImpactTracker>> classesImpacted = new HashMap<>();

        for (List<ClassImpactTracker> testSuiteClassesImpacted : storedMapping.getClassesImpacted().values()){
            for (ClassImpactTracker classImpacted : testSuiteClassesImpacted) {

                if (classesImpacted.get(classImpacted.getClassName()) == null){
                    classesImpacted.put(classImpacted.getClassName(), new ArrayList<>());
                }

                classesImpacted.get(classImpacted.getClassName()).addAll(classImpacted.getMethodsImpacted());
            }
        }

        return classesImpacted;
    }
}
