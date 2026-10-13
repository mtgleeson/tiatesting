package org.tiatesting.core.diff.diffanalyze;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link MethodImpactAnalyzer} matches changes made to the blank and comment lines
 * between a method's signature and its first code line. JaCoCo records a method's first line as its
 * first line with code, so those lines sit above the stored range.
 */
class MethodImpactAnalyzerLeadingLinesTest {

    private static final String SOURCE_DIR = "/project/src/main/java";
    private static final String FILE_PATH = SOURCE_DIR + "/com/example/Widget.java";
    private static final String MAPPING_KEY = "com/example/Widget.java";

    private static final List<String> WIDGET = Arrays.asList(
            "package com.example;",                 // 1
            "",                                     // 2
            "public class Widget {",                // 3
            "",                                     // 4
            "    public void tidy() {",             // 5
            "",                                     // 6
            "        // remove stale entries",      // 7
            "        // before saving",             // 8
            "        clean();",                     // 9
            "    }",                                // 10
            "",                                     // 11
            "    public void save() {",             // 12
            "        persist();",                   // 13
            "    }",                                // 14
            "}");                                   // 15

    private static final MethodImpactTracker TIDY = new MethodImpactTracker(
            "com/example/Widget.tidy.()V", 9, 10);
    private static final MethodImpactTracker SAVE = new MethodImpactTracker(
            "com/example/Widget.save.()V", 13, 14);

    private final MethodImpactAnalyzer analyzer = new MethodImpactAnalyzer();

    /**
     * Code added directly below the signature, above the blank and comment lines that precede the
     * first code line, impacts the method.
     */
    @Test
    void codeAddedBelowSignatureImpactsTheMethod() {
        // given
        List<String> revised = insertLineAfter(WIDGET, 5, "        audit();");

        // when
        Set<Integer> impacted = analyze(WIDGET, revised, TIDY, SAVE);

        // then
        assertEquals(idsOf(TIDY), impacted);
    }

    /**
     * Replacing the blank line below the signature with code impacts the method.
     */
    @Test
    void blankLineReplacedWithCodeImpactsTheMethod() {
        // given
        List<String> revised = replaceLine(WIDGET, 6, "        audit();");

        // when
        Set<Integer> impacted = analyze(WIDGET, revised, TIDY, SAVE);

        // then
        assertEquals(idsOf(TIDY), impacted);
    }

    /**
     * The widening stops at the signature line, so a change above the method doesn't impact it.
     */
    @Test
    void codeAddedAboveSignatureDoesNotImpactTheMethod() {
        // given
        List<String> revised = insertLineAfter(WIDGET, 3, "    private int count;");

        // when
        Set<Integer> impacted = analyze(WIDGET, revised, TIDY, SAVE);

        // then
        assertEquals(Collections.emptySet(), impacted);
    }

    /**
     * A method whose first code line directly follows its signature keeps the one-line padding.
     */
    @Test
    void startDirectlyBelowSignatureIsUnchanged() {
        // given
        int paddedBegin = 12;

        // when
        int widenedBegin = MethodImpactAnalyzer.widenStartOverBlankAndCommentLines(paddedBegin, WIDGET);

        // then
        assertEquals(12, widenedBegin);
    }

    /**
     * Block comment lines - the opening line, continuation lines and the closing line - are walked
     * over like line comments.
     */
    @Test
    void blockCommentLinesAreWalkedOver() {
        // given
        List<String> lines = Arrays.asList(
                "    public void tidy() {",         // 1
                "        /*",                       // 2
                "         * remove stale entries",  // 3
                "         */",                      // 4
                "        clean();");                // 5

        // when
        int widenedBegin = MethodImpactAnalyzer.widenStartOverBlankAndCommentLines(4, lines);

        // then
        assertEquals(1, widenedBegin);
    }

    /**
     * Stored lines that no longer fit the file (a stale mapping) keep the padded begin.
     */
    @Test
    void beginOutsideTheFileIsUnchanged() {
        // given
        int paddedBegin = WIDGET.size() + 5;

        // when
        int widenedBegin = MethodImpactAnalyzer.widenStartOverBlankAndCommentLines(paddedBegin, WIDGET);

        // then
        assertEquals(paddedBegin, widenedBegin);
    }

    /**
     * Runs the analyzer over one file's diff with the given tracked methods, keyed by their ids.
     *
     * @param original the original file lines
     * @param revised the revised file lines
     * @param trackedMethods the tracked methods for the file
     * @return the ids of the methods the diff impacts
     */
    private Set<Integer> analyze(List<String> original, List<String> revised, MethodImpactTracker... trackedMethods) {
        Map<Integer, MethodImpactTracker> methodsForFile = new HashMap<>();
        for (MethodImpactTracker trackedMethod : trackedMethods) {
            methodsForFile.put(trackedMethod.hashCode(), trackedMethod);
        }
        Map<String, Map<Integer, MethodImpactTracker>> methodsTrackedByFile = new HashMap<>();
        methodsTrackedByFile.put(MAPPING_KEY, methodsForFile);

        Set<Integer> impacted = new HashSet<>();
        analyzer.getMethodsForImpactedFile(String.join("\n", original), String.join("\n", revised),
                FILE_PATH, FILE_PATH, impacted, methodsTrackedByFile, Collections.singletonList(SOURCE_DIR));
        return impacted;
    }

    /**
     * Collects the ids the analyzer reports for the given methods.
     *
     * @param methods the expected impacted methods
     * @return their ids
     */
    private Set<Integer> idsOf(MethodImpactTracker... methods) {
        Set<Integer> ids = new HashSet<>();
        for (MethodImpactTracker method : methods) {
            ids.add(method.hashCode());
        }
        return ids;
    }

    /**
     * Copies the lines with one line replaced.
     *
     * @param lines the original lines
     * @param lineNumber the 1-based line to replace
     * @param text the replacement text
     * @return the revised lines
     */
    private List<String> replaceLine(List<String> lines, int lineNumber, String text) {
        List<String> revised = new ArrayList<>(lines);
        revised.set(lineNumber - 1, text);
        return revised;
    }

    /**
     * Copies the lines with a new line inserted after the given one.
     *
     * @param lines the original lines
     * @param lineNumber the 1-based line to insert after
     * @param text the inserted text
     * @return the revised lines
     */
    private List<String> insertLineAfter(List<String> lines, int lineNumber, String text) {
        List<String> revised = new ArrayList<>(lines);
        revised.add(lineNumber, text);
        return revised;
    }
}
