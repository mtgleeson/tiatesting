package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cover {@link TestSourceDependencyIndex}: finding the tracked suites that depend on a changed test
 * support class from the test source files. Each test writes the exact sources it needs into a temp
 * test dir.
 */
class TestSourceDependencyIndexTest {

    /**
     * Write a source file for a class under a test dir.
     *
     * @param dir the test source dir
     * @param className the class's name, e.g. {@code com.example.OrderTest}
     * @param extension {@code java} or {@code groovy}
     * @param lines the file's lines
     * @throws IOException if the file can't be written
     */
    private static void source(final Path dir, final String className, final String extension,
                               final String... lines) throws IOException {
        Path file = dir.resolve(className.replace('.', '/') + "." + extension);
        Files.createDirectories(file.getParent());
        Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Write a Java source file for a class under a test dir.
     *
     * @param dir the test source dir
     * @param className the class's name, e.g. {@code com.example.OrderTest}
     * @param lines the file's lines
     * @throws IOException if the file can't be written
     */
    private static void java(final Path dir, final String className, final String... lines) throws IOException {
        source(dir, className, "java", lines);
    }

    /**
     * Build an index over one dir and find the suites depending on a changed class.
     *
     * @param dir the test source dir
     * @param changedClass the changed support class
     * @param trackedSuites the tracked suite names
     * @return the tracked suites that depend on the changed class
     */
    private static Set<String> dependants(final Path dir, final String changedClass, final String... trackedSuites) {
        return TestSourceDependencyIndex.build(singletonList(dir.toString()), singletonList(changedClass),
                Arrays.asList(trackedSuites)).dependantSuites(changedClass);
    }

    /**
     * Collect names into a set.
     *
     * @param names the names
     * @return the names as a set
     */
    private static Set<String> setOf(final String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    @Test
    void suiteExtendingABaseClassInTheSamePackageDependsOnIt(@TempDir Path dir) throws Exception {
        // given - no import is needed within a package
        java(dir, "com.example.BaseTest", "package com.example;", "public abstract class BaseTest {}");
        java(dir, "com.example.OrderTest", "package com.example;", "class OrderTest extends BaseTest {}");
        java(dir, "com.example.OtherTest", "package com.example;", "class OtherTest {}");

        // when
        Set<String> suites = dependants(dir, "com.example.BaseTest", "com.example.OrderTest", "com.example.OtherTest");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void suiteImportingTheHelperFromAnotherPackageDependsOnIt(@TempDir Path dir) throws Exception {
        // given
        java(dir, "com.example.support.OrderTestHelper", "package com.example.support;", "public class OrderTestHelper {}");
        java(dir, "com.example.orders.OrderTest", "package com.example.orders;",
                "import com.example.support.OrderTestHelper;", "class OrderTest { OrderTestHelper helper; }");

        // when
        Set<String> suites = dependants(dir, "com.example.support.OrderTestHelper", "com.example.orders.OrderTest");

        // then
        assertEquals(setOf("com.example.orders.OrderTest"), suites);
    }

    @Test
    void suiteWildcardImportingThePackageAndNamingTheHelperDependsOnIt(@TempDir Path dir) throws Exception {
        // given - OrderTest names the helper; InvoiceTest imports the package but never names it
        java(dir, "com.example.support.OrderTestHelper", "package com.example.support;", "public class OrderTestHelper {}");
        java(dir, "com.example.orders.OrderTest", "package com.example.orders;",
                "import com.example.support.*;", "class OrderTest { OrderTestHelper helper; }");
        java(dir, "com.example.orders.InvoiceTest", "package com.example.orders;",
                "import com.example.support.*;", "class InvoiceTest {}");

        // when
        Set<String> suites = dependants(dir, "com.example.support.OrderTestHelper",
                "com.example.orders.OrderTest", "com.example.orders.InvoiceTest");

        // then
        assertEquals(setOf("com.example.orders.OrderTest"), suites);
    }

    @Test
    void suiteStaticImportingAMemberOfTheHelperDependsOnIt(@TempDir Path dir) throws Exception {
        // given
        java(dir, "com.example.support.Orders", "package com.example.support;", "public class Orders {}");
        java(dir, "com.example.orders.OrderTest", "package com.example.orders;",
                "import static com.example.support.Orders.anOrder;", "class OrderTest { Object o = anOrder(); }");
        java(dir, "com.example.orders.InvoiceTest", "package com.example.orders;",
                "import static com.example.support.Orders.*;", "class InvoiceTest {}");

        // when
        Set<String> suites = dependants(dir, "com.example.support.Orders",
                "com.example.orders.OrderTest", "com.example.orders.InvoiceTest");

        // then
        assertEquals(setOf("com.example.orders.OrderTest", "com.example.orders.InvoiceTest"), suites);
    }

    @Test
    void suiteImportingANestedClassOfTheHelperDependsOnIt(@TempDir Path dir) throws Exception {
        // given
        java(dir, "com.example.support.OrderTestHelper", "package com.example.support;",
                "public class OrderTestHelper { public static class Builder {} }");
        java(dir, "com.example.orders.OrderTest", "package com.example.orders;",
                "import com.example.support.OrderTestHelper.Builder;", "class OrderTest { Builder builder; }");

        // when
        Set<String> suites = dependants(dir, "com.example.support.OrderTestHelper", "com.example.orders.OrderTest");

        // then
        assertEquals(setOf("com.example.orders.OrderTest"), suites);
    }

    @Test
    void suiteNamingTheHelperByItsQualifiedNameDependsOnIt(@TempDir Path dir) throws Exception {
        // given - no import, the qualified name is written inline
        java(dir, "com.example.api.OrderApi", "package com.example.api;", "public class OrderApi {}");
        java(dir, "com.example.orders.OrderTest", "package com.example.orders;",
                "class OrderTest { Object api = new com.example.api.OrderApi(); }");

        // when
        Set<String> suites = dependants(dir, "com.example.api.OrderApi", "com.example.orders.OrderTest");

        // then
        assertEquals(setOf("com.example.orders.OrderTest"), suites);
    }

    @Test
    void sameSimpleNameInAPackageTheSuiteCannotSeeIsNotADependency(@TempDir Path dir) throws Exception {
        // given - OrderTest uses its own package's TestData, not the changed one
        java(dir, "com.example.support.TestData", "package com.example.support;", "public class TestData {}");
        java(dir, "com.example.orders.TestData", "package com.example.orders;", "class TestData {}");
        java(dir, "com.example.orders.OrderTest", "package com.example.orders;", "class OrderTest { TestData data; }");

        // when
        Set<String> suites = dependants(dir, "com.example.support.TestData", "com.example.orders.OrderTest");

        // then
        assertEquals(Collections.emptySet(), suites);
    }

    @Test
    void helperNameInsideALongerWordIsNotADependency(@TempDir Path dir) throws Exception {
        // given - OrderApiClient isn't OrderApi
        java(dir, "com.example.OrderApi", "package com.example;", "public class OrderApi {}");
        java(dir, "com.example.OrderTest", "package com.example;", "class OrderTest { OrderApiClient client; }");

        // when
        Set<String> suites = dependants(dir, "com.example.OrderApi", "com.example.OrderTest");

        // then
        assertEquals(Collections.emptySet(), suites);
    }

    @Test
    void suiteReachingTheHelperThroughAnotherClassDependsOnIt(@TempDir Path dir) throws Exception {
        // given - OrderTest extends BaseTest (not a suite), which uses OrderApi; InvoiceTest uses OrderTest
        java(dir, "com.example.api.OrderApi", "package com.example.api;", "public class OrderApi {}");
        java(dir, "com.example.BaseTest", "package com.example;",
                "import com.example.api.OrderApi;", "abstract class BaseTest { OrderApi api; }");
        java(dir, "com.example.OrderTest", "package com.example;", "class OrderTest extends BaseTest {}");
        java(dir, "com.example.InvoiceTest", "package com.example;", "class InvoiceTest { OrderTest order; }");

        // when
        Set<String> suites = dependants(dir, "com.example.api.OrderApi", "com.example.OrderTest", "com.example.InvoiceTest");

        // then
        assertEquals(setOf("com.example.OrderTest", "com.example.InvoiceTest"), suites);
    }

    @Test
    void helperNoSuiteRefersToHasNoDependants(@TempDir Path dir) throws Exception {
        // given
        java(dir, "com.example.util.UnusedHelper", "package com.example.util;", "public class UnusedHelper {}");
        java(dir, "com.example.OrderTest", "package com.example;", "class OrderTest {}");

        // when
        Set<String> suites = dependants(dir, "com.example.util.UnusedHelper", "com.example.OrderTest");

        // then
        assertEquals(Collections.emptySet(), suites);
    }

    @Test
    void deletedHelperIsStillFoundThroughTheFilesReferringToIt(@TempDir Path dir) throws Exception {
        // given - the helper's source is gone, but OrderTest still imports it
        java(dir, "com.example.orders.OrderTest", "package com.example.orders;",
                "import com.example.support.OrderTestHelper;", "class OrderTest {}");

        // when
        Set<String> suites = dependants(dir, "com.example.support.OrderTestHelper", "com.example.orders.OrderTest");

        // then
        assertEquals(setOf("com.example.orders.OrderTest"), suites);
    }

    @Test
    void trackedNestedSuiteOfADependantClassDependsOnTheHelper(@TempDir Path dir) throws Exception {
        // given - only the @Nested suite is tracked, under its Outer$Nested name
        java(dir, "com.example.OrderTestHelper", "package com.example;", "public class OrderTestHelper {}");
        java(dir, "com.example.OrderTest", "package com.example;",
                "class OrderTest { class WhenPaid { OrderTestHelper helper; } }");

        // when
        Set<String> suites = dependants(dir, "com.example.OrderTestHelper", "com.example.OrderTest$WhenPaid");

        // then
        assertEquals(setOf("com.example.OrderTest$WhenPaid"), suites);
    }

    @Test
    void trackedNestedSuiteOfTheChangedClassItselfDependsOnIt(@TempDir Path dir) throws Exception {
        // given - the changed file's top-level class isn't tracked, but its @Nested suite is
        java(dir, "com.example.OrderTest", "package com.example;", "class OrderTest { class WhenPaid {} }");

        // when
        Set<String> suites = dependants(dir, "com.example.OrderTest", "com.example.OrderTest$WhenPaid");

        // then
        assertEquals(setOf("com.example.OrderTest$WhenPaid"), suites);
    }

    @Test
    void groovySpecWithoutSemicolonsInAnotherDirDependsOnTheJavaHelper(@TempDir Path dir) throws Exception {
        // given - the helper is under src/test/java, the spec under src/test/groovy
        Path javaDir = Files.createDirectories(dir.resolve("java"));
        Path groovyDir = Files.createDirectories(dir.resolve("groovy"));
        java(javaDir, "com.example.support.OrderTestHelper", "package com.example.support;", "public class OrderTestHelper {}");
        source(groovyDir, "com.example.orders.OrderSpec", "groovy", "package com.example.orders",
                "import com.example.support.OrderTestHelper", "class OrderSpec extends spock.lang.Specification {}");

        // when
        Set<String> suites = TestSourceDependencyIndex.build(Arrays.asList(javaDir.toString(), groovyDir.toString()),
                singletonList("com.example.support.OrderTestHelper"), singletonList("com.example.orders.OrderSpec"))
                .dependantSuites("com.example.support.OrderTestHelper");

        // then
        assertEquals(setOf("com.example.orders.OrderSpec"), suites);
    }

    @Test
    void referenceCycleEndsTheWalk(@TempDir Path dir) throws Exception {
        // given - two helpers referring to each other, one used by a suite
        java(dir, "com.example.HelperA", "package com.example;", "class HelperA { HelperB b; }");
        java(dir, "com.example.HelperB", "package com.example;", "class HelperB { HelperA a; }");
        java(dir, "com.example.OrderTest", "package com.example;", "class OrderTest { HelperB b; }");

        // when
        Set<String> suites = dependants(dir, "com.example.HelperA", "com.example.OrderTest");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void packageInfoAndNonSourceFilesAreIgnored(@TempDir Path dir) throws Exception {
        // given - package-info names the helper's simple name in a comment; a resource file does too
        java(dir, "com.example.OrderTestHelper", "package com.example;", "public class OrderTestHelper {}");
        source(dir, "com.example.package-info", "java", "/** Uses OrderTestHelper. */", "package com.example;");
        source(dir, "com.example.orders", "json", "{\"helper\": \"OrderTestHelper\"}");

        // when
        Set<String> suites = dependants(dir, "com.example.OrderTestHelper", "com.example.OrderTest");

        // then
        assertEquals(Collections.emptySet(), suites);
    }

    @Test
    void missingTestDirIndexesNothing(@TempDir Path dir) {
        // given
        String missing = dir.resolve("not-there").toString();

        // when
        Set<String> suites = TestSourceDependencyIndex.build(singletonList(missing),
                singletonList("com.example.OrderTestHelper"), singletonList("com.example.OrderTest"))
                .dependantSuites("com.example.OrderTestHelper");

        // then
        assertEquals(Collections.emptySet(), suites);
    }

    @Test
    void topLevelClassCutsAtTheFirstDollarOfTheSimpleName() {
        // given
        String nested = "com.example.Outer$Inner$Deeper";

        // when
        String topLevel = TestSourceDependencyIndex.topLevelClass(nested);

        // then
        assertEquals("com.example.Outer", topLevel);
        assertEquals("com.example.Outer", TestSourceDependencyIndex.topLevelClass("com.example.Outer"));
        assertEquals("com.example.Outer", TestSourceDependencyIndex.topLevelClass("com.example.Outer$1"));
    }
}
