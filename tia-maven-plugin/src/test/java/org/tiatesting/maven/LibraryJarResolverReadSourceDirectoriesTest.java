package org.tiatesting.maven;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LibraryJarResolver#filterExistingSourceRoots(List, String, org.apache.maven.plugin.logging.Log)},
 * which drops a library's declared compile source roots that don't exist on disk.
 *
 * <p>Maven always adds the conventional {@code src/main/java} compile root to a project's model
 * even when it has no Java (e.g. a database library whose sources are SQL under
 * {@code src/main/resources}). Recording that phantom root makes Tia hand it to the VCS and warn
 * about it as mapped-but-not-present on every run, so it must be filtered out here.
 */
class LibraryJarResolverReadSourceDirectoriesTest {

    /**
     * An existing compile source root is kept (as an absolute, normalised path) while a declared
     * root that doesn't exist on disk (a DB project's empty default {@code src/main/java}) is
     * dropped.
     */
    @Test
    void keepsExistingRootsAndDropsNonExistentRoots(@TempDir File projectDir) {
        // given - a real src/main/resources dir and a declared-but-absent src/main/java
        File existingRoot = new File(projectDir, "src/main/resources");
        assertTrue(existingRoot.mkdirs(), "test setup: could not create the existing source root");
        String missingRoot = new File(projectDir, "src/main/java").getAbsolutePath();
        List<String> declaredRoots = Arrays.asList(existingRoot.getAbsolutePath(), missingRoot);

        // when
        List<String> result = LibraryJarResolver.filterExistingSourceRoots(
                declaredRoots, "com.ea.ut:utasdb:jar:1.0.0", new SystemStreamLog());

        // then - only the existing root survives, normalised to an absolute path
        String expected = Paths.get(existingRoot.getAbsolutePath()).toAbsolutePath().normalize().toString();
        assertEquals(Collections.singletonList(expected), result);
    }

    /**
     * Null, empty and blank declared roots are skipped without error, and a null compile-root list
     * yields an empty result.
     */
    @Test
    void skipsNullEmptyAndBlankRootsAndHandlesNullList() {
        // given - a list with null, empty and whitespace-only entries
        List<String> declaredRoots = new ArrayList<>();
        declaredRoots.add(null);
        declaredRoots.add("");
        declaredRoots.add("   ");

        // when
        List<String> fromMessyList = LibraryJarResolver.filterExistingSourceRoots(
                declaredRoots, "com.ea:lib:jar:1.0.0", new SystemStreamLog());
        List<String> fromNullList = LibraryJarResolver.filterExistingSourceRoots(
                null, "com.ea:lib:jar:1.0.0", new SystemStreamLog());

        // then - both resolve to an empty result
        assertTrue(fromMessyList.isEmpty(), "null/empty/blank roots must be skipped");
        assertTrue(fromNullList.isEmpty(), "a null compile-root list must yield an empty result");
    }
}
