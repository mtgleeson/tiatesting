package org.tiatesting.maven;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.vcs.VCSReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cover the mapping of the {@code tiaSelectAllTests} and {@code tiaReseed} parameters to a
 * {@link SelectionMode}. See the "Forced runs and re-seed" chapter in {@code WIKI.md}.
 */
class AbstractTiaMojoSelectionModeTest {

    @Test
    void noFlagIsSelective() {
        // given
        TestMojo mojo = new TestMojo();

        // when
        SelectionMode mode = mojo.getSelectionMode();

        // then
        assertEquals(SelectionMode.SELECTIVE, mode);
    }

    @Test
    void selectAllFlagMapsToSelectAllMode() {
        // given
        TestMojo mojo = new TestMojo();
        mojo.tiaSelectAllTests = true;

        // when
        SelectionMode mode = mojo.getSelectionMode();

        // then
        assertEquals(SelectionMode.SELECT_ALL, mode);
    }

    @Test
    void reseedFlagWinsOverSelectAll() {
        // given
        TestMojo mojo = new TestMojo();
        mojo.tiaSelectAllTests = true;
        mojo.tiaReseed = true;

        // when
        SelectionMode mode = mojo.getSelectionMode();

        // then
        assertEquals(SelectionMode.RESEED, mode);
    }

    /**
     * Minimal concrete mojo: only the flag fields and {@link AbstractTiaMojo#getSelectionMode()}
     * are exercised.
     */
    private static final class TestMojo extends AbstractTiaMojo {

        /**
         * @return never called by these tests
         */
        @Override
        public VCSReader getVCSReader() {
            throw new UnsupportedOperationException("these tests do not reach the VCS");
        }

        /**
         * Never called by these tests.
         */
        @Override
        public void execute() {
            throw new UnsupportedOperationException("these tests do not execute the mojo");
        }
    }
}
