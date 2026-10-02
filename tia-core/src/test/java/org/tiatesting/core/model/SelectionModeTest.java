package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.*;

class SelectionModeTest {

    /**
     * tiaReseed implies tiaSelectAllTests, so it wins when both are set.
     */
    @Test
    void reseedWinsOverSelectAll() {
        // given
        boolean selectAllTests = true;
        boolean reseed = true;

        // when
        SelectionMode mode = SelectionMode.fromFlags(selectAllTests, reseed);

        // then
        assertEquals(SelectionMode.RESEED, mode);
    }

    /**
     * With neither flag set the mode is ordinary selection, which is not a full run.
     */
    @Test
    void noFlagsIsSelective() {
        // given
        boolean selectAllTests = false;
        boolean reseed = false;

        // when
        SelectionMode mode = SelectionMode.fromFlags(selectAllTests, reseed);

        // then
        assertEquals(SelectionMode.SELECTIVE, mode);
        assertFalse(mode.isFullRun());
    }

    /**
     * Every mode but SELECTIVE runs every test, and only the two flag modes count as forced.
     */
    @Test
    void everyNonSelectiveModeIsAFullRunAndOnlyTheFlagModesAreForced() {
        // given

        // when

        // then
        assertTrue(SelectionMode.SEED.isFullRun());
        assertTrue(SelectionMode.SELECT_ALL.isFullRun());
        assertTrue(SelectionMode.RESEED.isFullRun());
        assertFalse(SelectionMode.SEED.isForced());
        assertTrue(SelectionMode.SELECT_ALL.isForced());
        assertTrue(SelectionMode.RESEED.isForced());
    }

    /**
     * A re-seed on a build that does not own mapping updates is refused, naming the flag.
     */
    @Test
    void reseedWithoutMappingOwnershipIsRefused() {
        // given
        SelectionMode mode = SelectionMode.RESEED;

        // when
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mode.requireMappingOwner(false));

        // then
        assertTrue(e.getMessage().contains("tiaReseed"));
    }

    /**
     * Select-all without mapping ownership is allowed - it is a plain full run.
     */
    @Test
    void selectAllWithoutMappingOwnershipIsAllowed() {
        // given
        SelectionMode mode = SelectionMode.SELECT_ALL;

        // when
        Executable call = () -> mode.requireMappingOwner(false);

        // then
        assertDoesNotThrow(call);
    }

    /**
     * A missing or unrecognised stored mode reads as SELECTIVE.
     */
    @Test
    void unknownOrMissingStoredNameReadsAsSelective() {
        // given

        // when

        // then
        assertEquals(SelectionMode.SELECTIVE, SelectionMode.fromStoredName(null));
        assertEquals(SelectionMode.SELECTIVE, SelectionMode.fromStoredName("NOPE"));
        assertEquals(SelectionMode.RESEED, SelectionMode.fromStoredName("RESEED"));
    }
}
