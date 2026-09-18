package org.tiatesting.core.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the offline-safe directory + filename matcher that resolves {@code tiaSourceLibs}
 * coordinates to jar paths without any Maven/Gradle dependency graph: exact matches, the
 * version-digit guard, classifier exclusion, and the no-match / ambiguous / bad-directory skip
 * behaviour with their warnings.
 */
class LibraryJarDirectoryResolverTest {

    /**
     * Collects the messages pushed to a logging sink so assertions can inspect warn/debug output.
     */
    private static final class RecordingSink implements Consumer<String> {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void accept(String message) {
            messages.add(message);
        }

        /**
         * @param needle a substring to look for
         * @return true if any recorded message contains the needle
         */
        boolean contains(String needle) {
            for (String m : messages) {
                if (m.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Create an empty file at {@code dir/name} so the resolver can list it as a candidate jar.
     *
     * @param dir the directory to create the file in
     * @param name the filename
     * @return the created file
     * @throws IOException if the file cannot be created
     */
    private static File touch(Path dir, String name) throws IOException {
        File f = dir.resolve(name).toFile();
        Files.createFile(f.toPath());
        return f;
    }

    @Test
    void resolvesExactMatchToAbsolutePath(@TempDir Path libDir) throws IOException {
        // given
        File jar = touch(libDir, "widgets-1.2.3.jar");
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "com.example:widgets", Collections.singletonList(libDir.toString()), warn, debug);

        // then
        assertEquals(Collections.singletonList(jar.getAbsolutePath()), resolved);
        assertTrue(debug.contains("Resolved coordinate 'com.example:widgets' to " + jar.getAbsolutePath()));
        assertTrue(warn.messages.isEmpty());
    }

    @Test
    void versionDigitGuardRejectsArtifactIdPrefixMatch(@TempDir Path libDir) throws IOException {
        // given - only foo-bar-1.0.jar exists; coordinate 'foo' must not match it
        touch(libDir, "foo-bar-1.0.jar");
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "com.example:foo", Collections.singletonList(libDir.toString()), warn, debug);

        // then
        assertTrue(resolved.isEmpty());
        assertTrue(warn.contains("not found in tiaLibraryJarsDirs"));
    }

    @Test
    void excludesSourcesAndJavadocClassifierJars(@TempDir Path libDir) throws IOException {
        // given - the real jar plus its sources/javadoc siblings
        File main = touch(libDir, "widgets-1.2.3.jar");
        touch(libDir, "widgets-1.2.3-sources.jar");
        touch(libDir, "widgets-1.2.3-javadoc.jar");
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "com.example:widgets", Collections.singletonList(libDir.toString()), warn, debug);

        // then - only the main jar resolves; the classifiers don't make it ambiguous
        assertEquals(Collections.singletonList(main.getAbsolutePath()), resolved);
        assertTrue(warn.messages.isEmpty());
    }

    @Test
    void noMatchWarnsAndSkips(@TempDir Path libDir) throws IOException {
        // given - a directory with an unrelated jar
        touch(libDir, "other-9.9.jar");
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "com.example:widgets", Collections.singletonList(libDir.toString()), warn, debug);

        // then
        assertTrue(resolved.isEmpty());
        assertTrue(warn.contains("tiaSourceLibs coordinate 'com.example:widgets' not found in tiaLibraryJarsDirs"));
    }

    @Test
    void multipleVersionMatchesWarnsAndSkips(@TempDir Path libDir) throws IOException {
        // given - two versions of the same artifact in one directory
        touch(libDir, "widgets-1.2.3.jar");
        touch(libDir, "widgets-1.3.0.jar");
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "com.example:widgets", Collections.singletonList(libDir.toString()), warn, debug);

        // then - ambiguous, so nothing resolves and the candidates are listed
        assertTrue(resolved.isEmpty());
        assertTrue(warn.contains("matched multiple jars"));
        assertTrue(warn.contains("widgets-1.2.3.jar"));
        assertTrue(warn.contains("widgets-1.3.0.jar"));
    }

    @Test
    void searchesAcrossMultipleDirectories(@TempDir Path dirA, @TempDir Path dirB) throws IOException {
        // given - each coordinate's jar lives in a different directory
        File widgets = touch(dirA, "widgets-1.0.jar");
        File gadgets = touch(dirB, "gadgets-2.0.jar");
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "com.example:widgets,com.example:gadgets",
                Arrays.asList(dirA.toString(), dirB.toString()), warn, debug);

        // then - both resolve, in coordinate declaration order
        assertEquals(Arrays.asList(widgets.getAbsolutePath(), gadgets.getAbsolutePath()), resolved);
        assertTrue(warn.messages.isEmpty());
    }

    @Test
    void missingDirectoryWarnsAndIsSkippedButOthersStillSearched(@TempDir Path libDir) throws IOException {
        // given - one good directory and one that does not exist
        File jar = touch(libDir, "widgets-1.0.jar");
        String missing = libDir.resolve("does-not-exist").toString();
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "com.example:widgets", Arrays.asList(missing, libDir.toString()), warn, debug);

        // then - the missing dir warns but the good dir still resolves the jar
        assertEquals(Collections.singletonList(jar.getAbsolutePath()), resolved);
        assertTrue(warn.contains("is missing or not a directory"));
    }

    @Test
    void thirdProjectDirSegmentIsIgnoredForMatching(@TempDir Path libDir) throws IOException {
        // given - a coordinate carrying the optional :projectDir segment
        File jar = touch(libDir, "widgets-1.0.jar");
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "com.example:widgets:/some/source/dir", Collections.singletonList(libDir.toString()), warn, debug);

        // then - only the artifactId is used, so the jar still resolves
        assertEquals(Collections.singletonList(jar.getAbsolutePath()), resolved);
    }

    @Test
    void malformedCoordinateWarnsAndSkips(@TempDir Path libDir) throws IOException {
        // given - an entry with no colon is not a valid groupId:artifactId
        touch(libDir, "widgets-1.0.jar");
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "widgets", Collections.singletonList(libDir.toString()), warn, debug);

        // then
        assertTrue(resolved.isEmpty());
        assertTrue(warn.contains("Invalid tiaSourceLibs coordinate 'widgets'"));
    }

    @Test
    void blankCsvReturnsEmptyWithoutWarning() {
        // given
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "   ", Collections.singletonList("/tmp"), warn, debug);

        // then
        assertTrue(resolved.isEmpty());
        assertTrue(warn.messages.isEmpty());
        assertFalse(debug.contains("Resolved"));
    }

    @Test
    void sameJarReachedThroughTwoDirectoryEntriesResolvesOnce(@TempDir Path libDir) throws IOException {
        // given - the same directory listed twice must not read as an ambiguous double match
        File jar = touch(libDir, "widgets-1.0.jar");
        RecordingSink warn = new RecordingSink();
        RecordingSink debug = new RecordingSink();

        // when
        List<String> resolved = LibraryJarDirectoryResolver.resolveLibraryJars(
                "com.example:widgets", Arrays.asList(libDir.toString(), libDir.toString()), warn, debug);

        // then
        assertEquals(Collections.singletonList(jar.getAbsolutePath()), resolved);
        assertTrue(warn.messages.isEmpty());
    }
}
