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
 * Cover {@link TestClassScanner}: turning compiled test-class directories into the top-level
 * suite names a seed run splits across its groups. Writes real {@code .class} files under a temp
 * directory rather than mocking a filesystem, since the scanner's whole job is walking one.
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

    @Test
    void scanTopLevelTestSuiteNames_returnsFqnsAndDropsInnerClasses(@TempDir Path dir) throws IOException {
        // given a compiled test layout with a nested package and an inner class
        touchClassFile(dir, "com/example/ATest.class");
        touchClassFile(dir, "com/example/sub/BTest.class");
        touchClassFile(dir, "com/example/ATest$Inner.class");

        // when
        Set<String> suites = TestClassScanner.scanTopLevelTestSuiteNames(dir.toString());

        // then the top-level classes are returned as dotted FQNs and the inner class is dropped
        assertEquals(2, suites.size());
        assertTrue(suites.contains("com.example.ATest"));
        assertTrue(suites.contains("com.example.sub.BTest"));
    }

    @Test
    void scanTopLevelTestSuiteNames_mergesDirsAndSkipsAbsentOnes(@TempDir Path dir) throws IOException {
        // given one real directory and one that does not exist
        touchClassFile(dir, "com/example/ATest.class");
        String csv = dir.toString() + ",/no/such/dir/does/not/exist";

        // when
        Set<String> suites = TestClassScanner.scanTopLevelTestSuiteNames(csv);

        // then the absent directory is skipped rather than failing the scan
        assertEquals(1, suites.size());
        assertTrue(suites.contains("com.example.ATest"));
    }

    @Test
    void scanTopLevelTestSuiteNames_returnsEmptyForNullBlankOrNoDirs() {
        // given no usable directories at all
        // when
        Set<String> fromNull = TestClassScanner.scanTopLevelTestSuiteNames(null);
        Set<String> fromBlank = TestClassScanner.scanTopLevelTestSuiteNames("   ");
        Set<String> fromAbsent = TestClassScanner.scanTopLevelTestSuiteNames("/no/such/dir");

        // then
        assertTrue(fromNull.isEmpty());
        assertTrue(fromBlank.isEmpty());
        assertTrue(fromAbsent.isEmpty());
    }
}
