package org.tiatesting.core.diff.diffanalyze;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how {@link MethodImpactAnalyzer} maps a source diff onto tracked methods, focusing on
 * constructors whose start-end range is stretched by a late-declared field. The source is the
 * junit5-git-maven-postgres CarService fixture (a field on the last line, line 74), with the
 * tracked methods and line numbers the coverage agent records for it.
 */
class MethodImpactAnalyzerTest {

    private static final String SOURCE_DIR = "/project/src/main/java";
    private static final String FILE_PATH = SOURCE_DIR + "/com/example/CarService.java";
    private static final String MAPPING_KEY = "com/example/CarService.java";

    private static final MethodImpactTracker CONSTRUCTOR_WITH_RANGES = new MethodImpactTracker(
            "com/example/CarService.<init>.()V", 8, 74,
            new int[]{7, 16, 20, 21, 25, 25, 34, 34, 39, 39, 43, 44, 49, 49, 53, 53, 57, 57, 66, 66, 70, 70, 74, 75});
    private static final MethodImpactTracker CONSTRUCTOR_WITHOUT_RANGES = new MethodImpactTracker(
            "com/example/CarService.<init>.()V", 8, 74);
    private static final MethodImpactTracker CHECK_BRAKES = new MethodImpactTracker(
            "com/example/CarService.checkBrakes.()V", 36, 38);

    private final MethodImpactAnalyzer analyzer = new MethodImpactAnalyzer();

    /**
     * An edit inside a method that isn't tracked (temp4, never covered by a test) sits inside the
     * constructor's start-end range but outside its exact line ranges, so nothing is impacted.
     *
     * @throws Exception if the source fixture can't be read
     */
    @Test
    void editInUntrackedMethodDoesNotImpactTheConstructor() throws Exception {
        // given
        List<String> original = carServiceLines();
        List<String> revised = replaceLine(original, 72, "        System.out.println(\"temp 4 changed\");");

        // when
        Set<Integer> impacted = analyze(original, revised, CONSTRUCTOR_WITH_RANGES, CHECK_BRAKES);

        // then
        assertEquals(Collections.emptySet(), impacted);
    }

    /**
     * An edit inside a tracked method impacts only that method, not the constructor whose
     * start-end range spans it.
     *
     * @throws Exception if the source fixture can't be read
     */
    @Test
    void editInTrackedMethodImpactsOnlyThatMethod() throws Exception {
        // given
        List<String> original = carServiceLines();
        List<String> revised = replaceLine(original, 37, "        System.out.println(\"check brakes 14\");");

        // when
        Set<Integer> impacted = analyze(original, revised, CONSTRUCTOR_WITH_RANGES, CHECK_BRAKES);

        // then
        assertEquals(idsOf(CHECK_BRAKES), impacted);
    }

    /**
     * Renaming a tracked method edits its signature line, which belongs to the method and not the
     * constructor.
     *
     * @throws Exception if the source fixture can't be read
     */
    @Test
    void signatureEditImpactsOnlyThatMethod() throws Exception {
        // given
        List<String> original = carServiceLines();
        List<String> revised = replaceLine(original, 35, "    public void checkAllBrakes(){");

        // when
        Set<Integer> impacted = analyze(original, revised, CONSTRUCTOR_WITH_RANGES, CHECK_BRAKES);

        // then
        assertEquals(idsOf(CHECK_BRAKES), impacted);
    }

    /**
     * Changing the late field's initializer impacts the constructor.
     *
     * @throws Exception if the source fixture can't be read
     */
    @Test
    void editToLateFieldImpactsTheConstructor() throws Exception {
        // given
        List<String> original = carServiceLines();
        List<String> revised = replaceLine(original, 74, "    private int e = 6;");

        // when
        Set<Integer> impacted = analyze(original, revised, CONSTRUCTOR_WITH_RANGES, CHECK_BRAKES);

        // then
        assertEquals(idsOf(CONSTRUCTOR_WITH_RANGES), impacted);
    }

    /**
     * Adding a new field between two members impacts the constructor, since the compiler folds
     * the new initializer into it.
     *
     * @throws Exception if the source fixture can't be read
     */
    @Test
    void newFieldBetweenMembersImpactsTheConstructor() throws Exception {
        // given
        List<String> original = carServiceLines();
        List<String> revised = insertLineAfter(original, 20, "    private int f = 7;");

        // when
        Set<Integer> impacted = analyze(original, revised, CONSTRUCTOR_WITH_RANGES, CHECK_BRAKES);

        // then
        assertEquals(idsOf(CONSTRUCTOR_WITH_RANGES), impacted);
    }

    /**
     * A new field added between two methods separated by a single blank line (temp20 ends on 65,
     * temp3's signature is on 67) impacts the constructor.
     *
     * @throws Exception if the source fixture can't be read
     */
    @Test
    void newFieldBetweenAdjacentMethodsImpactsTheConstructor() throws Exception {
        // given
        List<String> original = carServiceLines();
        List<String> revised = insertLineAfter(original, 65, "    private int f = 7;");

        // when
        Set<Integer> impacted = analyze(original, revised, CONSTRUCTOR_WITH_RANGES, CHECK_BRAKES);

        // then
        assertEquals(idsOf(CONSTRUCTOR_WITH_RANGES), impacted);
    }

    /**
     * A new field added directly after the late field, at the end of the constructor's last range,
     * still impacts the constructor.
     *
     * @throws Exception if the source fixture can't be read
     */
    @Test
    void newFieldAfterTheLastRangeImpactsTheConstructor() throws Exception {
        // given
        List<String> original = carServiceLines();
        List<String> revised = insertLineAfter(original, 74, "    private int f = 7;");

        // when
        Set<Integer> impacted = analyze(original, revised, CONSTRUCTOR_WITH_RANGES, CHECK_BRAKES);

        // then
        assertEquals(idsOf(CONSTRUCTOR_WITH_RANGES), impacted);
    }

    /**
     * A line inserted inside an untracked method's body doesn't impact the constructor.
     *
     * @throws Exception if the source fixture can't be read
     */
    @Test
    void insertionInsideUntrackedMethodDoesNotImpactTheConstructor() throws Exception {
        // given
        List<String> original = carServiceLines();
        List<String> revised = insertLineAfter(original, 72, "        System.out.println(\"temp 4 again\");");

        // when
        Set<Integer> impacted = analyze(original, revised, CONSTRUCTOR_WITH_RANGES, CHECK_BRAKES);

        // then
        assertEquals(Collections.emptySet(), impacted);
    }

    /**
     * A constructor stored without line ranges (a row written before they existed) keeps the old
     * start-end matching, so an edit anywhere in its range still impacts it.
     *
     * @throws Exception if the source fixture can't be read
     */
    @Test
    void constructorWithoutRangesKeepsStartEndMatching() throws Exception {
        // given
        List<String> original = carServiceLines();
        List<String> revised = replaceLine(original, 72, "        System.out.println(\"temp 4 changed\");");

        // when
        Set<Integer> impacted = analyze(original, revised, CONSTRUCTOR_WITHOUT_RANGES, CHECK_BRAKES);

        // then
        assertEquals(idsOf(CONSTRUCTOR_WITHOUT_RANGES), impacted);
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
     * Reads the CarService fixture from the test resources.
     *
     * @return the fixture's lines, line 1 at index 0
     * @throws Exception if the resource can't be read
     */
    private List<String> carServiceLines() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/diffanalyze/CarService.java.txt");
             Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name())) {
            return new ArrayList<>(Arrays.asList(scanner.useDelimiter("\\A").next().split("\\R")));
        }
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
