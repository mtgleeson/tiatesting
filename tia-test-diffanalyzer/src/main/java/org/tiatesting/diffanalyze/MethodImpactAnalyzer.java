package org.tiatesting.diffanalyze;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.algorithm.DiffException;
import com.github.difflib.patch.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.MethodImpactTracker;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 */
public class MethodImpactAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MethodImpactAnalyzer.class);
    private static final String LINEBREAK_PATTERN = "\\R";
    private static final Pattern UNIFIED_DIFF_HUNK_PATTERN = Pattern.compile("^@@ \\-(\\d+),(\\d+) \\+(\\d+),(\\d+) @@$");
    private static final int HUNK_DIFF_ORIG_LINE_START_GROUP_INDEX = 1;
    private static final int HUNK_DIFF_ORIG_LINE_START_COUNT_GROUP_INDEX = 2;

    /**
     * Compare two versions of a source file and create a unified diff.
     * Parse the original content as a Java file and check each method to see if it's in the diff based on the
     * file line numbers and the diff segment line numbers.
     *
     * @param originalFileContent
     * @param newFilContent
     * @param originalFileName
     * @param revisedFileName
     * @param methodsInvokedByChanges A set of methods that will be invoked by the changes in source code
     * @param sourceFilesTracked The tracked list of source files and their associated list of methods with previous execution coverage
     * @param sourceFilesDirs Locations of the source files for the project being tested
     */
    public void getMethodsForImpactedFile(final String originalFileContent, final String newFilContent,
                                          final String originalFileName, final String revisedFileName,
                                          final Set<String> methodsInvokedByChanges,
                                          final Map<String, Set<MethodImpactTracker>> sourceFilesTracked,
                                          final List<String> sourceFilesDirs){

        List<String> originalFileLines = Arrays.asList(originalFileContent.split(LINEBREAK_PATTERN));
        List<String> newFileLines = Arrays.asList(newFilContent.split(LINEBREAK_PATTERN));

        try {
            // create the diff between the original and revised file content
            Patch<String> diff = DiffUtils.diff(originalFileLines, newFileLines);

            // generating unified diff format so the line numbers are consistent with the source file
            List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(originalFileName, revisedFileName, originalFileLines, diff, 0);

            /*
            Find the class from the tracked list.
            For each diff, get the line number from the original file, then find the method from the tracked list within
            the line number range (using start and end line numbers).
             */
            Set<MethodImpactTracker> methodsTrackedForSourceFile = getMethodsTrackedForSourceFile(sourceFilesTracked,
                    originalFileName, sourceFilesDirs);

            if (methodsTrackedForSourceFile != null && !methodsTrackedForSourceFile.isEmpty()){
                unifiedDiff.forEach( patchDiff -> {

                    DiffContext diffContext = new DiffContext();
                    setImpactedLineBeginEnd(patchDiff, diffContext);

                    if (diffContext.isUnifiedDiff()){
                        findTrackedMethodsForSourceDiff(diffContext, methodsTrackedForSourceFile, methodsInvokedByChanges);
                    }
                });
            }
        } catch (DiffException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private Set<MethodImpactTracker> getMethodsTrackedForSourceFile(final Map<String, Set<MethodImpactTracker>> sourceFilesTracked,
                                                                    final String originalFilePath,
                                                                    final List<String> sourceFilesDirs){
        String fileName = "/" + originalFilePath;

        for (String sourceFilesDir : sourceFilesDirs){
            fileName = fileName.replace(sourceFilesDir + "/", "");
        }

        log.trace("File name to lookup tracked classes for methods - {}", fileName);
        return sourceFilesTracked.get(fileName);
    }

    private void findTrackedMethodsForSourceDiff(final DiffContext diffContext,
                                                 final Set<MethodImpactTracker> methodsTrackedForSourceFile,
                                                 final Set<String> methodsInvokedByChanges){
        for (MethodImpactTracker methodImpactTracker : methodsTrackedForSourceFile) {
            /**
             * Check if any of the impacted code line numbers are within the current methods line numbers.
             *
             * IM = impacted method (from VCS).
             * Check start point is within method:			    IM start > = method start && IM start <= method end
             * Check end point is within method:			    IM end  >= method start && IM end <= method end
             * Check impacted code range covers the method: 	IM start <= method start && IM end >= method end
             */
            int diffLineBegin = diffContext.getImpactedLineNumBegin();
            int diffLineEnd = diffContext.getImpactedLineNumEnd();
            int methodLineBegin = methodImpactTracker.getLineNumberStart();
            int methodLineEnd = methodImpactTracker.getLineNumberEnd();
            log.trace("Method {}, diffLineBegin: {}, diffLineEnd: {}, methodLineBegin: {}, methodLineEnd: {}", methodImpactTracker.getMethodName(), diffLineBegin, diffLineEnd, methodLineBegin, methodLineEnd);

            boolean diffBeginIsWithinMethod = diffLineBegin >= methodLineBegin && diffLineBegin <= methodLineEnd;
            boolean diffEndIsWithinMethod = diffLineEnd >= methodLineBegin && diffLineEnd <= methodLineEnd;
            boolean diffRangeCoversMethod = diffLineBegin <= methodLineBegin && diffLineEnd >= methodLineEnd;

            if (diffBeginIsWithinMethod || diffEndIsWithinMethod || diffRangeCoversMethod) {
                methodsInvokedByChanges.add(methodImpactTracker.getMethodName());
                log.debug("Found stored tracked method: {}, source line begin: {}, source line end: {}, stored line begin: {}, stored line end: {}",
                        methodImpactTracker.getMethodName(), methodLineBegin, methodLineEnd,
                        methodImpactTracker.getLineNumberStart(), methodImpactTracker.getLineNumberEnd());
            }
        }
    }

    /**
     * Parse the diff 'hunk' using regex to get the line numbers for the change for the original file.
     * i.e. @@ -11,2 +11,2 @@ i.e. this means old file - line 11, 2 lines (get replaced in new file, starting line 11 for 2 lines).
     * https://www.gnu.org/software/diffutils/manual/html_node/Detailed-Unified.html
     *
     * @param patchDiff
     * @param sourceFileDiffContext
     */
    private void setImpactedLineBeginEnd(String patchDiff, DiffContext sourceFileDiffContext){
        Matcher matcher = UNIFIED_DIFF_HUNK_PATTERN.matcher(patchDiff);

        if  (matcher.matches()){
            log.debug("Diff hunk: " + patchDiff);
            sourceFileDiffContext.setUnifiedDiff(true);

            int revisionLineBegin = Integer.parseInt(matcher.group(HUNK_DIFF_ORIG_LINE_START_GROUP_INDEX));
            int revisionLineCount = Integer.parseInt(matcher.group(HUNK_DIFF_ORIG_LINE_START_COUNT_GROUP_INDEX));

            // if the hunk line count is 0 (i.e. line added) then treat it as 1 line.
            revisionLineCount = revisionLineCount <= 0 ? 1 : revisionLineCount;

            sourceFileDiffContext.setImpactedLineNumBegin(revisionLineBegin);
            sourceFileDiffContext.setImpactedLineNumEnd(revisionLineBegin + (revisionLineCount - 1));

            log.debug("getImpactedLineNumBegin: " + sourceFileDiffContext.getImpactedLineNumBegin() + " getImpactedLineNumEnd: " + sourceFileDiffContext.getImpactedLineNumEnd());
        }
    }

    private static class DiffContext {
        boolean unifiedDiff;
        int impactedLineNumBegin;
        int impactedLineNumEnd;

        public int getImpactedLineNumBegin() {
            return impactedLineNumBegin;
        }

        public void setImpactedLineNumBegin(int impactedLineNumBegin) {
            this.impactedLineNumBegin = impactedLineNumBegin;
        }

        public int getImpactedLineNumEnd() {
            return impactedLineNumEnd;
        }

        public void setImpactedLineNumEnd(int impactedLineNumEnd) {
            this.impactedLineNumEnd = impactedLineNumEnd;
        }

        public boolean isUnifiedDiff() {
            return unifiedDiff;
        }

        public void setUnifiedDiff(boolean unifiedDiff) {
            this.unifiedDiff = unifiedDiff;
        }
    }
}
