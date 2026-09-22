package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link TestClassScanner}: turning compiled test-class directories into the suite names a
 * seed run splits across its groups. Writes real {@code .class} files under a temp directory
 * rather than mocking a filesystem, since the scanner's whole job is walking one.
 */
class TestClassScannerTest {

    /**
     * Create an empty file at the given relative path under root, creating parent directories as
     * needed, so a test can stage a compiled-class layout on disk.
     *
     * @param root the directory the relative path is created under
     * @param relativePath the class-file path relative to root, using {@code /} separators
     * @throws IOException if the file or its parents cannot be created
     */
    private static void touchClassFile(final Path root, final String relativePath) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }

    /**
     * The key behaviour-change test: a nested class's binary name ({@code Outer$Inner}) must be
     * included in the scan, not dropped, because Tia tracks a JUnit5 {@code @Nested} test suite by
     * exactly that binary name. Dropping it would leave a tracked suite absent from every seed-run
     * group's assignment, which mis-seals a split seed run ({@code allTestsRun=false}) for any
     * project using {@code @Nested}.
     *
     * @param dir a temporary directory to stage a compiled test-class layout under
     * @throws IOException if a staged class file cannot be created
     */
    @Test
    void scanTestSuiteNames_returnsAllCompiledClassNamesIncludingNested(@TempDir Path dir) throws IOException {
        // given a compiled test layout with a nested package and an inner/nested class
        touchClassFile(dir, "com/example/ATest.class");
        touchClassFile(dir, "com/example/sub/BTest.class");
        touchClassFile(dir, "com/example/ATest$Inner.class");

        // when
        Set<String> suites = TestClassScanner.scanTestSuiteNames(dir.toString());

        // then every compiled class is returned as a dotted binary FQN, nested class included
        assertEquals(3, suites.size());
        assertTrue(suites.contains("com.example.ATest"));
        assertTrue(suites.contains("com.example.sub.BTest"));
        assertTrue(suites.contains("com.example.ATest$Inner"));
    }

    @Test
    void scanTestSuiteNames_mergesDirsAndSkipsAbsentOnes(@TempDir Path dir) throws IOException {
        // given one real directory and one that does not exist
        touchClassFile(dir, "com/example/ATest.class");
        String csv = dir.toString() + ",/no/such/dir/does/not/exist";

        // when
        Set<String> suites = TestClassScanner.scanTestSuiteNames(csv);

        // then the absent directory is skipped rather than failing the scan
        assertEquals(1, suites.size());
        assertTrue(suites.contains("com.example.ATest"));
    }

    @Test
    void scanTestSuiteNames_returnsEmptyForNullBlankOrNoDirs() {
        // given no usable directories at all
        // when
        Set<String> fromNull = TestClassScanner.scanTestSuiteNames(null);
        Set<String> fromBlank = TestClassScanner.scanTestSuiteNames("   ");
        Set<String> fromAbsent = TestClassScanner.scanTestSuiteNames("/no/such/dir");

        // then
        assertTrue(fromNull.isEmpty());
        assertTrue(fromBlank.isEmpty());
        assertTrue(fromAbsent.isEmpty());
    }
}
