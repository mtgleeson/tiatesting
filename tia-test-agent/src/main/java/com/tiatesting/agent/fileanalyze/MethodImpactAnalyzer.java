package com.tiatesting.agent.fileanalyze;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.algorithm.DiffException;
import com.github.difflib.patch.Patch;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitor;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.tiatesting.core.coverage.MethodImpactTracker;

import java.util.*;
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

    /**
     * Compare two versions of a source file and create a unified diff.
     * Parse the original content as a Java file and check each method to see if it's in the diff based on the
     * file line numbers and the diff segment line numbers.
     *
     * @param originalFileContent
     * @param newFilContent
     * @param originalFileName
     * @param revisedFileName
     * @param methodsImpacted
     * @param classesImpacted
     */
    public void getMethodsForImpactedFile(String originalFileContent, String newFilContent, String originalFileName,
                                             String revisedFileName, Set<String> methodsImpacted,
                                             Map<String, List<MethodImpactTracker>> classesImpacted){

        List<String> originalFileLines = Arrays.asList(originalFileContent.split(LINEBREAK_PATTERN));
        List<String> newFileLines = Arrays.asList(newFilContent.split(LINEBREAK_PATTERN));

        try {
            // create the diff between the original and revised file content
            Patch<String> diff = DiffUtils.diff(originalFileLines, newFileLines);

            // generating unified diff format so the line numbers are consistent with the source file
            List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(originalFileName, revisedFileName, originalFileLines, diff, 0);

            // Parse the original content as a Java file and check each method to see if it's in the diff based on the
            // file line numbers and the diff segment line numbers
            MethodVisitorContext methodVisitorContext = new MethodVisitorContext();
            methodVisitorContext.setMethodsImpacted(methodsImpacted);
            VoidVisitor<MethodVisitorContext> methodNameVisitor = new MethodImpactVisitor();

            CompilationUnit cu = StaticJavaParser.parse(originalFileContent);

            List<String> classNameList = new ArrayList<>();
            VoidVisitor<List<String>> classNameVisitor = new ClassNameCollector();
            classNameVisitor.visit(cu, classNameList);

            for (String className : classNameList){
                methodVisitorContext.getMethodImpactTrackers().addAll(classesImpacted.get(className));
            }

            /*
            classesImpacted.forEach( (key, val) -> {
                System.out.println(System.lineSeparator() + "key: " + key + " val: " + System.lineSeparator() + "\t" + val.stream().map( mt -> mt.getMethodName() + " " + mt.getMethodLineNumber() ).collect(Collectors.joining(System.lineSeparator() + "\t", "", "")));
            });
             */

            unifiedDiff.forEach( patchDiff -> {
                setImpactedLineBeginEnd(patchDiff, methodVisitorContext);
                methodNameVisitor.visit(cu, methodVisitorContext);
            });

        } catch (DiffException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

    }

    /**
     * Parse the diff 'hunk' using regex to get the line numbers for the change for the original file.
     * i.e. @@ -11,2 +11,2 @@ i.e. this means old file - line 11, 2 lines (get replaced in new file, starting line 11 for 2 lines).
     * https://www.gnu.org/software/diffutils/manual/html_node/Detailed-Unified.html
     *
     * @param patchDiff
     * @param methodVisitorContext
     */
    private void setImpactedLineBeginEnd(String patchDiff, MethodVisitorContext methodVisitorContext){
        Matcher matcher = UNIFIED_DIFF_HUNK_PATTERN.matcher(patchDiff);

        if  (matcher.matches()){
            log.debug("Diff hunk: " + patchDiff);
            int revisionLineBegin = Integer.parseInt(matcher.group(HUNK_DIFF_ORIG_LINE_START_GROUP_INDEX));
            int revisionLineCount = Integer.parseInt(matcher.group(HUNK_DIFF_ORIG_LINE_START_COUNT_GROUP_INDEX));

            // if the hunk line count is 0 (i.e. line added) then treat it as 1 line.
            revisionLineCount = revisionLineCount <= 0 ? 1 : revisionLineCount;

            methodVisitorContext.setImpactedLineNumBegin(revisionLineBegin);
            methodVisitorContext.setImpactedLineNumEnd(revisionLineBegin + (revisionLineCount - 1));

            log.debug("getImpactedLineNumBegin: " + methodVisitorContext.getImpactedLineNumBegin() + " getImpactedLineNumEnd: " + methodVisitorContext.getImpactedLineNumEnd());
        }
    }

    private static class ClassNameCollector extends VoidVisitorAdapter<List<String>>{
        @Override
        public void visit(ClassOrInterfaceDeclaration n, List<String> collector) {
            super.visit(n, collector);

            if (n.getFullyQualifiedName().isPresent()){
                // convert the fully qualified name using dots to use forward slashes, consistent with the ASM byte code internal naming convention
                // https://asm.ow2.io/asm4-guide.pdf Section 2.1.2 Internal names
                String className = n.getFullyQualifiedName().get().replaceAll("\\.", "/");

                if (n.isNestedType()){
                    // nested/inner classes should have a $ in the ASM naming convention i.e. com/example/DoorService$StaticNestedClass
                    className = className.replaceAll("/" + n.getName().asString(), "\\$" +  n.getName().asString());
                }

                collector.add(className);
            }
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

            // if there are no stored tracked methods for the class then don't both trying to find one
            if (arg.getMethodImpactTrackers() == null || arg.getMethodImpactTrackers().size() == 0){
                return;
            }

            /**
             * Check if any of the impacted code line numbers are within the current methods line numbers.
             *
             * IM = impacted method (from VCS).
             * Check start point is within method:			    IM start > = method start && IM start <= method end
             * Check end point is within method:			    IM end  >= method start && IM end <= method end
             * Check impacted code range covers the method: 	IM start <= method start && IM end >= method end
             */
            int impactedCodeLineBegin = arg.getImpactedLineNumBegin();
            int impactedCodeLineEnd = arg.getImpactedLineNumEnd();
            int methodLineBegin = md.getBegin().get().line;
            int methodLineEnd = md.getEnd().get().line;

            boolean impactedChangeBeginIsWithinMethod = impactedCodeLineBegin >= methodLineBegin && impactedCodeLineBegin <= methodLineEnd;
            boolean impactedChangeEndIsWithinMethod = impactedCodeLineEnd >= methodLineBegin && impactedCodeLineEnd <= methodLineEnd;
            boolean impactedChangeRangeCoversMethod = impactedCodeLineBegin <= methodLineBegin && impactedCodeLineEnd >= methodLineEnd;

            if (impactedChangeBeginIsWithinMethod || impactedChangeEndIsWithinMethod || impactedChangeRangeCoversMethod){

                MethodImpactTracker methodImpactTracker = getMethodImpactTracker(methodLineBegin, methodLineEnd, arg.getMethodImpactTrackers());

                if (methodImpactTracker != null && !arg.getMethodsImpacted().contains(methodImpactTracker.getMethodName())){
                    arg.getMethodsImpacted().add(methodImpactTracker.getMethodName());

                    log.info(String.format("Found stored tracked method: %s, source line begin: %d, source line end: %d, stored line number: %d",
                            methodImpactTracker.getMethodName(), methodLineBegin, methodLineEnd, methodImpactTracker.getMethodLineNumber()));
                }
            }
        }
    }

    /**
     * Find the associated stored impacted method using the given beginning and end line numbers.
     *
     * We use the stored impacted method tracker object as the source of truth for the method name. The reason being,
     * the method name is stored using ASM's internal naming convention from byte code (via Jacoco).
     *
     * Rather than trying to reconstruct this name from source code we simply use the name from the stored impacted list.
     * To match them, we have a source line number associated with the impacted method tracker object. This line number
     * is derived via Jacoco after loading the compiled classes and source files.
     *
     * Given the beginning and end line numbers for a method from a source file, loop through and see if any of the
     * impacted methods stored for the class have their line number within the given source code method.
     *
     * if so, we have a match and can use the name from the stored method tracker object.
     *
     * Why do we use name from the stored method tracker object? Because trying to reconstruct it manually from a parsed
     * method (using JavaParser) is costly and difficult. To do it accurately you need to checkout a copy of all the source
     * files at the "from" version temporarily onto disk and load these into JavaParser.
     * This allows getting the fully qualified name of types declared within the source code but it doesn't work for
     * types declared in libraries. To get that working (which I never did), you would also need to retrieve somehow and
     * then load in all the library Jars (with the correct versions) for the application.
     *
     * @param methodLineBegin
     * @param methodLineEnd
     * @param methodImpactTrackers
     * @return
     */
    private static MethodImpactTracker getMethodImpactTracker(final int methodLineBegin, final int methodLineEnd, List<MethodImpactTracker> methodImpactTrackers){
        for (MethodImpactTracker methodImpactTracker : methodImpactTrackers){
            if (methodImpactTracker.getMethodLineNumber() >= methodLineBegin && methodImpactTracker.getMethodLineNumber() <= methodLineEnd){
                return methodImpactTracker;
            }
        }
        return null;
    }

    private static class MethodVisitorContext  {
        Set<String> methodsImpacted;
        int impactedLineNumBegin;
        int impactedLineNumEnd;
        List<MethodImpactTracker> methodImpactTrackers = new ArrayList<>();

        public Set<String> getMethodsImpacted() {
            return methodsImpacted;
        }

        public void setMethodsImpacted(Set<String> methodsImpacted){
            this.methodsImpacted = methodsImpacted;
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

        public List<MethodImpactTracker> getMethodImpactTrackers() {
            return methodImpactTrackers;
        }

        public void setMethodImpactTrackers(List<MethodImpactTracker> methodImpactTrackers) {
            this.methodImpactTrackers = methodImpactTrackers;
        }
    }
}
