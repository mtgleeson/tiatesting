package org.tiatesting;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.tiatesting.vcs.SourceFileDiffContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TODO move this class to a new file impact analyzer module.
 */
public class FileImpactAnalyzer {

    private static final Log log = LogFactory.getLog(FileImpactAnalyzer.class);

    final MethodImpactAnalyzer methodImpactAnalyzer;

    public FileImpactAnalyzer(MethodImpactAnalyzer methodImpactAnalyzer){
        this.methodImpactAnalyzer = methodImpactAnalyzer;
    }

    public void getMethodsForFilesChanged(List<SourceFileDiffContext> impactedSourceFiles){
        Set<String> methodsImpacted = new HashSet<>();


        for (SourceFileDiffContext impactedSourceFile : impactedSourceFiles){
            switch(impactedSourceFile.getChangeType()){
                case MODIFY:
                    methodImpactAnalyzer.getMethodsForFileChanged(impactedSourceFile.getSourceContentOriginal(),
                            impactedSourceFile.getSourceContentNew(), impactedSourceFile.getOldFilePath(),
                            impactedSourceFile.getNewFilePath(), methodsImpacted);
                    break;
            }
        }

        System.out.println("methodsImpacted: " + methodsImpacted);
    }

}
