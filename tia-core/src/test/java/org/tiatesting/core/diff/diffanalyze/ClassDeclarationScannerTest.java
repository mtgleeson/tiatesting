package org.tiatesting.core.diff.diffanalyze;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the class-level declaration lines {@link ClassDeclarationScanner} finds. The main fixture
 * is {@code diffanalyze/ClassDeclarations.java.txt}: a class with a multi-line header, annotated
 * fields, a field initialized by a lambda, a method, a nested class, a nested enum and a brace inside
 * a string literal.
 */
class ClassDeclarationScannerTest {

    /**
     * The outer class's header (annotations through the line with its brace), the annotated field
     * without an initializer, and the annotation above the lambda-initialized field are class-level.
     * Initialized fields' own lines (matched to the constructor), the Javadoc, the method and its
     * annotation, and the brace inside the string are not.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void findsHeaderAndFieldsOfTheOuterClass() throws Exception {
        // given
        List<String> lines = fixtureLines();

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertArrayEquals(new int[]{8, 11, 13, 14, 18, 18}, declarations.get("CarService"));
    }

    /**
     * A nested class's header and fields are attributed to it by its nesting path, not to the outer
     * class.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void attributesNestedClassLinesToTheNestedClass() throws Exception {
        // given
        List<String> lines = fixtureLines();

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertArrayEquals(new int[]{28, 28, 29, 29}, declarations.get("CarService$Cache"));
    }

    /**
     * An enum constant list with no trailing semicolon is closed by the enum's brace and is
     * class-level for the enum.
     *
     * @throws Exception if the fixture can't be read
     */
    @Test
    void recordsAnEnumConstantListClosedByTheBrace() throws Exception {
        // given
        List<String> lines = fixtureLines();

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertArrayEquals(new int[]{36, 36, 36, 36}, declarations.get("CarService$Mode"));
    }

    /**
     * Local and anonymous classes inside a method body aren't attributed, and their fields aren't
     * class-level for the enclosing class.
     */
    @Test
    void ignoresLocalAndAnonymousClasses() {
        // given
        List<String> lines = Arrays.asList(
                "public class Widget {",                         // 1
                "    void run() {",                              // 2
                "        class Local { int count; }",            // 3
                "        Runnable r = new Runnable() {",         // 4
                "            private int calls;",                // 5
                "            public void run() { calls++; }",    // 6
                "        };",                                    // 7
                "    }",                                         // 8
                "}");                                            // 9

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertArrayEquals(new int[]{1, 1}, declarations.get("Widget"));
        assertEquals(1, declarations.size());
    }

    /**
     * Braces and semicolons inside block comments, character literals and text blocks don't affect
     * the scan.
     */
    @Test
    void skipsCommentsCharactersAndTextBlocks() {
        // given
        List<String> lines = Arrays.asList(
                "public class Widget {",                         // 1
                "    /* { ; } */",                               // 2
                "    private char open = '{';",                  // 3
                "    private String sql = \"\"\"",               // 4
                "        select { ; }",                          // 5
                "        \"\"\";",                               // 6
                "    private int count;",                        // 7
                "    void run() {",                              // 8
                "    }",                                         // 9
                "}");                                            // 10

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertArrayEquals(new int[]{1, 1, 7, 7}, declarations.get("Widget"));
    }

    /**
     * An annotation argument with an assignment or a parameter list doesn't make a method look like
     * a field, so the method isn't class-level.
     */
    @Test
    void methodWithAssignmentInAnnotationIsNotClassLevel() {
        // given
        List<String> lines = Arrays.asList(
                "public class Widget {",                         // 1
                "    @RequestMapping(value = \"/x\")",           // 2
                "    public String get(int limit) {",            // 3
                "        return \"x\";",                         // 4
                "    }",                                         // 5
                "}");                                            // 6

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertArrayEquals(new int[]{1, 1}, declarations.get("Widget"));
    }

    /**
     * A static final field may be an inlined constant with no initializer code, so its whole
     * declaration is class-level; an annotated instance field with an initializer contributes only its
     * annotation line.
     */
    @Test
    void staticFinalConstantIsClassLevel() {
        // given
        List<String> lines = Arrays.asList(
                "public class Widget {",                         // 1
                "    private static final int MAX = 5;",         // 2
                "    @Inject",                                   // 3
                "    private int timeout = 30;",                 // 4
                "}");                                            // 5

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertArrayEquals(new int[]{1, 1, 2, 2, 3, 3}, declarations.get("Widget"));
    }

    /**
     * A static final field whose initializer makes a call, uses new or opens a block compiles into
     * the static initializer, so like any initialized field only its annotation lines are class-level.
     */
    @Test
    void staticFinalWithCodeInitializerIsNotAConstant() {
        // given
        List<String> lines = Arrays.asList(
                "public class Widget {",                                                      // 1
                "    private static final Logger log = LoggerFactory.getLogger(Widget.class);", // 2
                "    @Deprecated",                                                            // 3
                "    private static final Map<String, Integer> SIZES = new HashMap<>();",     // 4
                "    private static final int[] PRIMES = {2, 3, 5};",                         // 5
                "}");                                                                         // 6

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertArrayEquals(new int[]{1, 1, 3, 3}, declarations.get("Widget"));
    }

    /**
     * Abstract and interface method declarations end with a semicolon but aren't fields, so they
     * aren't class-level - including one with a throws clause and annotated parameters.
     */
    @Test
    void abstractMethodDeclarationIsNotClassLevel() {
        // given
        List<String> lines = Arrays.asList(
                "public abstract class Worker {",                                    // 1
                "    @Override",                                                     // 2
                "    public abstract void process(@NotNull Order order);",           // 3
                "    protected abstract Result load(String id) throws IOException;", // 4
                "    @Value(\"${size}\") private int size;",                          // 5
                "}");                                                                // 6

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertArrayEquals(new int[]{1, 1, 5, 5}, declarations.get("Worker"));
    }

    /**
     * A field of an interface is implicitly static final, so it is class-level even without the
     * modifiers.
     */
    @Test
    void interfaceFieldIsClassLevel() {
        // given
        List<String> lines = Arrays.asList(
                "public interface Limits {",                     // 1
                "    int MAX = 5;",                              // 2
                "}");                                            // 3

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertArrayEquals(new int[]{1, 1, 2, 2}, declarations.get("Limits"));
    }

    /**
     * A file with no class declaration has no class-level lines.
     */
    @Test
    void fileWithoutAClassHasNoDeclarations() {
        // given
        List<String> lines = Arrays.asList("package com.example;", "", "import java.util.List;");

        // when
        Map<String, int[]> declarations = ClassDeclarationScanner.scan(lines);

        // then
        assertFalse(declarations.containsKey("List"));
        assertEquals(0, declarations.size());
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
            return Arrays.asList(scanner.useDelimiter("\\A").next().split("\\R"));
        }
    }
}
