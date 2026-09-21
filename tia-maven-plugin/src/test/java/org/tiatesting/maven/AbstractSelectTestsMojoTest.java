package org.tiatesting.maven;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.distributed.GroupingResult;
import org.tiatesting.core.distributed.SuiteGroup;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.vcs.VCSReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Maven entry point's contract that
 * {@link AbstractSelectTestsMojo#printDistributedRunPreview} must never let a
 * misconfigured distributed grouping abort the read-only {@code select-tests} goal.
 */
class AbstractSelectTestsMojoTest {

    /**
     * Minimal concrete mojo so the abstract base can be instantiated in a test. Overrides {@link
     * #getProject()} rather than the private {@code project} field {@link AbstractTiaMojo} holds,
     * since the seed-suite disk scan now reads {@code getProject().getBuild().getTestOutputDirectory()}
     * every time a seed selection is balanced with a group count or max-groups ceiling configured -
     * every test in this class that previews a seed selection depends on this returning a non-null
     * project, not only the seed-preview test below that sets {@link #testOutputDirectory}.
     */
    private static final class TestMojo extends AbstractSelectTestsMojo {
        /** The test output directory {@link #getProject()} reports; null unless a test sets it. */
        private String testOutputDirectory;

        @Override
        public VCSReader getVCSReader() {
            return null;
        }

        /**
         * Build a bare {@link MavenProject} whose build reports {@link #testOutputDirectory}, so
         * the mojo's seed-suite provider can resolve a test-output directory without a real Maven
         * session.
         *
         * @return a project whose {@code getBuild().getTestOutputDirectory()} returns {@link
         *         #testOutputDirectory} (null unless a test sets it)
         */
        @Override
        public MavenProject getProject() {
            Model model = new Model();
            Build build = new Build();
            build.setTestOutputDirectory(testOutputDirectory);
            model.setBuild(build);
            return new MavenProject(model);
        }
    }

    /**
     * Balance the selection and print its preview, the two-step sequence {@code execute()} performs
     * so the estimate block above the preview can report the heaviest group. Wrapped here so each
     * test drives the same pair of calls the goal itself makes rather than only the printing half.
     *
     * @param mojo the goal under test
     * @param selection the selection to balance and preview
     */
    private static void previewFor(final TestMojo mojo, final TestSelectorResult selection) {
        mojo.printDistributedRunPreview(selection, mojo.buildDistributedGroupingIfConfigured(selection));
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
                5000L, Collections.<String>emptySet(), 0L, runTimes, 0L, 500L, 0L, false,
                TestRunSelectionDetails.empty());
    }

    /**
     * Build a seed selection - no stored mapping exists yet for the tracked branch, so {@code
     * runAllTests} is true and both {@code testsToRun} and {@code testsToIgnore} are empty, per
     * {@link TestSelectorResult#isRunAllTests()}.
     *
     * @return a selection with {@code runAllTests} true and no selected or ignored tests
     */
    private static TestSelectorResult seedSelection() {
        return new TestSelectorResult(Collections.<String>emptySet(), Collections.<String>emptySet(), null,
                0L, Collections.<String>emptySet(), 0L, Collections.<String, Long>emptyMap(), 0L, 0L, 0L, true,
                TestRunSelectionDetails.empty());
    }

    /**
     * Verifies that a distributed grouping shape {@link
     * org.tiatesting.core.distributed.DistributedRunPlanner#balance} rejects (here, both a fixed
     * group count and a max-group ceiling - the mistake a shared parent pom makes easy to make)
     * does not throw out of {@code select-tests}. Instead a skip notice naming the
     * offending property is printed and the read-only command completes normally.
     */
    @Test
    void printDistributedRunPreview_invalidGroupingShape_doesNotThrowAndPrintsSkipNotice() {
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
            assertDoesNotThrow(() -> previewFor(mojo, twoSuiteSelection()));
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
    void printDistributedRunPreview_validGroupingShape_printsPreview() {
        // given a mojo configured with a valid, static-groups grouping shape
        TestMojo mojo = new TestMojo();
        mojo.tiaDistributedGroupCount = 2;

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String printed;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

            // when
            previewFor(mojo, twoSuiteSelection());
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

    /**
     * Verifies the seed-run handling at the {@code execute()} branch this class tests
     * indirectly: {@link AbstractSelectTestsMojo#printDistributedRunPreview} still
     * renders a coherent preview - the seed-run notice, one group, no target verdict - when called
     * with a seed selection ({@link TestSelectorResult#isRunAllTests()} true), the exact selection
     * shape {@code execute()} passes on the "all (no stored mapping for this branch yet)" branch.
     * {@link org.tiatesting.core.distributed.DistributedRunPlanner#balance} collapses a seed
     * selection to a single empty group regardless of the configured group count, so this also
     * proves that collapse reaches the console unchanged.
     */
    @Test
    void printDistributedRunPreview_seedSelection_printsSeedRunPreview() {
        // given a mojo configured with a distributed grouping shape, previewing a seed selection
        TestMojo mojo = new TestMojo();
        mojo.tiaDistributedGroupCount = 2;

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String printed;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

            // when
            previewFor(mojo, seedSelection());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(originalOut);
            printed = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        }

        // then
        assertTrue(printed.contains("Distributed run grouping preview (not persisted):"),
                "expected the preview block, got: " + printed);
        assertTrue(printed.contains("Seed run: no stored mapping exists yet for this branch"),
                "expected the seed-run notice, got: " + printed);
        assertTrue(printed.contains("Groups: 1"), "expected the single collapsed group, got: " + printed);
        assertFalse(printed.contains("Target:"), "a seed run has no target verdict to print: " + printed);
        assertFalse(printed.contains("skipped"), "no skip notice expected, got: " + printed);
    }

    /**
     * Verifies the seed-run disk scan this class's {@link TestMojo#getProject()} override exists
     * for: a seed selection balanced against a two-group distributed shape, with two compiled test
     * classes staged under the project's test output directory, is split across both groups instead
     * of collapsing to the single empty group {@link
     * #printDistributedRunPreview_seedSelection_printsSeedRunPreview()} covers when nothing is found
     * on disk.
     *
     * @throws IOException if the temporary test-output directory or its staged class files cannot
     *                      be created
     */
    @Test
    void buildDistributedGroupingIfConfigured_seedSelectionWithClassesOnDisk_splitsAcrossConfiguredGroups()
            throws IOException {
        // given a mojo configured for two groups, previewing a seed selection with two compiled
        // test classes staged under the project's test output directory
        TestMojo mojo = new TestMojo();
        mojo.tiaDistributedGroupCount = 2;
        Path testOutputDir = Files.createTempDirectory("tia-seed-preview-test");
        Files.createFile(testOutputDir.resolve("ATest.class"));
        Files.createFile(testOutputDir.resolve("BTest.class"));
        mojo.testOutputDirectory = testOutputDir.toString();

        // when
        GroupingResult grouping = mojo.buildDistributedGroupingIfConfigured(seedSelection());
        int nonEmptyGroupCount = 0;
        Set<String> suiteNameUnion = new HashSet<>();
        for (SuiteGroup group : grouping.getGroups()) {
            if (!group.getSuiteNames().isEmpty()) {
                nonEmptyGroupCount++;
            }
            suiteNameUnion.addAll(group.getSuiteNames());
        }

        // then - both groups received one of the two discovered classes
        assertEquals(2, grouping.getGroupCount(), "expected two groups, got: " + grouping.getGroups());
        assertEquals(2, nonEmptyGroupCount,
                "expected both groups to be non-empty, got: " + grouping.getGroups());
        assertEquals(2, suiteNameUnion.size(),
                "expected the suite union to cover both discovered classes, got: " + suiteNameUnion);
    }
}
