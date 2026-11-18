package org.tiatesting.core.library;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coverage for {@link SourceLines#matches}: a null line matches any line. */
class SourceLinesTest {

    @Test
    void equalLinesMatch() {
        // given two identical lines
        String line = "//depot/libraries/libA/src/main/java";

        // when they are compared
        boolean matches = SourceLines.matches(line, "//depot/libraries/libA/src/main/java");

        // then they match
        assertTrue(matches);
    }

    @Test
    void differentLinesDoNotMatch() {
        // given two different lines
        String mainline = "//depot/mainline/libA/src/main/java";

        // when they are compared
        boolean matches = SourceLines.matches(mainline, "//depot/future/libA/src/main/java");

        // then they do not match
        assertFalse(matches);
    }

    @Test
    void nullLineMatchesAnyLine() {
        // given an unknown line on either side
        String known = "main";

        // when it is compared with a known line in both orders
        boolean leftNull = SourceLines.matches(null, known);
        boolean rightNull = SourceLines.matches(known, null);

        // then both match
        assertTrue(leftNull);
        assertTrue(rightNull);
    }

    @Test
    void sourceRootsUnderTheBuildDirectoryAreDropped() {
        // given a versioned source root and a generated-sources root inside the build directory
        String versioned = new File("/projects/lib/src/main/java").getAbsolutePath();
        String generated = new File("/projects/lib/target/generated-sources/annotations").getAbsolutePath();

        // when the build output roots are removed
        List<String> kept = SourceLines.withoutBuildOutput(Arrays.asList(versioned, generated),
                new File("/projects/lib/target").getAbsolutePath());

        // then only the versioned root remains
        assertEquals(Collections.singletonList(versioned), kept);
    }

    @Test
    void aSiblingDirectorySharingTheBuildDirectoryNamePrefixIsKept() {
        // given a source root whose path merely starts with the build directory's name
        String sibling = new File("/projects/lib/target-sources/java").getAbsolutePath();

        // when the build output roots are removed
        List<String> kept = SourceLines.withoutBuildOutput(Collections.singletonList(sibling),
                new File("/projects/lib/target").getAbsolutePath());

        // then it is kept
        assertEquals(Collections.singletonList(sibling), kept);
    }

    @Test
    void anUnknownBuildDirectoryKeepsEveryRoot() {
        // given source roots and no build directory
        List<String> roots = Arrays.asList("/projects/lib/src/main/java", "/projects/lib/gen");

        // when the build output roots are removed with a null and a blank build directory
        List<String> withNull = SourceLines.withoutBuildOutput(roots, null);
        List<String> withBlank = SourceLines.withoutBuildOutput(roots, " ");

        // then nothing is dropped, and a null list yields an empty one
        assertEquals(roots, withNull);
        assertEquals(roots, withBlank);
        assertTrue(SourceLines.withoutBuildOutput(null, "/projects/lib/target").isEmpty());
    }
}
