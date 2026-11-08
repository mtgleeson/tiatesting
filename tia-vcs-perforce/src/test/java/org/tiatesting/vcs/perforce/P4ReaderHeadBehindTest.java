package org.tiatesting.vcs.perforce;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link P4Reader#isChangelistBehind}: the head is behind a changelist only when its
 * number is lower.
 */
class P4ReaderHeadBehindTest {

    @Test
    void lowerHead_isBehind() {
        // given
        String head = "1005";

        // when
        boolean behind = P4Reader.isChangelistBehind(head, "1010");

        // then
        assertTrue(behind);
    }

    @Test
    void equalHead_isNotBehind() {
        // given
        String head = "1010";

        // when
        boolean behind = P4Reader.isChangelistBehind(head, "1010");

        // then
        assertFalse(behind);
    }

    @Test
    void higherHead_isNotBehind() {
        // given
        String head = "1012";

        // when
        boolean behind = P4Reader.isChangelistBehind(head, "1010");

        // then
        assertFalse(behind);
    }
}
