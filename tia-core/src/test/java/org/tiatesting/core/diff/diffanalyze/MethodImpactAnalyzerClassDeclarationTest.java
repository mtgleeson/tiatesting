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
 * Verifies that {@link MethodImpactAnalyzer} impacts every tracked method of a class when a diff
 * changes the class's class-level declaration lines - its header or a field declaration - and that
 * other changes outside methods don't. The source is {@code diffanalyze/ClassDeclarations.java.txt}.
 */
class MethodImpactAnalyzerClassDeclarationTest {

    private static final String SOURCE_DIR = "/project/src/main/java";
    private static final String FILE_PATH = SOURCE_DIR + "/com/example/CarService.java";
    private static final String MAPPING_KEY = "com/example/CarService.java";

    private static final MethodImpactTracker LIST = new MethodImpactTracker(
            "com/example/CarService.list.()Ljava/util/List;", 25, 25);
    private static final MethodImpactTracker ORDER_LAMBDA = new MethodImpactTracker(
            "com/example/CarService.lambda$new$0.(Lcom/example/Car;Lcom/example/Car;)I", 20, 20);
    private static final MethodImpactTracker CACHE_SIZE = new MethodImpactTracker(
            "com/example/CarService$Cache.size.()I", 32, 32);

    private final MethodImpactAnalyzer analyzer = new MethodImpactAnalyzer();

    /**
     * Editing a class annotation impacts every tracked method of the class, but not of its nested
     * class.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void classAnnotationEditImpactsEveryMethodOfTheClass() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = replaceLine(original, 9, "@Transactional(readOnly = false)");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(idsOf(LIST, ORDER_LAMBDA), impacted);
    }

    /**
     * Editing the extends/implements line of a multi-line class header impacts the class.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void extendsEditImpactsTheClass() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = replaceLine(original, 11, "        extends OtherService implements Auditable {");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(idsOf(LIST, ORDER_LAMBDA), impacted);
    }

    /**
     * Editing a field's annotation impacts the class.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void fieldAnnotationEditImpactsTheClass() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = replaceLine(original, 13, "    @Value(\"${car.max}\")");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(idsOf(LIST, ORDER_LAMBDA), impacted);
    }

    /**
     * Adding an annotation directly above an existing field's annotations impacts the class.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void annotationAddedAboveFieldImpactsTheClass() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = insertLineAfter(original, 12, "    @Nullable");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(idsOf(LIST, ORDER_LAMBDA), impacted);
    }

    /**
     * An instance field with an initializer is matched to the constructor, which compiles the
     * initializer in, so an annotation added directly above it isn't class-level (the constructor
     * isn't tracked here, so nothing is impacted).
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void annotationAddedAboveInitializedFieldIsLeftToTheConstructor() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = insertLineAfter(original, 15, "    @Nullable");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(Collections.emptySet(), impacted);
    }

    /**
     * Adding a new field directly above an existing one adds a member rather than changing the
     * existing declaration, so nothing is impacted.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void newFieldAddedAboveFieldDoesNotImpactTheClass() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = insertLineAfter(original, 15, "    private int count;");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(Collections.emptySet(), impacted);
    }

    /**
     * A comment added directly above a field holds no code, so nothing is impacted.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void commentAddedAboveFieldDoesNotImpactTheClass() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = insertLineAfter(original, 12, "    // the most cars to list");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(Collections.emptySet(), impacted);
    }

    /**
     * Editing the class Javadoc doesn't impact the class.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void classJavadocEditDoesNotImpactTheClass() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = replaceLine(original, 6, " * Lists cars.");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(Collections.emptySet(), impacted);
    }

    /**
     * Editing a method's annotation impacts only that method, not the whole class.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void methodAnnotationEditImpactsOnlyThatMethod() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = replaceLine(original, 23, "    @RequestMapping(value = \"/all-cars\")");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(idsOf(LIST), impacted);
    }

    /**
     * Editing a nested class's field impacts the nested class's methods only.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void nestedClassFieldEditImpactsOnlyTheNestedClass() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = replaceLine(original, 29, "        @Value(\"${cache.max}\") private int size;");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(idsOf(CACHE_SIZE), impacted);
    }

    /**
     * Adding an annotation above a nested class's declaration impacts the nested class only.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void annotationAddedAboveNestedClassImpactsOnlyTheNestedClass() throws Exception {
        // given
        List<String> original = fixtureLines();
        List<String> revised = insertLineAfter(original, 27, "    @Deprecated");

        // when
        Set<Integer> impacted = analyze(original, revised);

        // then
        assertEquals(idsOf(CACHE_SIZE), impacted);
    }

    /**
     * Runs the analyzer over the fixture's diff with all three tracked methods.
     *
     * @param original the original file lines
     * @param revised the revised file lines
     * @return the ids of the methods the diff impacts
     */
    private Set<Integer> analyze(List<String> original, List<String> revised) {
        Map<Integer, MethodImpactTracker> methodsForFile = new HashMap<>();
        for (MethodImpactTracker trackedMethod : Arrays.asList(LIST, ORDER_LAMBDA, CACHE_SIZE)) {
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
     * Reads the class declarations fixture from the test resources.
     *
     * @return the fixture's lines, line 1 at index 0
     * @throws Exception if the resource can't be read
     */
    private List<String> fixtureLines() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/diffanalyze/ClassDeclarations.java.txt");
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
