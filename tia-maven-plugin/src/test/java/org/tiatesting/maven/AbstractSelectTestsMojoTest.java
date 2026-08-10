package org.tiatesting.maven;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.vcs.VCSReader;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers finding 1 of the stage 4b final review at the Maven entry point:
 * {@link AbstractSelectTestsMojo#printDistributedRunPreviewIfConfigured} must never let a
 * misconfigured distributed grouping abort the read-only {@code select-tests} goal.
 */
class AbstractSelectTestsMojoTest {

    /** Minimal concrete mojo so the abstract base can be instantiated in a test. */
    private static final class TestMojo extends AbstractSelectTestsMojo {
        @Override
        public VCSReader getVCSReader() {
            return null;
        }
    }

    /**
     * Build a selection of two suites with distinct, known run times, so a grouping preview has
     * something concrete to balance.
     *
     * @return a selection with two suites in {@code testsToRun}, each with a recorded run time
     */
    private static TestSelectorResult twoSuiteSelection() {
        Map<String, Long> runTimes = new HashMap<>();
        runTimes.put("com.example.ATest", 3000L);
        runTimes.put("com.example.BTest", 2000L);
        Set<String> testsToRun = new HashSet<>(runTimes.keySet());
        return new TestSelectorResult(testsToRun, Collections.<String>emptySet(), null,
                5000L, Collections.<String>emptySet(), 0L, runTimes, 0L, 500L, false);
    }

    /**
     * Verifies finding 1's fix: a distributed grouping shape {@link
     * org.tiatesting.core.distributed.DistributedRunPlanner#balance} rejects (here, both a fixed
     * group count and a max-group ceiling - the exact shared-parent-pom mistake finding 1
     * describes) does not throw out of {@code select-tests}. Instead a skip notice naming the
     * offending property is printed and the read-only command completes normally.
     */
    @Test
    void printDistributedRunPreviewIfConfigured_invalidGroupingShape_doesNotThrowAndPrintsSkipNotice() {
        // given a mojo configured with a distributed grouping shape balance() rejects
        TestMojo mojo = new TestMojo();
        mojo.tiaDistributedGroupCount = 4;
        mojo.tiaDistributedMaxGroups = 8;

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String printed;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

            // when
            assertDoesNotThrow(() -> mojo.printDistributedRunPreviewIfConfigured(twoSuiteSelection()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(originalOut);
            printed = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        }

        // then - a skip notice was printed instead of the preview block, and nothing propagated
        assertTrue(printed.contains("Distributed run grouping preview skipped:"),
                "expected a skip notice, got: " + printed);
        assertTrue(printed.contains("tiaDistributedMaxGroups"),
                "skip notice should name the offending property, got: " + printed);
        assertFalse(printed.contains("Distributed run grouping preview (not persisted):"),
                "no preview block should have been printed: " + printed);
    }

    /**
     * Verifies the counterpart of the fix above: a valid distributed grouping configuration still
     * prints the ordinary preview block, unaffected by the new try/catch.
     */
    @Test
    void printDistributedRunPreviewIfConfigured_validGroupingShape_printsPreview() {
        // given a mojo configured with a valid, static-groups grouping shape
        TestMojo mojo = new TestMojo();
        mojo.tiaDistributedGroupCount = 2;

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String printed;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

            // when
            mojo.printDistributedRunPreviewIfConfigured(twoSuiteSelection());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(originalOut);
            printed = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        }

        // then
        assertTrue(printed.contains("Distributed run grouping preview (not persisted):"),
                "expected the ordinary preview block, got: " + printed);
        assertFalse(printed.contains("skipped"), "no skip notice expected, got: " + printed);
    }
}
