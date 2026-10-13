package org.tiatesting.core.diff.diffanalyze;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the class-level declaration lines of a Java or Groovy source file: the lines that change the
 * behaviour of a whole class rather than one method, so a diff touching them impacts every tracked
 * method of the class. These are:
 * <ul>
 *   <li>the class header - its annotations, the declaration line and a multi-line
 *       {@code extends}/{@code implements} clause, down to the line with the class's opening brace;</li>
 *   <li>each member that ends with a semicolon at class-body level - a field with its annotations,
 *       an enum constant list, or an abstract/interface method.</li>
 * </ul>
 * A field with an initializer is different: the initializer compiles into the constructor or static
 * initializer, whose line ranges already match it, so only its annotation lines above the line with
 * the {@code =} are class-level. The exception is a {@code static final} field (or any field of an
 * interface or annotation type, which is implicitly {@code static final}): a constant with a literal
 * value is inlined by the compiler and has no initializer code, so its whole declaration is
 * class-level. Methods, constructors and initializer blocks are not class-level: their lines are
 * matched to the method itself. Comments, including Javadoc, are never part of a range.
 *
 * <p>The scan is a single pass that tracks brace depth, skipping braces inside comments, string and
 * character literals and text blocks, and keeps a stack of the enclosing classes so a nested class's
 * lines are attributed to it ({@code Outer$Inner}). Local and anonymous classes are not attributed.
 * See the "Matching a diff to tracked methods" chapter in {@code WIKI.md}.
 */
final class ClassDeclarationScanner {

