package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.tiatesting.core.testrunner.TestSuiteRunnabilityClassifier.Verdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tiatesting.core.testrunner.ClassFileFixture.classIn;

/**
 * Cover {@link TestSuiteRunnabilityClassifier}: deciding from compiled class files alone whether a
 * suite name can ever be run by a test engine. Every class file is written by
 * {@link ClassFileFixture} into a temp directory, so each test states exactly the shape it
 * classifies. The verdicts that excuse a suite must be provable; anything the classifier cannot
 * see must come back {@link Verdict#UNDETERMINED}, which no caller excuses.
 */
class TestSuiteRunnabilityClassifierTest {

    private static final String JUNIT5_TEST = "org.junit.jupiter.api.Test";

    /**
     * Build a classifier over a single directory.
     *
     * @param dir the test-classes directory
     * @return the classifier
     */
    private static TestSuiteRunnabilityClassifier classifierFor(final Path dir) {
        return TestSuiteRunnabilityClassifier.forDirs(singletonList(dir.toString()));
    }

    @Test
    void concreteJUnit5ClassWithTestMethodIsRunnable(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.FooTest").method("works", JUNIT5_TEST).write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.FooTest");

        // then
        assertEquals(Verdict.RUNNABLE, verdict);
        assertFalse(verdict.isNeverRunnable());
        assertNull(verdict.getReason());
    }

    @Test
    void testMethodInheritedFromAbstractSuperclassInTheSameDirIsRunnable(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.Base").access(Opcodes.ACC_ABSTRACT).method("works", JUNIT5_TEST).write();
        classIn(dir, "com.example.ChildTest").extending("com.example.Base").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.ChildTest");

        // then
        assertEquals(Verdict.RUNNABLE, verdict);
    }

    @Test
    void testDefaultMethodOnATestInterfaceInTheSameDirIsRunnable(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.Contract").access(Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)
                .method("honoursContract", JUNIT5_TEST).write();
        classIn(dir, "com.example.ImplTest").implementing("com.example.Contract").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.ImplTest");

        // then
        assertEquals(Verdict.RUNNABLE, verdict);
    }

    @Test
    void junit4TestIsRunnable(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.LegacyTest").method("works", "org.junit.Test").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.LegacyTest");

        // then
        assertEquals(Verdict.RUNNABLE, verdict);
    }

    @Test
    void classLevelRunWithIsRunnable(@TempDir Path dir) throws Exception {
        // given - a JUnit 4 suite runner class with no test methods of its own
        classIn(dir, "com.example.AllTests").annotatedWith("org.junit.runner.RunWith").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.AllTests");

        // then
        assertEquals(Verdict.RUNNABLE, verdict);
    }

    @Test
    void composedTestAnnotationDefinedInTheTestTreeIsRunnable(@TempDir Path dir) throws Exception {
        // given - @FastTest is meta-annotated with @Test, and the suite only uses @FastTest
        classIn(dir, "com.example.FastTest")
                .access(Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION)
                .annotatedWith(JUNIT5_TEST).write();
        classIn(dir, "com.example.ComposedTest").method("works", "com.example.FastTest").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.ComposedTest");

        // then
        assertEquals(Verdict.RUNNABLE, verdict);
    }

    @Test
    void spockSpecificationSubclassIsRunnable(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.FooSpec").extending("spock.lang.Specification").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.FooSpec");

        // then
        assertEquals(Verdict.RUNNABLE, verdict);
    }

    @Test
    void abstractClassIsNotRunnable(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.AbstractBase").access(Opcodes.ACC_ABSTRACT)
                .method("works", JUNIT5_TEST).write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.AbstractBase");

        // then
        assertEquals(Verdict.ABSTRACT, verdict);
        assertTrue(verdict.isNeverRunnable());
        assertTrue(verdict.isProvenNotATest());
        assertEquals("abstract class", verdict.getReason());
    }

    @Test
    void interfaceIsNotRunnable(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.FooApi").access(Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)
                .method("fetch", null).write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.FooApi");

        // then
        assertEquals(Verdict.INTERFACE, verdict);
        assertTrue(verdict.isProvenNotATest());
    }

    @Test
    void helperWithNoTestMethodsIsNotRunnable(@TempDir Path dir) throws Exception {
        // given - an API client helper whose only annotation is from a non-test library
        classIn(dir, "com.example.FooApiClient")
                .method("getName", "com.fasterxml.jackson.annotation.JsonProperty")
                .method("fetch", null).write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.FooApiClient");

        // then
        assertEquals(Verdict.NO_TEST_METHODS, verdict);
        assertTrue(verdict.isProvenNotATest());
        assertEquals("no test methods", verdict.getReason());
    }

    @Test
    void helperExtendingAJdkClassIsNotRunnable(@TempDir Path dir) throws Exception {
        // given
        classIn(dir, "com.example.FixtureMap").extending("java.util.HashMap").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.FixtureMap");

        // then
        assertEquals(Verdict.NO_TEST_METHODS, verdict);
    }

    @Test
    void superclassOutsideTheScannedDirsIsUndetermined(@TempDir Path dir) throws Exception {
        // given - a JUnit 3 test: its test methods are found by name, from a base class in a jar
        classIn(dir, "com.example.OldStyleTest").extending("junit.framework.TestCase")
                .method("testWorks", null).write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.OldStyleTest");

        // then
        assertEquals(Verdict.UNDETERMINED, verdict);
        assertFalse(verdict.isNeverRunnable());
        assertFalse(verdict.isProvenNotATest());
    }

    @Test
    void externalInterfaceDoesNotMakeAHelperUndetermined(@TempDir Path dir) throws Exception {
        // given - a helper implementing a library interface that is not in the test dirs
        classIn(dir, "com.example.FooSupplier").implementing("org.example.lib.Supplier").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.FooSupplier");

        // then
        assertEquals(Verdict.NO_TEST_METHODS, verdict);
    }

    @Test
    void missingClassFileIsNotRunnableButNotProvenNotATest(@TempDir Path dir) throws Exception {
        // given - a dir that exists but holds no class for the suite
        classIn(dir, "com.example.SomethingElse").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.DeletedTest");

        // then
        assertEquals(Verdict.NO_CLASS_FILE, verdict);
        assertTrue(verdict.isNeverRunnable());
        assertFalse(verdict.isProvenNotATest());
        assertEquals("no class file", verdict.getReason());
    }

    @Test
    void nestedClassNameResolvesItsOwnClassFile(@TempDir Path dir) throws Exception {
        // given - only the nested class carries tests
        classIn(dir, "com.example.Outer").write();
        classIn(dir, "com.example.Outer$Inner").method("works", JUNIT5_TEST).write();

        // when
        Verdict inner = classifierFor(dir).classify("com.example.Outer$Inner");
        Verdict outer = classifierFor(dir).classify("com.example.Outer");

        // then
        assertEquals(Verdict.RUNNABLE, inner);
        assertEquals(Verdict.NO_TEST_METHODS, outer);
    }

    @Test
    void classFoundInTheSecondOfSeveralDirsIsClassified(@TempDir Path dir) throws Exception {
        // given - a Gradle-style split: java and groovy output dirs
        Path javaDir = Files.createDirectories(dir.resolve("java"));
        Path groovyDir = Files.createDirectories(dir.resolve("groovy"));
        classIn(groovyDir, "com.example.FooSpec").extending("spock.lang.Specification").write();

        // when
        Verdict verdict = TestSuiteRunnabilityClassifier
                .forDirs(Arrays.asList(javaDir.toString(), groovyDir.toString()))
                .classify("com.example.FooSpec");

        // then
        assertEquals(Verdict.RUNNABLE, verdict);
    }

    @Test
    void superclassInAnotherScannedDirIsFollowed(@TempDir Path dir) throws Exception {
        // given - the base class with the tests lives in the second dir
        Path first = Files.createDirectories(dir.resolve("first"));
        Path second = Files.createDirectories(dir.resolve("second"));
        classIn(second, "com.example.Base").access(Opcodes.ACC_ABSTRACT).method("works", JUNIT5_TEST).write();
        classIn(first, "com.example.ChildTest").extending("com.example.Base").write();

        // when
        Verdict verdict = TestSuiteRunnabilityClassifier
                .forDirs(Arrays.asList(first.toString(), second.toString()))
                .classify("com.example.ChildTest");

        // then
        assertEquals(Verdict.RUNNABLE, verdict);
    }

    @Test
    void everyVerdictIsUndeterminedWhenNoConfiguredDirExists(@TempDir Path dir) {
        // given - a wrong path: no configured dir exists
        TestSuiteRunnabilityClassifier classifier = classifierFor(dir.resolve("missing"));

        // when
        Verdict verdict = classifier.classify("com.example.AnyTest");

        // then - never NO_CLASS_FILE, or every unobserved suite would look deleted
        assertEquals(Verdict.UNDETERMINED, verdict);
    }

    @Test
    void disabledClassifierReturnsUndetermined() {
        // given
        TestSuiteRunnabilityClassifier classifier = TestSuiteRunnabilityClassifier.disabled();

        // when
        Verdict verdict = classifier.classify("com.example.AnyTest");

        // then
        assertEquals(Verdict.UNDETERMINED, verdict);
    }

    @Test
    void fromCsvSplitsAndTrimsDirs(@TempDir Path dir) throws Exception {
        // given
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        classIn(b, "com.example.FooTest").method("works", JUNIT5_TEST).write();

        // when
        Verdict verdict = TestSuiteRunnabilityClassifier.fromCsv(" " + a + " , " + b + " ")
                .classify("com.example.FooTest");

        // then
        assertEquals(Verdict.RUNNABLE, verdict);
    }

    @Test
    void fromCsvWithNullOrBlankIsDisabled() {
        // given
        TestSuiteRunnabilityClassifier fromNull = TestSuiteRunnabilityClassifier.fromCsv(null);
        TestSuiteRunnabilityClassifier fromBlank = TestSuiteRunnabilityClassifier.fromCsv("  ");

        // when
        Verdict nullVerdict = fromNull.classify("com.example.AnyTest");
        Verdict blankVerdict = fromBlank.classify("com.example.AnyTest");

        // then
        assertEquals(Verdict.UNDETERMINED, nullVerdict);
        assertEquals(Verdict.UNDETERMINED, blankVerdict);
    }

    @Test
    void hierarchyCycleDoesNotLoopForever(@TempDir Path dir) throws Exception {
        // given - an interface cycle javac cannot produce, but a class file can describe
        int iface = Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        classIn(dir, "com.example.I1").access(iface).implementing("com.example.I2").write();
        classIn(dir, "com.example.I2").access(iface).implementing("com.example.I1").write();
        classIn(dir, "com.example.Helper").implementing("com.example.I1").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.Helper");

        // then
        assertEquals(Verdict.NO_TEST_METHODS, verdict);
    }

    @Test
    void annotationMetaAnnotationCycleDoesNotLoopForever(@TempDir Path dir) throws Exception {
        // given - two annotation types annotating each other, neither a test marker
        int annotation = Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION;
        classIn(dir, "com.example.A1").access(annotation).annotatedWith("com.example.A2").write();
        classIn(dir, "com.example.A2").access(annotation).annotatedWith("com.example.A1").write();
        classIn(dir, "com.example.Helper").method("run", "com.example.A1").write();

        // when
        Verdict verdict = classifierFor(dir).classify("com.example.Helper");

        // then
        assertEquals(Verdict.NO_TEST_METHODS, verdict);
    }

    @Test
    void corruptClassFileFailsRatherThanBeingExcused(@TempDir Path dir) throws Exception {
        // given - a class file that is not a class file
        Path classFile = dir.resolve("com/example/BrokenTest.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, new byte[]{1, 2, 3});
        TestSuiteRunnabilityClassifier classifier = classifierFor(dir);

        // when / then - never silently treated as missing
        assertThrows(IllegalStateException.class, () -> classifier.classify("com.example.BrokenTest"));
    }
}
