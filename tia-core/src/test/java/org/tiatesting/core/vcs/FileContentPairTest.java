package org.tiatesting.core.vcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for {@link FileContentPair}: value equality over both sides and a content-free description.
 */
class FileContentPairTest {

    @Test
    void pairsWithEqualSidesAreEqual() {
        // given
        FileContentPair first = new FileContentPair("<a/>", null);
        FileContentPair second = new FileContentPair("<a/>", null);

        // when
        boolean equal = first.equals(second);

        // then
        assertEquals(true, equal);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void pairsDifferingOnEitherSideAreNotEqual() {
        // given
        FileContentPair base = new FileContentPair("<a/>", "<b/>");

        // when
        FileContentPair differentOld = new FileContentPair("<x/>", "<b/>");
        FileContentPair differentNew = new FileContentPair("<a/>", null);

        // then
        assertNotEquals(base, differentOld);
        assertNotEquals(base, differentNew);
    }

    @Test
    void toStringDescribesLengthsNotContent() {
        // given
        FileContentPair pair = new FileContentPair("<secret/>", null);

        // when
        String description = pair.toString();

        // then
        assertEquals("FileContentPair{oldLength=9, newLength=absent}", description);
    }
}