    private static final Pattern CLASS_DECLARATION_PATTERN = Pattern.compile(
            "(?:^|\\s)(@interface|class|interface|enum|record|trait)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern STATIC_PATTERN = Pattern.compile("(?:^|\\s)static\\s");
    private static final Pattern FINAL_PATTERN = Pattern.compile("(?:^|\\s)final\\s");

    private ClassDeclarationScanner() {
    }

    /**
     * Scan a source file for its class-level declaration lines.
     *
     * @param lines the file content, line 1 at index 0
     * @return per class, keyed by its nesting path within the file (e.g. {@code Outer} or
     *         {@code Outer$Inner}), the class-level lines as flat inclusive {@code [start, end, ...]}
     *         pairs in ascending order; classes with no class-level lines are absent
     */
    static Map<String, int[]> scan(final List<String> lines) {
        Scan scan = new Scan();
        for (int i = 0; i < lines.size(); i++) {
            scan.scanLine(lines.get(i), i + 1);
        }
        return scan.result();
    }

    /**
     * A brace-delimited scope: a class body, or any other block (a method body, an initializer, an
     * array or lambda body). The file itself is the bottom frame.
     */
    private static final class Frame {
        /** The class's nesting path for a class body; {@code null} for any other block and the file. */
        private final String classPath;
        /** Whether member declarations are tracked in this frame: a class body, or the file. */
        private final boolean declarationScope;
        /** Whether the frame is the body of an interface or annotation type, whose fields are constants. */
        private final boolean interfaceBody;
        private int parenDepth;
        /** The current member's code at paren depth 0, without comments and string contents. */
        private final StringBuilder member = new StringBuilder();
        private int memberStartLine = -1;
        /** The line of the current member's first {@code =} at paren depth 0, or -1 for none. */
        private int assignmentLine = -1;

        /**
         * @param classPath the class's nesting path, or {@code null} for a block or the file
         * @param declarationScope whether member declarations are tracked in this frame
         * @param interfaceBody whether the frame is an interface or annotation type body
         */
        private Frame(final String classPath, final boolean declarationScope, final boolean interfaceBody) {
            this.classPath = classPath;
            this.declarationScope = declarationScope;
            this.interfaceBody = interfaceBody;
        }

        /**
         * Whether the frame is a class body.
         *
         * @return true for a class body
         */
        private boolean isClassBody() {
            return classPath != null;
        }

        /**
         * Start a new member: clear the collected code and its first line.
         */
        private void resetMember() {
            member.setLength(0);
            memberStartLine = -1;
            assignmentLine = -1;
        }

        /**
         * Whether the current member has any code yet.
         *
         * @return true once a code character has been seen since the last member ended
         */
        private boolean hasMember() {
            return memberStartLine > 0;
        }
    }

    /**
     * The mutable state of one scan: the frame stack, the comment and literal state carried across
     * lines, and the collected ranges.
     */
    private static final class Scan {
        private final Deque<Frame> frames = new ArrayDeque<>();
        private final Map<String, List<Integer>> rangesByClass = new HashMap<>();
        private boolean inBlockComment;
        /** The delimiter of the literal being skipped, or {@code null} outside one. */
        private String literalDelimiter;

        /**
         * Start a scan with the file as the bottom frame.
         */
        private Scan() {
            frames.push(new Frame(null, true, false));
        }

        /**
         * Scan one line, updating the frame stack and recording any class-level range that ends on it.
         *
         * @param line the source line
         * @param lineNumber the 1-based line number
         */
        private void scanLine(final String line, final int lineNumber) {
            int i = 0;
            while (i < line.length()) {
                if (inBlockComment) {
                    if (line.startsWith("*/", i)) {
                        inBlockComment = false;
                        i += 2;
                    } else {
                        i++;
                    }
                    continue;
                }
                if (literalDelimiter != null) {
                    i = skipInLiteral(line, i);
                    continue;
                }
                if (line.startsWith("//", i)) {
                    break;
                }
                if (line.startsWith("/*", i)) {
                    inBlockComment = true;
                    i += 2;
                    continue;
                }
                i = scanCode(line, i, lineNumber);
            }

            if ("\"".equals(literalDelimiter) || "'".equals(literalDelimiter)) {
                // single-line literals can't span lines; recover from an unterminated one
                literalDelimiter = null;
            }
            Frame frame = frames.peek();
            if (frame.hasMember()) {
                frame.member.append(' ');
            }
        }

        /**
         * Advance through a string or character literal or text block, ending it at its delimiter.
         *
         * @param line the source line
         * @param i the index of the next character inside the literal
         * @return the index of the next character to scan
         */
        private int skipInLiteral(final String line, final int i) {
            if (line.charAt(i) == '\\') {
                return i + 2;
            }
            if (line.startsWith(literalDelimiter, i)) {
                int next = i + literalDelimiter.length();
                literalDelimiter = null;
                return next;
            }
            return i + 1;
        }

        /**
         * Scan one code character (or literal opener), updating the member text and the frames.
         *
         * @param line the source line
         * @param i the index of the character
         * @param lineNumber the 1-based line number
         * @return the index of the next character to scan
         */
        private int scanCode(final String line, final int i, final int lineNumber) {
            Frame frame = frames.peek();
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                if (frame.hasMember() && frame.parenDepth == 0) {
                    frame.member.append(' ');
                }
                return i + 1;
            }
            if (!frame.hasMember() && c != ';' && c != '{' && c != '}') {
                // a member starts at its first code character; the structural characters only end one
                frame.memberStartLine = lineNumber;
            }

            if (line.startsWith("\"\"\"", i) || line.startsWith("'''", i)) {
                literalDelimiter = line.substring(i, i + 3);
                appendAtTopLevel(frame, "\"\"");
                return i + 3;
            }
            if (c == '"' || c == '\'') {
                literalDelimiter = String.valueOf(c);
                appendAtTopLevel(frame, "\"\"");
                return i + 1;
            }

            switch (c) {
                case '(':
                    frame.parenDepth++;
                    break;
                case ')':
                    frame.parenDepth = Math.max(0, frame.parenDepth - 1);
                    break;
                case '{':
                    openBrace(frame, lineNumber);
                    break;
                case '}':
                    closeBrace(lineNumber);
                    break;
                case ';':
                    endMemberWithSemicolon(frame, lineNumber);
                    break;
                case '=':
                    if (frame.parenDepth == 0 && frame.assignmentLine < 0) {
                        frame.assignmentLine = lineNumber;
                    }
                    appendAtTopLevel(frame, "=");
                    break;
                default:
                    appendAtTopLevel(frame, String.valueOf(c));
            }
            return i + 1;
        }

        /**
         * Append member text when it is outside any parentheses, so annotation arguments and
         * parameter lists don't look like an assignment or a class declaration.
         *
         * @param frame the current frame
         * @param text the text to append
         */
        private void appendAtTopLevel(final Frame frame, final String text) {
            if (frame.parenDepth == 0) {
                frame.member.append(text);
            }
        }

        /**
         * Handle an opening brace. Inside parentheses, or in a block, it just opens a nested block. At
         * declaration level it ends the current member: a class declaration opens that class's body
         * (its header lines are class-level), a field whose initializer opens a block (e.g. a lambda or
         * anonymous class) is recorded like any field, and anything else (a method, constructor or
         * initializer block) is not class-level.
         *
         * @param frame the current frame
         * @param lineNumber the 1-based line of the brace
         */
        private void openBrace(final Frame frame, final int lineNumber) {
            if (frame.parenDepth > 0 || !frame.declarationScope) {
                frames.push(new Frame(null, false, false));
                return;
            }

            Matcher declaration = CLASS_DECLARATION_PATTERN.matcher(frame.member.toString());
            if (declaration.find()) {
                String keyword = declaration.group(1);
                String name = declaration.group(2);
                String classPath = frame.isClassBody() ? frame.classPath + "$" + name : name;
                addRange(classPath, frame.hasMember() ? frame.memberStartLine : lineNumber, lineNumber);
                frame.resetMember();
                boolean interfaceBody = "interface".equals(keyword) || "@interface".equals(keyword);
                frames.push(new Frame(classPath, true, interfaceBody));
                return;
            }
            if (frame.isClassBody() && frame.hasMember() && frame.assignmentLine > 0) {
                addFieldRange(frame, lineNumber);
            }
            frame.resetMember();
            frames.push(new Frame(null, false, false));
        }

        /**
         * Handle a closing brace: close the innermost frame. A class body closing with a member still
         * open (an enum constant list with no trailing semicolon) records that member as class-level.
         *
         * @param lineNumber the 1-based line of the brace
         */
        private void closeBrace(final int lineNumber) {
            if (frames.size() == 1) {
                // unbalanced closing brace - keep the file frame
                return;
            }
            Frame closed = frames.pop();
            if (closed.isClassBody() && closed.hasMember() && closed.parenDepth == 0) {
                addFieldRange(closed, lineNumber);
            }
            Frame parent = frames.peek();
            if (parent.parenDepth == 0) {
                // a block inside parentheses (e.g. an annotation's array argument) leaves the
                // enclosing member open
                parent.resetMember();
            }
        }

        /**
         * Handle a semicolon: at class-body level outside parentheses it ends a field, an enum constant
         * list or an abstract method, which is class-level. At file level it ends a package or import
         * statement.
         *
         * @param frame the current frame
         * @param lineNumber the 1-based line of the semicolon
         */
        private void endMemberWithSemicolon(final Frame frame, final int lineNumber) {
            if (frame.parenDepth > 0) {
                return;
            }
            if (frame.isClassBody() && frame.hasMember()) {
                addFieldRange(frame, lineNumber);
            }
            frame.resetMember();
        }

        /**
         * Record the class-level lines of a field-like member ending on the given line. Without an
         * initializer, or for a constant (see {@link #isConstant(Frame)}), that is the whole member.
         * Otherwise the initializer is matched to the constructor or static initializer, so only the
         * lines above the one with the {@code =} - its annotations - are class-level.
         *
         * @param frame the class body frame holding the member
         * @param endLine the 1-based line the member ends on
         */
        private void addFieldRange(final Frame frame, final int endLine) {
            if (frame.assignmentLine < 0 || isConstant(frame)) {
                addRange(frame.classPath, frame.memberStartLine, endLine);
            } else if (frame.assignmentLine > frame.memberStartLine) {
                addRange(frame.classPath, frame.memberStartLine, frame.assignmentLine - 1);
            }
        }

        /**
         * Whether the frame's current member is a constant the compiler may inline: a field of an
         * interface or annotation type, or one declared {@code static final}.
         *
         * @param frame the class body frame holding the member
         * @return true when the member may be an inlined constant
         */
        private boolean isConstant(final Frame frame) {
            String member = frame.member.toString();
            return frame.interfaceBody
                    || (STATIC_PATTERN.matcher(member).find() && FINAL_PATTERN.matcher(member).find());
        }

        /**
         * Record a class-level line range for a class.
         *
         * @param classPath the class's nesting path
         * @param startLine the 1-based first line
         * @param endLine the 1-based last line
         */
        private void addRange(final String classPath, final int startLine, final int endLine) {
            List<Integer> ranges = rangesByClass.computeIfAbsent(classPath, key -> new ArrayList<>());
            ranges.add(startLine);
            ranges.add(endLine);
        }

        /**
         * The collected ranges, per class, as flat int arrays. Ranges are recorded in file order, so
         * each array is already ascending.
         *
         * @return the class-level line ranges keyed by class nesting path
         */
        private Map<String, int[]> result() {
            Map<String, int[]> result = new HashMap<>();
            for (Map.Entry<String, List<Integer>> entry : rangesByClass.entrySet()) {
                int[] ranges = new int[entry.getValue().size()];
                for (int i = 0; i < ranges.length; i++) {
                    ranges[i] = entry.getValue().get(i);
                }
                result.put(entry.getKey(), ranges);
            }
            return result;
        }
    }
}
