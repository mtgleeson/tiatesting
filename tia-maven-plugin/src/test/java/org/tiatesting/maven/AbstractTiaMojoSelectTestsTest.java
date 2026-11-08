package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link AbstractTiaMojo#selectTests}: a selection that refuses the run with an
 * {@link IllegalStateException} is reported as a {@link MojoExecutionException} carrying Tia's
 * message, so Maven shows it as the build failure rather than as a plugin crash.
 */
class AbstractTiaMojoSelectTestsTest {

    @Test
    void refusedSelection_isReportedAsABuildFailureWithTiasMessage() {
        // given
        IllegalStateException refusal = new IllegalStateException("Tia can't update the mapping from this workspace");
        Supplier<String> selection = () -> {
            throw refusal;
        };

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class,
                () -> AbstractTiaMojo.selectTests(selection));

        // then
        assertEquals(refusal.getMessage(), thrown.getMessage());
        assertSame(refusal, thrown.getCause());
    }

    @Test
    void successfulSelection_returnsItsResult() throws MojoExecutionException {
        // given
        Supplier<String> selection = () -> "selected";

        // when
        String result = AbstractTiaMojo.selectTests(selection);

        // then
        assertEquals("selected", result);
    }

    @Test
    void otherRuntimeFailures_areNotRewrapped() {
        // given
        Supplier<String> selection = () -> {
            throw new IllegalArgumentException("unexpected");
        };

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> AbstractTiaMojo.selectTests(selection));

        // then
        assertEquals("unexpected", thrown.getMessage());
    }
}
