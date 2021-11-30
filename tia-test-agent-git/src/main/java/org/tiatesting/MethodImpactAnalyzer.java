package org.tiatesting;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.algorithm.DiffException;
import com.github.difflib.patch.Patch;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitor;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TODO move this class to a new file impact analyzer module.
 */
public class MethodImpactAnalyzer {

    private static final Log log = LogFactory.getLog(MethodImpactAnalyzer.class);
    private static final String LINEBREAK_PATTERN = "\\R";
    private static final Pattern UNIFIED_DIFF_HUNK_PATTERN = Pattern.compile("^@@ \\-(\\d+),(\\d+) \\+(\\d+),(\\d+) @@$");
    private static final int HUNK_DIFF_ORIG_LINE_START_GROUP_INDEX = 1;
    private static final int HUNK_DIFF_ORIG_LINE_START_COUNT_GROUP_INDEX = 2;

    protected void getMethodsForFileChanged(String originalFileContent, String newFilContent, String originalFileName,
                                          String revisedFileName, Set<String> methodsImpacted){
        List<String> originalFileLines = Arrays.asList(originalFileContent.split(LINEBREAK_PATTERN));
        List<String> newFileLines = Arrays.asList(newFilContent.split(LINEBREAK_PATTERN));

        try {
            Patch<String> diff = DiffUtils.diff(originalFileLines, newFileLines);
            //generating unified diff format
            List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(originalFileName, revisedFileName, originalFileLines, diff, 0);
            MethodVisitorContext methodVisitorContext = new MethodVisitorContext(methodsImpacted);
            VoidVisitor<MethodVisitorContext> methodNameVisitor = new MethodImpactVisitor();

            unifiedDiff.forEach( patchDiff -> {
                CompilationUnit cu = StaticJavaParser.parse(originalFileContent);
                setImpactedLineBeginEnd(patchDiff, methodVisitorContext);
                methodNameVisitor.visit(cu, methodVisitorContext);
            });
        } catch (DiffException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Parse the 'hunk' using regex to get the line numbers for the change for the original file.
     * i.e. @@ -11,2 +11,2 @@ i.e. this means old file - line 11, 2 lines (get replaced in new file, starting line 11 for 2 lines).
     * https://www.gnu.org/software/diffutils/manual/html_node/Detailed-Unified.html
     *
     * @param patchDiff
     * @param methodVisitorContext
     */
    private void setImpactedLineBeginEnd(String patchDiff, MethodVisitorContext methodVisitorContext){
        Matcher matcher = UNIFIED_DIFF_HUNK_PATTERN.matcher(patchDiff);

        if  (matcher.matches()){
            System.out.println("diffHunk: " + patchDiff);
            int revisionLineBegin = Integer.parseInt(matcher.group(HUNK_DIFF_ORIG_LINE_START_GROUP_INDEX));
            int revisionLineCount = Integer.parseInt(matcher.group(HUNK_DIFF_ORIG_LINE_START_COUNT_GROUP_INDEX));

            // if the hunk line count is 0 (i.e. line added) then treat it as 1 line.
            revisionLineCount = revisionLineCount <= 0 ? 1 : revisionLineCount;

            methodVisitorContext.setImpactedLineNumBegin(revisionLineBegin);
            methodVisitorContext.setImpactedLineNumEnd(revisionLineBegin + (revisionLineCount - 1));

            System.out.println("getImpactedLineNumBegin: " + methodVisitorContext.getImpactedLineNumBegin() + " getImpactedLineNumEnd: " + methodVisitorContext.getImpactedLineNumEnd());
        }
    }

    /**
     * Visitor for methods in a source Java file. The visitor checks if a given diff (MethodVisitorContext) touches
     * the method. This is based on checking the range of lines from the diff snippet against the line number range
     * of the file.
     * Note: the diff represented in MethodVisitorContext is one diff patch snippet from a file. An impacted file
     * could have many diffs (MethodVisitorContext).
     *
     * For example, a diff snippet could look like:
     * @@ -33,0 +33,3 @@
     * System.out.println("temp 0");
     *
     */
    private static class MethodImpactVisitor extends VoidVisitorAdapter<MethodVisitorContext> {
        @Override
        public void visit(MethodDeclaration md, MethodVisitorContext arg) {
            super.visit(md, arg);

            /**
             * Check if any of the impacted code line numbers are within the current methods line numbers.
             *
             * IM = impacted method (from VCS).
             * Check start point is within method			IM start > = method start && IM start <= method end
             * Check end point is within method			IM end  >= method start && IM end <= method end
             * Check impacted code range covers the method			IM start <= method start && IM end >= method end
             */
            int impactedCodeLineBegin = arg.getImpactedLineNumBegin();
            int impactedCodeLineEnd = arg.getImpactedLineNumEnd();
            int methodLineBegin = md.getBegin().get().line;
            int methodLineEnd = md.getEnd().get().line;

            boolean impactedChangeBeginIsWithinMethod = impactedCodeLineBegin >= methodLineBegin && impactedCodeLineBegin <= methodLineEnd;
            boolean impactedChangeEndIsWithinMethod = impactedCodeLineEnd >= methodLineBegin && impactedCodeLineEnd <= methodLineEnd;
            boolean impactedChangeRangeCoversMethod = impactedCodeLineBegin <= methodLineBegin && impactedCodeLineEnd >= methodLineEnd;

            if (impactedChangeBeginIsWithinMethod || impactedChangeEndIsWithinMethod || impactedChangeRangeCoversMethod){
                arg.getMethodsImpacted().add(md.getDeclarationAsString());
            }

            /* TODO need to add the method in the format used with JACOCO. Not sure it's available out of the box from JavaParser?
            // Might need to create the format myself using what's available in JavaParser MethodDeclaration?
            System.out.println("Method Name Printed: " + md.getName() + " |||||| " + md.getDeclarationAsString()
                    + " ||||| " + md.getSignature()
                    + " ||||| " + md.getNameAsExpression()
                    + " ||||| " + md.getTypeAsString()
                    + " ||||| " + md.getBegin().get().line
                    + " ||||| " + md.getEnd().get().line);
             */
        }
    }

    private static class MethodVisitorContext  {
        Set<String> methodsImpacted;
        int impactedLineNumBegin;
        int impactedLineNumEnd;

        public MethodVisitorContext(Set<String> methodsImpacted){
            this.methodsImpacted = methodsImpacted;
        }

        public Set<String> getMethodsImpacted() {
            return methodsImpacted;
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
    }
}
