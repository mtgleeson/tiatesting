package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tiatesting.core.testrunner.ClassFileFixture.classIn;

/**
 * Cover {@link TestClassDependencyIndex}: finding the tracked suites that depend on a changed test
 * support class from the compiled test classes. Every class file is written by
 * {@link ClassFileFixture} into a temp directory, so each test states exactly which references a
 * class carries and where in the class file they sit.
 */
class TestClassDependencyIndexTest {

    private static final String JUNIT5_TEST = "org.junit.jupiter.api.Test";

    /**
     * Build an index over one directory.
     *
     * @param dir the test-classes directory
     * @param changedClass the changed support class the index is queried for
     * @param trackedSuites the tracked suite names
     * @return the index
     */
    private static TestClassDependencyIndex indexFor(final Path dir, final String changedClass,
                                                     final String... trackedSuites) {
        return TestClassDependencyIndex.build(singletonList(dir.toString()), singletonList(changedClass),
                Arrays.asList(trackedSuites));
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
    void suiteExtendingTheChangedBaseClassDependsOnIt(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.BaseTest").access(Opcodes.ACC_ABSTRACT).write();
        classIn(dir, "com.example.OrderTest").extending("com.example.BaseTest").method("works", JUNIT5_TEST).write();
        classIn(dir, "com.example.OtherTest").method("works", JUNIT5_TEST).write();

        // when
        Set<String> suites = indexFor(dir, "com.example.BaseTest", "com.example.OrderTest", "com.example.OtherTest")
                .dependantSuites("com.example.BaseTest");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void suiteImplementingTheChangedInterfaceDependsOnIt(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.Fixtures").access(Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT).write();
        classIn(dir, "com.example.OrderTest").implementing("com.example.Fixtures").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.Fixtures", "com.example.OrderTest")
                .dependantSuites("com.example.Fixtures");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void suiteCallingTheChangedHelperDependsOnIt(@TempDir Path dir) throws Exception {
        // given - the helper is only the owner of a method call, a constant pool class entry
        classIn(dir, "com.example.OrderTestHelper").write();
        classIn(dir, "com.example.OrderTest").callingStatic("com.example.OrderTestHelper").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.OrderTestHelper", "com.example.OrderTest")
                .dependantSuites("com.example.OrderTestHelper");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void suiteNamingTheChangedClassOnlyInAFieldTypeDependsOnIt(@TempDir Path dir) throws Exception {
        // given - a field type has no class entry, only the field's descriptor
        classIn(dir, "com.example.api.OrderApi").write();
        classIn(dir, "com.example.OrderTest").field("com.example.api.OrderApi").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.api.OrderApi", "com.example.OrderTest")
                .dependantSuites("com.example.api.OrderApi");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void suiteNamingTheChangedClassOnlyInAParameterTypeDependsOnIt(@TempDir Path dir) throws Exception {
        // given - a parameter type has no class entry, only the method's descriptor
        classIn(dir, "com.example.OrderBuilder").write();
        classIn(dir, "com.example.OrderTest").methodTaking("com.example.OrderBuilder").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.OrderBuilder", "com.example.OrderTest")
                .dependantSuites("com.example.OrderBuilder");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void suiteUsingTheChangedClassAsAnAnnotationDependsOnIt(@TempDir Path dir) throws Exception {
        // given - an annotation type is named only in the annotation's descriptor
        classIn(dir, "com.example.WithOrders")
                .access(Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION).write();
        classIn(dir, "com.example.OrderTest").annotatedWith("com.example.WithOrders").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.WithOrders", "com.example.OrderTest")
                .dependantSuites("com.example.WithOrders");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void suiteReachingTheChangedHelperThroughAnotherClassDependsOnIt(@TempDir Path dir) throws Exception {
        // given - OrderTest extends BaseTest (not a suite), which calls OrderApi
        classIn(dir, "com.example.api.OrderApi").write();
        classIn(dir, "com.example.BaseTest").access(Opcodes.ACC_ABSTRACT).callingStatic("com.example.api.OrderApi").write();
        classIn(dir, "com.example.OrderTest").extending("com.example.BaseTest").write();
        classIn(dir, "com.example.InvoiceTest").field("com.example.OrderTest").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.api.OrderApi", "com.example.OrderTest", "com.example.InvoiceTest")
                .dependantSuites("com.example.api.OrderApi");

        // then
        assertEquals(setOf("com.example.OrderTest", "com.example.InvoiceTest"), suites);
    }

    @Test
    void helperNoSuiteReferencesHasNoDependants(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.util.UnusedHelper").write();
        classIn(dir, "com.example.OrderTest").method("works", JUNIT5_TEST).write();

        // when
        Set<String> suites = indexFor(dir, "com.example.util.UnusedHelper", "com.example.OrderTest")
                .dependantSuites("com.example.util.UnusedHelper");

        // then
        assertEquals(Collections.emptySet(), suites);
    }

    @Test
    void referenceToANestedClassOfTheHelperCountsAsTheHelper(@TempDir Path dir) throws Exception {
        // given - OrderTest only uses the helper's nested builder
        classIn(dir, "com.example.OrderTestHelper").write();
        classIn(dir, "com.example.OrderTestHelper$Builder").write();
        classIn(dir, "com.example.OrderTest").callingStatic("com.example.OrderTestHelper$Builder").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.OrderTestHelper", "com.example.OrderTest")
                .dependantSuites("com.example.OrderTestHelper");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void referenceFromANestedClassOfTheSuiteCountsAsTheSuite(@TempDir Path dir) throws Exception {
        // given - only the suite's anonymous class calls the helper
        classIn(dir, "com.example.OrderTestHelper").write();
        classIn(dir, "com.example.OrderTest").method("works", JUNIT5_TEST).write();
        classIn(dir, "com.example.OrderTest$1").callingStatic("com.example.OrderTestHelper").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.OrderTestHelper", "com.example.OrderTest")
                .dependantSuites("com.example.OrderTestHelper");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void trackedNestedSuiteOfADependantClassDependsOnTheHelper(@TempDir Path dir) throws Exception {
        // given - only the @Nested suite is tracked, under its Outer$Nested name
        classIn(dir, "com.example.OrderTestHelper").write();
        classIn(dir, "com.example.OrderTest").write();
        classIn(dir, "com.example.OrderTest$WhenPaid").callingStatic("com.example.OrderTestHelper").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.OrderTestHelper", "com.example.OrderTest$WhenPaid")
                .dependantSuites("com.example.OrderTestHelper");

        // then
        assertEquals(setOf("com.example.OrderTest$WhenPaid"), suites);
    }

    @Test
    void trackedNestedSuiteOfTheChangedClassItselfDependsOnIt(@TempDir Path dir) throws Exception {
        // given - the changed file's top-level class isn't tracked, but its @Nested suite is
        classIn(dir, "com.example.OrderTest").write();
        classIn(dir, "com.example.OrderTest$WhenPaid").method("works", JUNIT5_TEST).write();

        // when
        Set<String> suites = indexFor(dir, "com.example.OrderTest", "com.example.OrderTest$WhenPaid")
                .dependantSuites("com.example.OrderTest");

        // then
        assertEquals(setOf("com.example.OrderTest$WhenPaid"), suites);
    }

    @Test
    void deletedHelperIsStillFoundThroughTheClassesReferencingIt(@TempDir Path dir) throws Exception {
        // given - the helper's class file is gone, but OrderTest's class file still names it
        classIn(dir, "com.example.OrderTest").callingStatic("com.example.OrderTestHelper").write();

        // when
        TestClassDependencyIndex index = indexFor(dir, "com.example.OrderTestHelper", "com.example.OrderTest");

        // then
        assertFalse(index.hasClassFile("com.example.OrderTestHelper"));
        assertEquals(setOf("com.example.OrderTest"), index.dependantSuites("com.example.OrderTestHelper"));
    }

    @Test
    void referencesToClassesOutsideTheTestDirsAreNotFollowed(@TempDir Path dir) throws Exception {
        // given - both suites use a library class, which is not a changed class
        classIn(dir, "com.example.OrderTest").callingStatic("org.assertj.core.api.Assertions").write();
        classIn(dir, "com.example.InvoiceTest").callingStatic("org.assertj.core.api.Assertions").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.OrderTest", "com.example.OrderTest", "com.example.InvoiceTest")
                .dependantSuites("org.assertj.core.api.Assertions");

        // then
        assertEquals(Collections.emptySet(), suites);
    }

    @Test
    void referenceCycleEndsTheWalk(@TempDir Path dir) throws Exception {
        // given - two helpers referencing each other, one used by a suite
        classIn(dir, "com.example.HelperA").callingStatic("com.example.HelperB").write();
        classIn(dir, "com.example.HelperB").callingStatic("com.example.HelperA").write();
        classIn(dir, "com.example.OrderTest").callingStatic("com.example.HelperB").write();

        // when
        Set<String> suites = indexFor(dir, "com.example.HelperA", "com.example.OrderTest")
                .dependantSuites("com.example.HelperA");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void classesAcrossSeveralDirsAreIndexedTogether(@TempDir Path dir) throws Exception {
        // given - the helper and the suite are compiled into different dirs (e.g. Java and Groovy)
        Path javaDir = Files.createDirectories(dir.resolve("java"));
        Path groovyDir = Files.createDirectories(dir.resolve("groovy"));
        classIn(javaDir, "com.example.OrderTestHelper").write();
        classIn(groovyDir, "com.example.OrderSpec").callingStatic("com.example.OrderTestHelper").write();
        Collection<String> dirs = Arrays.asList(javaDir.toString(), groovyDir.toString());

        // when
        TestClassDependencyIndex index = TestClassDependencyIndex.build(dirs,
                singletonList("com.example.OrderTestHelper"), singletonList("com.example.OrderSpec"));

        // then
        assertTrue(index.hasClassFile("com.example.OrderTestHelper"));
        assertEquals(setOf("com.example.OrderSpec"), index.dependantSuites("com.example.OrderTestHelper"));
    }

    @Test
    void unreadableClassFileIsSkippedAndTheRestAreIndexed(@TempDir Path dir) throws Exception {
        // given - a truncated class file next to a readable suite
        classIn(dir, "com.example.OrderTestHelper").write();
        classIn(dir, "com.example.OrderTest").callingStatic("com.example.OrderTestHelper").write();
        Files.write(dir.resolve("com/example/Broken.class"), new byte[]{(byte) 0xCA, (byte) 0xFE});

        // when
        Set<String> suites = indexFor(dir, "com.example.OrderTestHelper", "com.example.OrderTest", "com.example.Broken")
                .dependantSuites("com.example.OrderTestHelper");

        // then
        assertEquals(setOf("com.example.OrderTest"), suites);
    }

    @Test
    void noExistingDirMeansNoClassDirsAndNoDependants(@TempDir Path dir) {
        // given
        String missing = dir.resolve("not-compiled-yet").toString();

        // when
        TestClassDependencyIndex index = TestClassDependencyIndex.build(singletonList(missing),
                singletonList("com.example.OrderTestHelper"), singletonList("com.example.OrderTest"));

        // then
        assertFalse(index.hasClassDirs());
        assertFalse(index.hasClassFile("com.example.OrderTestHelper"));
        assertEquals(Collections.emptySet(), index.dependantSuites("com.example.OrderTestHelper"));
    }

    @Test
    void existingDirReportsClassDirsAndClassFiles(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.OrderTestHelper").write();
        classIn(dir, "com.example.OrderTestHelper$Builder").write();

        // when
        TestClassDependencyIndex index = indexFor(dir, "com.example.OrderTestHelper");

        // then
        assertTrue(index.hasClassDirs());
        assertTrue(index.hasClassFile("com.example.OrderTestHelper"));
        assertTrue(index.hasClassFile("com.example.OrderTestHelper$Builder"));
        assertFalse(index.hasClassFile("com.example.Missing"));
    }

    @Test
    void topLevelClassCutsAtTheFirstDollarOfTheSimpleName() {
        // given
        String nested = "com.example.Outer$Inner$Deeper";

        // when
        String topLevel = TestClassDependencyIndex.topLevelClass(nested);

        // then
        assertEquals("com.example.Outer", topLevel);
        assertEquals("com.example.Outer", TestClassDependencyIndex.topLevelClass("com.example.Outer"));
        assertEquals("com.example.Outer", TestClassDependencyIndex.topLevelClass("com.example.Outer$1"));
    }
}
