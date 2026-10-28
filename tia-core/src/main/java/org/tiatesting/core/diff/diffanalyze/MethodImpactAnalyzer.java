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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final int HUNK_DIFF_REVISED_LINE_START_GROUP_INDEX = 3;
    private static final int HUNK_DIFF_REVISED_LINE_COUNT_GROUP_INDEX = 4;
    private static final Pattern STATEMENT_BLOCK_OPENER_PATTERN = Pattern.compile("^(try|do|while|for)\\b.*\\{$");

    /**
     * Compare two versions of a source file and create a unified diff.
     * Parse the original content as a Java file and check each method to see if it's in the diff based on the
     * file line numbers and the diff segment line numbers.
     *
     * <p>Logs one DEBUG summary line per file - its hunk ranges, its tracked method count and how many
     * of them matched - plus one DEBUG line per match. The tracked methods' widened ranges are logged
     * at TRACE, so the DEBUG output grows with the matches rather than with hunks x methods.
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
            String mappingKey = SourceFilenameUtil.normalizeToMappingKey(originalFileName, sourceFilesDirs);
            Map<Integer, MethodImpactTracker> methodsTrackedForSourceFile = methodsTrackedByFile.get(mappingKey);

            if (methodsTrackedForSourceFile != null && !methodsTrackedForSourceFile.isEmpty()){
                Map<String, Set<Integer>> methodIdsByClass = groupMethodIdsByClassPath(methodsTrackedForSourceFile);
                Map<String, int[]> declarationLinesByClass = ClassDeclarationScanner.scan(originalFileLines);
                Map<Integer, int[]> widenedLineRanges = widenMethodLineRanges(methodsTrackedForSourceFile,
                        originalFileLines);
                Set<Integer> methodsImpactedInFile = new HashSet<>();
                List<String> hunkRanges = new ArrayList<>();

                for (String patchDiff : unifiedDiff) {
                    DiffContext diffContext = new DiffContext();
                    setImpactedLineBeginEnd(patchDiff, diffContext);

                    if (diffContext.isUnifiedDiff()){
                        hunkRanges.add(formatHunkRange(diffContext));
                        findTrackedMethodsForSourceDiff(diffContext, methodsTrackedForSourceFile, widenedLineRanges,
                                methodsImpactedInFile);
                        findTrackedClassesForDeclarationDiff(diffContext, declarationLinesByClass, methodIdsByClass,
                                newFileLines, methodsImpactedInFile);
                    }
                }

                methodsInvokedByChanges.addAll(methodsImpactedInFile);
                log.debug("{}: {} hunk(s) {} against {} tracked method(s), {} matched", mappingKey,
                        hunkRanges.size(), hunkRanges, methodsTrackedForSourceFile.size(),
                        methodsImpactedInFile.size());
            } else {
                log.debug("{}: no tracked methods", mappingKey);
            }
        } catch (DiffException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Work out, once per file, the line range each start-end tracked method is matched against: its
     * padded stored range with the begin widened upwards to the top of its declaration (see
     * {@link #widenStartToDeclaration(int, List)}). The range depends only on the method, not on
     * the diff hunk, so computing it here rather than per hunk keeps the matching cost at hunks plus
     * methods for the widening walk. Methods carrying exact line ranges are matched against those
     * and get no entry.
     *
     * <p>Each method's range is logged at TRACE, one line per method. Together with the hunk ranges
     * in the per-file DEBUG summary, these lines rebuild every hunk-to-method comparison, for
     * debugging a method that should have matched and didn't. TRACE is dropped by the Gradle daemon,
     * so only Maven can show them - see "Logging conventions (TRACE vs DEBUG)" in {@code WIKI.md}.
     *
     * @param methodsTrackedForSourceFile the tracked methods (by id) for the changed file
     * @param originalFileLines the original (pre-change) file content, line 1 at index 0
     * @return the widened inclusive {@code [begin, end]} range per start-end method id
     */
    private Map<Integer, int[]> widenMethodLineRanges(final Map<Integer, MethodImpactTracker> methodsTrackedForSourceFile,
                                                      final List<String> originalFileLines){
        Map<Integer, int[]> widenedLineRanges = new HashMap<>();
        boolean traceEnabled = log.isTraceEnabled();
        for (Map.Entry<Integer, MethodImpactTracker> trackedMethod : methodsTrackedForSourceFile.entrySet()) {
            MethodImpactTracker methodImpactTracker = trackedMethod.getValue();
            if (methodImpactTracker.getLineRanges() != null) {
                if (traceEnabled) {
                    log.trace("Tracked method {}, stored line ranges: {}", methodImpactTracker.getMethodName(),
                            LineRanges.format(methodImpactTracker.getLineRanges()));
                }
                continue;
            }

            // start-end widened by one line either side, to catch changes to the method name line and
            // the closing brace, then upwards to the top of the method's declaration (its annotations and
            // a multi-line signature)
            int[] matchedLineRange = methodImpactTracker.getMatchedLineRanges();
            int[] widened = new int[]{widenStartToDeclaration(matchedLineRange[0], originalFileLines),
                    matchedLineRange[1]};
            widenedLineRanges.put(trackedMethod.getKey(), widened);
            if (traceEnabled) {
                log.trace("Tracked method {}, method line begin: {}, method line end: {}",
                        methodImpactTracker.getMethodName(), widened[0], widened[1]);
            }
        }
        return widenedLineRanges;
    }

    /**
     * Format a hunk's original-side lines for the per-file DEBUG summary: {@code 31-33} for a hunk
     * that changes or removes original lines 31 to 33, or {@code +31} for a pure insertion
     * ({@code -31,0}), which changes no original line.
     *
     * @param diffContext the parsed diff hunk
     * @return the hunk's line range as shown in the summary
     */
    private static String formatHunkRange(final DiffContext diffContext){
        if (diffContext.isInsertion()) {
            return "+" + diffContext.getImpactedLineNumBegin();
        }
        return diffContext.getImpactedLineNumBegin() + "-" + diffContext.getImpactedLineNumEnd();
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
     * A method matched by start-end is compared with its widened range from
     * {@link #widenMethodLineRanges(Map, List)}: its begin widened upwards to the top of its
     * declaration - over blank and comment lines above its first code line, then over its signature
     * and annotation lines.
     *
     * <p>Only matches are logged, at DEBUG. A comparison that doesn't match logs nothing: logging every
     * hunk and method pair grows with hunks x methods and swamped large selections.
     *
     * @param diffContext the parsed diff hunk carrying the impacted line range
     * @param methodsTrackedForSourceFile the tracked methods (by id) for the changed file
     * @param widenedLineRanges the widened {@code [begin, end]} range per start-end method id, from
     *                          {@link #widenMethodLineRanges(Map, List)}
     * @param methodsInvokedByChanges accumulator for the ids of methods the diff impacts
     */
    private void findTrackedMethodsForSourceDiff(final DiffContext diffContext,
                                                 final Map<Integer, MethodImpactTracker> methodsTrackedForSourceFile,
                                                 final Map<Integer, int[]> widenedLineRanges,
                                                 final Set<Integer> methodsInvokedByChanges){
        int diffLineBegin = diffContext.getImpactedLineNumBegin();
        int diffLineEnd = diffContext.getImpactedLineNumEnd();
        for (Map.Entry<Integer, MethodImpactTracker> trackedMethod : methodsTrackedForSourceFile.entrySet()) {
            Integer methodHashcode = trackedMethod.getKey();
            MethodImpactTracker methodImpactTracker = trackedMethod.getValue();

            if (methodImpactTracker.getLineRanges() != null) {
                if (diffTouchesLineRanges(diffContext, methodImpactTracker.getLineRanges())) {
                    methodsInvokedByChanges.add(methodHashcode);
                    log.debug("Found stored tracked method: {}, diff line begin: {}, diff line end: {}, stored line ranges: {}",
                            methodImpactTracker.getMethodName(), diffLineBegin, diffLineEnd,
                            LineRanges.format(methodImpactTracker.getLineRanges()));
                }
                continue;
            }

            int[] widened = widenedLineRanges.get(methodHashcode);
            int methodLineBegin = widened[0];
            int methodLineEnd = widened[1];

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
     * Group a file's tracked method ids by the class they belong to, keyed by the class's nesting path
     * within the file - its internal name without the package, e.g. {@code Outer} or
     * {@code Outer$Inner} for {@code com/example/Outer$Inner.method.()V}. This is the key
     * {@link ClassDeclarationScanner#scan(List)} uses.
     *
     * @param methodsTrackedForSourceFile the tracked methods (by id) for the changed file
     * @return the method ids per class nesting path
     */
    private static Map<String, Set<Integer>> groupMethodIdsByClassPath(
            final Map<Integer, MethodImpactTracker> methodsTrackedForSourceFile){
        Map<String, Set<Integer>> methodIdsByClass = new HashMap<>();
        for (Map.Entry<Integer, MethodImpactTracker> trackedMethod : methodsTrackedForSourceFile.entrySet()) {
            String methodName = trackedMethod.getValue().getMethodName();
            int classEnd = methodName.indexOf('.');
            String className = classEnd < 0 ? methodName : methodName.substring(0, classEnd);
            String classPath = className.substring(className.lastIndexOf('/') + 1);
            methodIdsByClass.computeIfAbsent(classPath, key -> new HashSet<>()).add(trackedMethod.getKey());
        }
        return methodIdsByClass;
    }

    /**
     * Impact every tracked method of a class when a diff hunk touches the class's class-level
     * declaration lines - its header (annotations, declaration, {@code extends}/{@code implements}) or
     * a field declaration with its annotations (see {@link ClassDeclarationScanner}). Those lines change
     * the behaviour of the class as a whole, and no method's line range covers them. Each impacted
     * method becomes its own source-method trigger downstream.
     *
     * @param diffContext the parsed diff hunk
     * @param declarationLinesByClass the class-level lines per class nesting path, from the original file
     * @param methodIdsByClass the tracked method ids per class nesting path
     * @param newFileLines the revised file content, line 1 at index 0, for reading inserted lines
     * @param methodsInvokedByChanges accumulator for the ids of methods the diff impacts
     */
    private void findTrackedClassesForDeclarationDiff(final DiffContext diffContext,
                                                      final Map<String, int[]> declarationLinesByClass,
                                                      final Map<String, Set<Integer>> methodIdsByClass,
                                                      final List<String> newFileLines,
                                                      final Set<Integer> methodsInvokedByChanges){
        for (Map.Entry<String, Set<Integer>> trackedClass : methodIdsByClass.entrySet()) {
            int[] declarationLines = declarationLinesByClass.get(trackedClass.getKey());
            if (declarationLines != null && diffTouchesDeclarationLines(diffContext, declarationLines, newFileLines)) {
                methodsInvokedByChanges.addAll(trackedClass.getValue());
                log.debug("Class-level declaration change in {}, diff line begin: {}, diff line end: {}, impacting its {} tracked methods",
                        trackedClass.getKey(), diffContext.getImpactedLineNumBegin(),
                        diffContext.getImpactedLineNumEnd(), trackedClass.getValue().size());
            }
        }
    }

    /**
     * Check whether a diff hunk changes a class's class-level declaration lines. A hunk that changes
     * or removes original lines counts when it overlaps them. A pure insertion ({@code -N,0}, new
     * lines before original line N) counts only when line N is a declaration line and the inserted
     * lines continue that declaration - e.g. an annotation added above a field or the class - rather
     * than adding a new member: they hold code, and every code line is a declaration line (see
     * {@link #isDeclarationLine(String)}), so a new field or method, which ends with <code>;</code>
     * or a brace, doesn't count.
     *
     * @param diffContext the parsed diff hunk
     * @param declarationLines the class's class-level lines as flat inclusive {@code [start, end, ...]} pairs
     * @param newFileLines the revised file content, line 1 at index 0
     * @return true when the hunk changes the class's declaration
     */
    private boolean diffTouchesDeclarationLines(final DiffContext diffContext, final int[] declarationLines,
                                                final List<String> newFileLines){
        if (diffContext.isInsertion()) {
            int insertedBefore = diffContext.getImpactedLineNumBegin();
            return rangesTouch(declarationLines, insertedBefore, insertedBefore)
                    && insertionContinuesDeclaration(diffContext, newFileLines);
        }
        return rangesTouch(declarationLines, diffContext.getImpactedLineNumBegin(), diffContext.getImpactedLineNumEnd());
    }

    /**
     * Whether a pure insertion's new lines only continue the declaration below them: at least one code
     * line, and every code line a declaration line (an annotation, modifier or signature part).
     *
     * @param diffContext the parsed insertion hunk, carrying the revised-side line range
     * @param newFileLines the revised file content, line 1 at index 0
     * @return true when the inserted lines continue a declaration rather than add a member
     */
    private static boolean insertionContinuesDeclaration(final DiffContext diffContext, final List<String> newFileLines){
        int first = diffContext.getRevisedLineBegin();
        int last = Math.min(first + diffContext.getRevisedLineCount() - 1, newFileLines.size());
        boolean hasCode = false;
        for (int lineNumber = Math.max(first, 1); lineNumber <= last; lineNumber++) {
            String line = newFileLines.get(lineNumber - 1);
            if (isBlankOrCommentLine(line)) {
                continue;
            }
            if (!isDeclarationLine(line)) {
                return false;
            }
            hasCode = true;
        }
        return hasCode;
    }

    /**
     * Whether an inclusive line range overlaps any of a set of ranges.
     *
     * @param lineRanges flat inclusive {@code [start, end, ...]} pairs
     * @param begin the 1-based first line
     * @param end the 1-based last line
     * @return true when the range overlaps at least one pair
     */
    private static boolean rangesTouch(final int[] lineRanges, final int begin, final int end){
        for (int i = 0; i < lineRanges.length; i += 2) {
            if (begin <= lineRanges[i + 1] && end >= lineRanges[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Widen a method's matched begin line upwards to the top of the method's declaration. JaCoCo's
     * first line for a method is its first line with code, not its signature, and the one-line padding
     * only reaches the line above that. Without widening, code added below the signature above blank or
     * comment lines, a modifier on an earlier line of a multi-line signature, or an annotation edit
     * (e.g. {@code @Transactional}) wouldn't impact the method. Two walks, both upwards:
     * <ol>
     *   <li>Over the blank and comment-only lines directly above the padded begin, stopping at and
     *       including the first line with anything else on it - normally the signature line. A
     *       statement block opener with no code of its own (e.g. <code>try &#123;</code> at the top of the
     *       body) is stepped over and the walk repeated - see
     *       {@link #stepOverStatementBlockOpeners(int, List)}.</li>
     *   <li>Unless that line ends the previous member (it ends with <code>;</code> or
     *       <code>&#125;</code>, as for a one-line method or a constructor whose first line is its
     *       signature), on over the lines directly above it that belong to the declaration - see
     *       {@link #isDeclarationLine(String)}. This stops at a blank line, at a comment (the method's
     *       Javadoc) and at the end of the previous member.</li>
     * </ol>
     * This only widens the range, so at worst a few extra tests are selected.
     * See the "Matching a diff to tracked methods" chapter in {@code WIKI.md}.
     *
     * @param matchedLineBegin the 1-based padded begin line (the line before the first code line)
     * @param originalFileLines the original (pre-change) file content, line 1 at index 0
     * @return the 1-based widened begin line, never greater than {@code matchedLineBegin}
     */
    static int widenStartToDeclaration(final int matchedLineBegin, final List<String> originalFileLines){
        if (matchedLineBegin < 1 || matchedLineBegin > originalFileLines.size()) {
            // the stored lines don't fit the file (stale mapping) - keep the padded begin
            return matchedLineBegin;
        }

        int lineNumber = stepOverStatementBlockOpeners(
                walkOverBlankAndCommentLines(matchedLineBegin, originalFileLines), originalFileLines);

        if (endsStatementOrBlock(codeWithoutTrailingComment(originalFileLines.get(lineNumber - 1)))) {
            // the walk landed on the previous member's last line, not this method's signature
            return lineNumber;
        }

        while (lineNumber > 1 && isDeclarationLine(originalFileLines.get(lineNumber - 2))) {
            lineNumber--;
        }
        return lineNumber;
    }

    /**
     * Walk upwards from a line over blank and comment-only lines.
     *
     * @param lineNumber the 1-based line to start from, within the file
     * @param originalFileLines the original (pre-change) file content, line 1 at index 0
     * @return the 1-based first line at or above {@code lineNumber} that isn't blank or comment-only,
     *         or line 1
     */
    private static int walkOverBlankAndCommentLines(final int lineNumber, final List<String> originalFileLines){
        int current = lineNumber;
        while (current > 1 && isBlankOrCommentLine(originalFileLines.get(current - 1))) {
            current--;
        }
        return current;
    }

    /**
     * Step upwards over statement block openers that sit at the top of a method body with no code on
     * their own line (see {@link #opensStatementBlock(String)}), walking over the blank and comment
     * lines above each. JaCoCo's first code line is then inside the block, so the walk lands on the
     * opener rather than the signature. An opening brace on its own line (Allman style) is taken
     * together with the line above it, so {@code try} followed by a lone brace is stepped over too,
     * while a signature followed by a lone brace is not.
     *
     * @param lineNumber the 1-based line the blank and comment walk stopped at
     * @param originalFileLines the original (pre-change) file content, line 1 at index 0
     * @return the 1-based line above any openers, or {@code lineNumber} when it isn't one
     */
    private static int stepOverStatementBlockOpeners(final int lineNumber, final List<String> originalFileLines){
        int current = lineNumber;
        while (current > 1) {
            int openerLine = current;
            String code = codeWithoutTrailingComment(originalFileLines.get(current - 1));
            if (code.equals("{")) {
                // Allman style: the opener's keyword sits on the line above its brace
                openerLine = walkOverBlankAndCommentLines(current - 1, originalFileLines);
                code = codeWithoutTrailingComment(originalFileLines.get(openerLine - 1)) + " {";
            }
            if (!opensStatementBlock(code)) {
                break;
            }
            current = openerLine > 1 ? walkOverBlankAndCommentLines(openerLine - 1, originalFileLines) : 1;
        }
        return current;
    }

    /**
     * Whether a line of code opens a statement block that can sit at the top of a method body with no
     * code on its own line - {@code try}, {@code do}, or a {@code while}/{@code for} loop whose
     * condition compiles to no instructions there (e.g. {@code while (true)}). A declaration line never
     * starts with these keywords, so stepping over such a line never skips past a signature.
     *
     * @param code the trimmed code, without a trailing line comment
     * @return true when the code starts with one of the keywords and ends with an opening brace
     */
    private static boolean opensStatementBlock(final String code){
        return STATEMENT_BLOCK_OPENER_PATTERN.matcher(code).matches();
    }

    /**
     * Whether a source line holds nothing but whitespace or comment text. Recognises line comments,
     * the start of a block comment, block comment continuation lines ({@code *}) and a line closing a
     * block comment. The markers are shared by Java and Groovy, the languages Tia diffs.
     *
     * @param line the source line
     * @return true when the line is blank or comment-only
     */
    private static boolean isBlankOrCommentLine(final String line){
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")
                || trimmed.startsWith("*");
    }

    /**
     * Whether a source line above a method's signature still belongs to the method's declaration: an
     * annotation (including the lines of a multi-line annotation) or an earlier line of a multi-line
     * signature. A blank or comment line doesn't, nor does a line ending a statement or block
     * (<code>;</code> or <code>&#125;</code>), nor one opening a block (<code>&#123;</code>, e.g. the class
     * declaration) - except an annotation line opening an array argument, such as
     * <code>@SuppressWarnings(&#123;</code>.
     *
     * @param line the source line
     * @return true when the line is part of the declaration below it
     */
    private static boolean isDeclarationLine(final String line){
        if (isBlankOrCommentLine(line)) {
            return false;
        }
        String code = codeWithoutTrailingComment(line);
        if (endsStatementOrBlock(code)) {
            return false;
        }
        return !code.endsWith("{") || code.startsWith("@");
    }

    /**
     * Whether a line of code ends a statement or a block, i.e. it is the last line of a member.
     *
     * @param code the trimmed code, without a trailing line comment
     * @return true when the code ends with <code>;</code> or <code>&#125;</code>
     */
    private static boolean endsStatementOrBlock(final String code){
        return code.endsWith(";") || code.endsWith("}");
    }

    /**
     * Trim a source line and drop a trailing line comment, so the line's last code character can be
     * checked. A {@code //} inside a string literal is cut too; that can only leave a line looking
     * unterminated, which widens the range rather than narrowing it.
     *
     * @param line the source line
     * @return the trimmed code before any {@code //}
     */
    private static String codeWithoutTrailingComment(final String line){
        String trimmed = line.trim();
        int commentStart = trimmed.indexOf("//");
        return commentStart < 0 ? trimmed : trimmed.substring(0, commentStart).trim();
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
        return rangesTouch(lineRanges, diffLineBegin, diffLineEnd);
    }

    /**
     * Parse the diff 'hunk' using regex to get the line numbers for the change for the original file.
     * i.e. @@ -11,2 +11,2 @@ i.e. this means old file - line 11, 2 lines (get replaced in new file, starting line 11 for 2 lines).
     * https://www.gnu.org/software/diffutils/manual/html_node/Detailed-Unified.html
     *
     * <p>The raw hunk header is logged at TRACE; its line range is in the per-file DEBUG summary.
     *
     * @param patchDiff one line of the unified diff; only a hunk header line is parsed
     * @param sourceFileDiffContext the context to fill in when the line is a hunk header
     */
    private void setImpactedLineBeginEnd(String patchDiff, DiffContext sourceFileDiffContext){
        Matcher matcher = UNIFIED_DIFF_HUNK_PATTERN.matcher(patchDiff);

        if  (matcher.matches()){
            log.trace("Diff hunk: {}", patchDiff);
            sourceFileDiffContext.setUnifiedDiff(true);

            int revisionLineBegin = Integer.parseInt(matcher.group(HUNK_DIFF_ORIG_LINE_START_GROUP_INDEX));
            int revisionLineCount = Integer.parseInt(matcher.group(HUNK_DIFF_ORIG_LINE_START_COUNT_GROUP_INDEX));

            // if the hunk line count is 0 (i.e. line added) then treat it as 1 line.
            sourceFileDiffContext.setInsertion(revisionLineCount == 0);
            revisionLineCount = revisionLineCount <= 0 ? 1 : revisionLineCount;

            sourceFileDiffContext.setImpactedLineNumBegin(revisionLineBegin);
            sourceFileDiffContext.setImpactedLineNumEnd(revisionLineBegin + (revisionLineCount - 1));
            sourceFileDiffContext.setRevisedLineBegin(Integer.parseInt(matcher.group(HUNK_DIFF_REVISED_LINE_START_GROUP_INDEX)));
            sourceFileDiffContext.setRevisedLineCount(Integer.parseInt(matcher.group(HUNK_DIFF_REVISED_LINE_COUNT_GROUP_INDEX)));
        }
    }

    private static class DiffContext {
        boolean unifiedDiff;
        boolean insertion;
        int impactedLineNumBegin;
        int impactedLineNumEnd;
        int revisedLineBegin;
        int revisedLineCount;

        /**
         * The first line of the hunk in the revised file ({@code +M} in {@code +M,K}).
         *
         * @return the 1-based first revised line
         */
        public int getRevisedLineBegin() {
            return revisedLineBegin;
        }

        /**
         * Record the first line of the hunk in the revised file.
         *
         * @param revisedLineBegin the 1-based first revised line
         */
        public void setRevisedLineBegin(int revisedLineBegin) {
            this.revisedLineBegin = revisedLineBegin;
        }

        /**
         * The number of revised-file lines in the hunk ({@code K} in {@code +M,K}); zero for a pure
         * deletion.
         *
         * @return the revised line count
         */
        public int getRevisedLineCount() {
            return revisedLineCount;
        }

        /**
         * Record the number of revised-file lines in the hunk.
         *
         * @param revisedLineCount the revised line count
         */
        public void setRevisedLineCount(int revisedLineCount) {
            this.revisedLineCount = revisedLineCount;
        }

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
