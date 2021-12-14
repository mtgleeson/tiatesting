package org.tiatesting;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.vcs.SourceFileDiffContext;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TODO move this class to a new file impact analyzer module (or existing tia-test-agent module).
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
     * @param classesImpacted
     */
    public Set<String> getMethodsForFilesChanged(List<SourceFileDiffContext> sourceFileDiffContexts,
                                          Map<String, List<MethodImpactTracker>> classesImpacted){
        Set<String> methodsImpacted = new HashSet<>();

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

}
