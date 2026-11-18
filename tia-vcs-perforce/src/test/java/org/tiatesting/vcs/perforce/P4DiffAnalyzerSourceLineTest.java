package org.tiatesting.vcs.perforce;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Coverage for {@link P4DiffAnalyzer#joinSourceLine}: the line built from resolved depot paths. */
class P4DiffAnalyzerSourceLineTest {

    @Test
    void depotPathsAreSortedTrimmedAndJoined() {
        // given two source roots resolved in arbitrary order, one with a trailing wildcard
        List<String> depotPaths = Arrays.asList(
                "//depot/libraries/libA/src/main/kotlin/...", "//depot/libraries/libA/src/main/java");

        // when the line is built
        String line = P4DiffAnalyzer.joinSourceLine(depotPaths, true);

        // then the paths are sorted, stripped of the wildcard and comma-joined
        assertEquals("//depot/libraries/libA/src/main/java,//depot/libraries/libA/src/main/kotlin", line);
    }

    @Test
    void caseInsensitiveServerGivesOneLineForBothSpellings() {
        // given the same root spelled in two cases on a case-insensitive server
        String upper = P4DiffAnalyzer.joinSourceLine(
                Collections.singletonList("//Depot/Libraries/libA/src/..."), false);

        // when the other spelling is built
        String lower = P4DiffAnalyzer.joinSourceLine(
                Collections.singletonList("//depot/libraries/liba/src/..."), false);

        // then both give the same line
        assertEquals(lower, upper);
    }

    @Test
    void noResolvedPathsGivesNoLine() {
        // given no source root resolved through the client view
        List<String> depotPaths = Collections.emptyList();

        // when the line is built
        String line = P4DiffAnalyzer.joinSourceLine(depotPaths, true);

        // then there is no line
        assertNull(line);
    }
}
