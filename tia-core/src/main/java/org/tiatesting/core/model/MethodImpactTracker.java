package org.tiatesting.core.model;

import org.tiatesting.core.sourcefile.FileExtensions;

import java.util.Objects;

public class MethodImpactTracker {

    /**
     * This is the full package.class name + method name + method signature.
     * For example: com/example/HandleService.getHandleModel.(Ljava/lang/Long;)Ljava/lang/String
     */
    private final String methodName;
    private final int lineNumberStart;
    private final int lineNumberEnd;

    /**
     * The exact source lines a change must touch to impact this method, as flat inclusive
     * {@code [start1, end1, start2, end2, ...]} pairs in ascending order, or {@code null} when the
     * method is matched by its padded {@link #lineNumberStart}-{@link #lineNumberEnd} range.
     * Only set for constructors and static initializers whose range is split by other members:
     * the compiler folds every field initializer into them, so a field declared after other methods
     * stretches their start-end range over those methods. The ranges already include the
     * signature/closing-brace allowance, so they are matched without extra padding.
     * See the "Constructor and static initializer line ranges" chapter in {@code WIKI.md}.
     */
    private final int[] lineRanges;

    /**
     * The number of mapping-update runs that executed this method. Accumulated at the seal and
     * stored on the method catalogue; zero for a tracker that was not read from the catalogue.
     */
    private long executedRunCount;

    /**
     * The number of mapping-update runs this method triggered by being changed. Accumulated at the
     * seal and stored on the method catalogue; zero for a tracker that was not read from the
     * catalogue.
     */
    private long triggeredRunCount;

    /**
     * Creates a tracker for a method matched by its contiguous start-end line range.
     *
     * @param methodName the full class + method name + descriptor
     * @param lineNumberStart the first line with code in the method
     * @param lineNumberEnd the last line with code in the method
     */
    public MethodImpactTracker(String methodName, int lineNumberStart, int lineNumberEnd) {
        this(methodName, lineNumberStart, lineNumberEnd, null);
    }

    /**
     * Creates a tracker for a method, optionally carrying the exact line ranges used to match
     * source changes when its start-end range is split by other members (see {@link #lineRanges}).
     *
     * @param methodName the full class + method name + descriptor
     * @param lineNumberStart the first line with code in the method
     * @param lineNumberEnd the last line with code in the method
     * @param lineRanges flat inclusive start/end pairs, or {@code null} to match by start-end
     */
    public MethodImpactTracker(String methodName, int lineNumberStart, int lineNumberEnd, int[] lineRanges) {
        this.methodName = methodName;
        this.lineNumberStart = lineNumberStart;
        this.lineNumberEnd = lineNumberEnd;
        this.lineRanges = lineRanges;
    }

    public String getMethodName() {
        return methodName;
    }

    public int getLineNumberStart() {
        return lineNumberStart;
    }

    public int getLineNumberEnd() {
        return lineNumberEnd;
    }

    /**
     * The exact line ranges a change must touch to impact this method, when its start-end range is
     * split by other members. See {@link #lineRanges} for the format.
     *
     * @return flat inclusive start/end pairs, or {@code null} when the method is matched by its
     *         padded start-end range
     */
    public int[] getLineRanges() {
        return lineRanges;
    }

    /**
     * The lines a source change must touch to impact this method. For a method with exact line
     * ranges (see {@link #lineRanges}) that is those ranges. Otherwise it is the method's first to
     * last code line widened by one line either side, so edits to its signature line and closing
     * brace also count - the allowance the diff matcher has always applied to start-end matching.
     *
     * @return flat inclusive {@code [start, end, ...]} pairs in ascending order; never {@code null}
     */
    public int[] getMatchedLineRanges() {
        return lineRanges != null ? lineRanges : new int[]{lineNumberStart - 1, lineNumberEnd + 1};
    }

    /**
     * The number of mapping-update runs that executed this method, counted once per sealed run.
     *
     * @return the stored executed-run count
     */
    public long getExecutedRunCount() {
        return executedRunCount;
    }

    /**
     * Set the number of mapping-update runs that executed this method.
     *
     * @param executedRunCount the executed-run count to store on the tracker
     */
    public void setExecutedRunCount(long executedRunCount) {
        this.executedRunCount = executedRunCount;
    }

    /**
     * The number of mapping-update runs this method triggered by being changed, counted once per
     * sealed run.
     *
     * @return the stored triggered-run count
     */
    public long getTriggeredRunCount() {
        return triggeredRunCount;
    }

    /**
     * Set the number of mapping-update runs this method triggered by being changed.
     *
     * @param triggeredRunCount the triggered-run count to store on the tracker
     */
    public void setTriggeredRunCount(long triggeredRunCount) {
        this.triggeredRunCount = triggeredRunCount;
    }

    /**
     * The full method name rendered for display, with the internal {@code /} package separators
     * turned into {@code .}. Uses {@link String#replace(char, char)} (a single-pass char scan)
     * rather than {@code replaceAll}, which compiled a regex {@link java.util.regex.Pattern} on
     * every call - this method is invoked per cell across the millions of HTML report table cells,
     * so the regex compilation dominated the render, as measured by the {@code profileHtmlReport}
     * harness. The output is byte-identical to the previous {@code replaceAll("/", ".")}.
     *
     * @return the method name with {@code /} replaced by {@code .}
     */
    public String getNameForDisplay() {
        return methodName.replace('/', '.');
    }

    /**
     * Display name with the parameter list and return descriptor stripped — everything from the
     * first {@code (} onwards is removed, along with the trailing {@code .} that separates the
     * method name from the signature. Used in the HTML report to keep table cells and headings
     * narrow; the full signature is exposed as a {@code title} tooltip on hover.
     *
     * @return 1st part of the method name, with parameter list and return descriptor stripped.
     */
    public String getShortNameForDisplay() {
        String full = getNameForDisplay();
        int paren = full.indexOf('(');
        if (paren < 0) {
            return full;
        }
        String head = full.substring(0, paren);
        if (head.endsWith(".")) {
            head = head.substring(0, head.length() - 1);
        }
        return head;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodImpactTracker that = (MethodImpactTracker) o;
        return Objects.equals(methodName, that.methodName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodName);
    }
}
