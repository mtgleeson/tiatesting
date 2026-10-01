package org.tiatesting.core.diff.diffanalyze;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.algorithm.DiffException;
import com.github.difflib.patch.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.LineRanges;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.sourcefile.SourceFilenameUtil;

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
     * @param originalFileContent the original file content
     * @param newFilContent the new file content
     * @param originalFileName the original filename
     * @param revisedFileName the revised filename
     * @param methodsInvokedByChanges A set of methods that will be invoked by the changes in source code
     * @param methodsTrackedByFile the tracked methods (with line ranges) for the changed source
     *                             files, keyed by mapping-key filename then method id - the
     *                             targeted changed-files-to-tracked-methods read for this run's diff
     * @param sourceFilesDirs Locations of the source files for the project being tested
     */
    public void getMethodsForImpactedFile(final String originalFileContent, final String newFilContent,
                                          final String originalFileName, final String revisedFileName,
                                          final Set<Integer> methodsInvokedByChanges,
                                          final Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
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
            Map<Integer, MethodImpactTracker> methodsTrackedForSourceFile =
                    getMethodsTrackedForSourceFile(methodsTrackedByFile, originalFileName, sourceFilesDirs);

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

    /**
     * Look up the tracked methods for a source file from a VCS diff. The diff's file path
     * is normalized to the stored mapping key via
     * {@link SourceFilenameUtil#normalizeToMappingKey(String, List)} so the lookup matches
     * the relative, forward-slash keys produced by the coverage agent.
     *
     * @param methodsTrackedByFile the tracked methods for the changed files, keyed by mapping key
     * @param originalFilePath the original (pre-change) file path from the diff
     * @param sourceFilesDirs the configured source root directories to strip from the path
     * @return the tracked methods (by id) for the file, or {@code null} when the file isn't tracked
     */
    private Map<Integer, MethodImpactTracker> getMethodsTrackedForSourceFile(
            final Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile,
            final String originalFilePath,
            final List<String> sourceFilesDirs){
        String fileName = SourceFilenameUtil.normalizeToMappingKey(originalFilePath, sourceFilesDirs);
        log.debug("File name to lookup tracked classes for methods - {}", fileName);
        return methodsTrackedByFile.get(fileName);
    }

    /**
     * Check if any of the impacted code line numbers are within the current methods line numbers.
     * Note: the stored line numbers generated from the code coverage analysis start from the line after the
     * method description/signature, and end at the line with the closing brace.
     *
     * IM = impacted method (from VCS).
     * Check start point is within method:			    IM start > = method start && IM start <= method end
     * Check end point is within method:			    IM end  >= method start && IM end <= method end
     * Check impacted code range covers the method: 	IM start <= method start && IM end >= method end
     *
     * A method carrying exact line ranges (a constructor or static initializer whose start-end range
     * is split by other members) is instead matched against those ranges - see
     * {@link #diffTouchesLineRanges(DiffContext, int[])}.
     *
     * @param diffContext the parsed diff hunk carrying the impacted line range
     * @param methodsTrackedForSourceFile the tracked methods (by id) for the changed file
     * @param methodsInvokedByChanges accumulator for the ids of methods the diff impacts
     */
    private void findTrackedMethodsForSourceDiff(final DiffContext diffContext,
                                                 final Map<Integer, MethodImpactTracker> methodsTrackedForSourceFile,
                                                 final Set<Integer> methodsInvokedByChanges){
        for (Map.Entry<Integer, MethodImpactTracker> trackedMethod : methodsTrackedForSourceFile.entrySet()) {
            Integer methodHashcode = trackedMethod.getKey();
            MethodImpactTracker methodImpactTracker = trackedMethod.getValue();
            int diffLineBegin = diffContext.getImpactedLineNumBegin();
            int diffLineEnd = diffContext.getImpactedLineNumEnd();

            if (methodImpactTracker.getLineRanges() != null) {
                if (diffTouchesLineRanges(diffContext, methodImpactTracker.getLineRanges())) {
                    methodsInvokedByChanges.add(methodHashcode);
                    log.debug("Found stored tracked method: {}, diff line begin: {}, diff line end: {}, stored line ranges: {}",
                            methodImpactTracker.getMethodName(), diffLineBegin, diffLineEnd,
                            LineRanges.format(methodImpactTracker.getLineRanges()));
                }
                continue;
            }

            // start-end widened by one line either side, to catch changes to the method name line and
            // the closing brace
            int[] matchedLineRange = methodImpactTracker.getMatchedLineRanges();
            int methodLineBegin = matchedLineRange[0];
            int methodLineEnd = matchedLineRange[1];
            log.debug("Method {}, diffLineBegin: {}, diffLineEnd: {}, methodLineBegin: {}, methodLineEnd: {}", methodImpactTracker.getMethodName(), diffLineBegin, diffLineEnd, methodLineBegin, methodLineEnd);

            boolean diffBeginIsWithinMethod = diffLineBegin >= methodLineBegin && diffLineBegin <= methodLineEnd;
            boolean diffEndIsWithinMethod = diffLineEnd >= methodLineBegin && diffLineEnd <= methodLineEnd;
            boolean diffRangeCoversMethod = diffLineBegin <= methodLineBegin && diffLineEnd >= methodLineEnd;

            if (diffBeginIsWithinMethod || diffEndIsWithinMethod || diffRangeCoversMethod) {
                methodsInvokedByChanges.add(methodHashcode);
                log.debug("Found stored tracked method: {}, diff line begin: {}, diff line end: {}, stored line begin: {}, stored line end: {}",
                        methodImpactTracker.getMethodName(), diffLineBegin, diffLineEnd, methodLineBegin, methodLineEnd);
            }
        }
    }

    /**
     * Check whether a diff hunk touches any of a method's exact line ranges. The ranges already
     * include the signature/closing-brace allowance, so they are matched without extra padding.
     * A pure insertion is written by java-diff-utils as {@code -N,0}, placing the new lines before
     * original line N (i.e. between lines N-1 and N), so it touches a range containing either
     * neighbour - e.g. a new field added directly after the last line of a constructor range still
     * counts as a constructor change.
     *
     * @param diffContext the parsed diff hunk carrying the impacted line range
     * @param lineRanges the method's flat inclusive {@code [start, end, ...]} pairs
     * @return true when the hunk overlaps at least one range
     */
    private boolean diffTouchesLineRanges(final DiffContext diffContext, final int[] lineRanges){
        int diffLineEnd = diffContext.getImpactedLineNumEnd();
        int diffLineBegin = diffContext.isInsertion() ? diffLineEnd - 1 : diffContext.getImpactedLineNumBegin();
        for (int i = 0; i < lineRanges.length; i += 2) {
            if (diffLineBegin <= lineRanges[i + 1] && diffLineEnd >= lineRanges[i]) {
                return true;
            }
        }
        return false;
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
            sourceFileDiffContext.setInsertion(revisionLineCount == 0);
            revisionLineCount = revisionLineCount <= 0 ? 1 : revisionLineCount;

            sourceFileDiffContext.setImpactedLineNumBegin(revisionLineBegin);
            sourceFileDiffContext.setImpactedLineNumEnd(revisionLineBegin + (revisionLineCount - 1));

            log.debug("getImpactedLineNumBegin: " + sourceFileDiffContext.getImpactedLineNumBegin() + " getImpactedLineNumEnd: " + sourceFileDiffContext.getImpactedLineNumEnd());
        }
    }

    private static class DiffContext {
        boolean unifiedDiff;
        boolean insertion;
        int impactedLineNumBegin;
        int impactedLineNumEnd;

        /**
         * Whether the hunk is a pure insertion ({@code -N,0}), i.e. it adds lines before original
         * line N without changing any original line.
         *
         * @return true for a pure insertion
         */
        public boolean isInsertion() {
            return insertion;
        }

        /**
         * Record whether the hunk is a pure insertion.
         *
         * @param insertion true when the hunk's original line count is 0
         */
        public void setInsertion(boolean insertion) {
            this.insertion = insertion;
        }

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
