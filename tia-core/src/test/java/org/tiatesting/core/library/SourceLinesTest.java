package org.tiatesting.core.library;

import org.junit.jupiter.api.Test;

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
}
