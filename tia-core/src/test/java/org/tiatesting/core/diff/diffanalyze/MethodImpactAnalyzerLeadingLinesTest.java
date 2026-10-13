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
 * Verifies that {@link MethodImpactAnalyzer} matches changes made above a method's first code line:
 * to the blank and comment lines between the signature and the first code line, to the earlier lines
 * of a multi-line signature, and to the method's annotations. JaCoCo records a method's first line as
 * its first line with code, so those lines sit above the stored range.
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

    private static final List<String> REPOSITORY = Arrays.asList(
            "public class CarRepository {",           // 1
            "",                                       // 2
            "    /**",                                // 3
            "     * Finds a car.",                    // 4
            "     */",                                // 5
            "    @Transactional(readOnly = true)",    // 6
            "    @SuppressWarnings({",                // 7
            "            \"unchecked\",",             // 8
            "            \"rawtypes\"})",             // 9
            "    public Car find(",                   // 10
            "            long id,",                   // 11
            "            boolean fresh) {",           // 12
            "        return cars.get(id);",           // 13
            "    }",                                  // 14
            "",                                       // 15
            "    /**",                                // 16
            "     * Audits the cars.",                // 17
            "     */",                                // 18
            "    public void audit() {",              // 19
            "        log(cars);",                     // 20
            "    }",                                  // 21
            "}");                                     // 22

    private static final MethodImpactTracker FIND = new MethodImpactTracker(
            "com/example/CarRepository.find.(JZ)Lcom/example/Car;", 13, 13);
    private static final MethodImpactTracker AUDIT = new MethodImpactTracker(
            "com/example/CarRepository.audit.()V", 20, 21);

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
        int widenedBegin = MethodImpactAnalyzer.widenStartToDeclaration(paddedBegin, WIDGET);

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
        int widenedBegin = MethodImpactAnalyzer.widenStartToDeclaration(4, lines);

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
        int widenedBegin = MethodImpactAnalyzer.widenStartToDeclaration(paddedBegin, WIDGET);

        // then
        assertEquals(paddedBegin, widenedBegin);
    }

    /**
     * Editing a single-line annotation above the signature impacts the method.
     */
    @Test
    void annotationEditImpactsTheMethod() {
        // given
        List<String> revised = replaceLine(REPOSITORY, 6, "    @Transactional(readOnly = false)");

        // when
        Set<Integer> impacted = analyze(REPOSITORY, revised, FIND, AUDIT);

        // then
        assertEquals(idsOf(FIND), impacted);
    }

    /**
     * Adding an annotation above an un-annotated method's signature impacts the method.
     */
    @Test
    void annotationAddedImpactsTheMethod() {
        // given
        List<String> revised = insertLineAfter(REPOSITORY, 18, "    @Async");

        // when
        Set<Integer> impacted = analyze(REPOSITORY, revised, FIND, AUDIT);

        // then
        assertEquals(idsOf(AUDIT), impacted);
    }

    /**
     * Editing a line of a multi-line annotation impacts the method.
     */
    @Test
    void multiLineAnnotationEditImpactsTheMethod() {
        // given
        List<String> revised = replaceLine(REPOSITORY, 8, "            \"unchecked\", \"deprecation\",");

        // when
        Set<Integer> impacted = analyze(REPOSITORY, revised, FIND, AUDIT);

        // then
        assertEquals(idsOf(FIND), impacted);
    }

    /**
     * Changing a modifier on the first line of a multi-line signature impacts the method.
     */
    @Test
    void multiLineSignatureModifierEditImpactsTheMethod() {
        // given
        List<String> revised = replaceLine(REPOSITORY, 10, "    public synchronized Car find(");

        // when
        Set<Integer> impacted = analyze(REPOSITORY, revised, FIND, AUDIT);

        // then
        assertEquals(idsOf(FIND), impacted);
    }

    /**
     * The declaration walk stops at the method's Javadoc, so a Javadoc edit doesn't impact it.
     */
    @Test
    void javadocEditDoesNotImpactTheMethod() {
        // given
        List<String> revised = replaceLine(REPOSITORY, 4, "     * Finds a car by its id.");

        // when
        Set<Integer> impacted = analyze(REPOSITORY, revised, FIND, AUDIT);

        // then
        assertEquals(Collections.emptySet(), impacted);
    }

    /**
     * The declaration walks over every annotation and signature line up to the Javadoc, including the
     * first line of an annotation's array argument.
     */
    @Test
    void declarationWalkReachesTheFirstAnnotation() {
        // given
        int paddedBegin = 12;

        // when
        int widenedBegin = MethodImpactAnalyzer.widenStartToDeclaration(paddedBegin, REPOSITORY);

        // then
        assertEquals(6, widenedBegin);
    }

    /**
     * A field directly above the method, with no blank line between them, ends the declaration walk
     * even when it carries a trailing comment.
     */
    @Test
    void declarationWalkStopsAtPreviousMember() {
        // given
        List<String> lines = Arrays.asList(
                "    private int hits = 0; // hit count",   // 1
                "    @Override",                           // 2
                "    public void tidy() {",                // 3
                "        clean();");                       // 4

        // when
        int widenedBegin = MethodImpactAnalyzer.widenStartToDeclaration(3, lines);

        // then
        assertEquals(2, widenedBegin);
    }

    /**
     * When the blank-line walk lands on the previous member's last line - here a field above a
     * one-line method, whose first code line is its signature - the declaration walk doesn't continue
     * into that member's annotations.
     */
    @Test
    void declarationWalkDoesNotEnterThePreviousMember() {
        // given
        List<String> lines = Arrays.asList(
                "    @Column",                             // 1
                "    private int hits;",                   // 2
                "",                                        // 3
                "    int size() { return hits; }");        // 4

        // when
        int widenedBegin = MethodImpactAnalyzer.widenStartToDeclaration(3, lines);

        // then
        assertEquals(2, widenedBegin);
    }

    /**
     * With the opening brace on its own line, the walk continues over the signature and annotation
     * lines above it.
     */
    @Test
    void declarationWalkCrossesAnOwnLineBrace() {
        // given
        List<String> lines = Arrays.asList(
                "",                                        // 1
                "    @Override",                           // 2
                "    public void tidy()",                  // 3
                "    {",                                   // 4
                "",                                        // 5
                "        clean();");                       // 6

        // when
        int widenedBegin = MethodImpactAnalyzer.widenStartToDeclaration(5, lines);

        // then
        assertEquals(2, widenedBegin);
    }

    /**
     * The class declaration line opens a block, so the walk stops below it for the first member.
     */
    @Test
    void declarationWalkStopsAtTheClassDeclaration() {
        // given
        List<String> lines = Arrays.asList(
                "@Entity",                                 // 1
                "public class Widget {",                   // 2
                "    @Override",                           // 3
                "    public void tidy() {",                // 4
                "        clean();");                       // 5

        // when
        int widenedBegin = MethodImpactAnalyzer.widenStartToDeclaration(4, lines);

        // then
        assertEquals(3, widenedBegin);
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
