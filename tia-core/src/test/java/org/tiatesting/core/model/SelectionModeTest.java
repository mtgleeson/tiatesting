package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.*;

class SelectionModeTest {

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

    @Test
    void selectAllWithoutMappingOwnershipIsAllowed() {
        // given
        SelectionMode mode = SelectionMode.SELECT_ALL;

        // when
        Executable call = () -> mode.requireMappingOwner(false);

        // then
        assertDoesNotThrow(call);
    }

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
