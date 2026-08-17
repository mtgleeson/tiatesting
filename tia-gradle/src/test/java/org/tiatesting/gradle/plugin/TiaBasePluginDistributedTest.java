package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.vcs.VCSReader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
 * Covers the three Gradle-side pieces of distributed-run configuration: the {@code tia.buildDir}
 * extension property, the {@code tia.distributed} master switch, and the {@code tia-select-tests}
 * grouping preview never throwing out of {@code doLast} on a misconfigured distributed run.
 */
class TiaBasePluginDistributedTest {

    /** Minimal concrete plugin so the abstract base can be applied and queried in tests. */
    static class TestPlugin extends TiaBasePlugin {
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
     * Build a seed selection - no stored mapping exists yet for the tracked branch, so {@code
     * runAllTests} is true and both {@code testsToRun} and {@code testsToIgnore} are empty, per
     * {@link TestSelectorResult#isRunAllTests()}.
     *
     * @return a selection with {@code runAllTests} true and no selected or ignored tests
     */
    private static TestSelectorResult seedSelection() {
        return new TestSelectorResult(Collections.<String>emptySet(), Collections.<String>emptySet(), null,
                0L, Collections.<String>emptySet(), 0L, Collections.<String, Long>emptyMap(), 0L, 0L, true);
    }

    /**
     * Verifies that {@link TiaBasePlugin#getTiaBuildDir()} falls back to {@code
     * <project build dir>/tia} when {@code tia.buildDir} is not configured, matching the
     * pre-existing hardcoded behaviour so nothing breaks for projects that never set the property.
     */
    @Test
    void getTiaBuildDir_notConfigured_fallsBackToProjectBuildDirTia(@TempDir File projectDir) {
        // given a project with the Tia plugin applied and no buildDir configured
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TestPlugin plugin = (TestPlugin) project.getPlugins().apply(TestPlugin.class);

        // when
        String tiaBuildDir = plugin.getTiaBuildDir();

        // then
        String expected = project.getLayout().getBuildDirectory().getAsFile().get().getPath()
                + File.separator + "tia";
        assertEquals(expected, tiaBuildDir);
    }

    /**
     * Verifies that a configured {@code tia.buildDir} overrides the computed default,
     * the Gradle analog of Maven's {@code -DtiaBuildDir=...} - the lever a multi-project pipeline
     * needs when the plugin is applied to a subproject and the plan file must land at a predictable
     * path.
     */
    @Test
    void getTiaBuildDir_configured_overridesDefault(@TempDir File projectDir) {
        // given a project with tia.buildDir explicitly configured
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TestPlugin plugin = (TestPlugin) project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setBuildDir("/custom/plan/output");

        // when
        String tiaBuildDir = plugin.getTiaBuildDir();

        // then
        assertEquals("/custom/plan/output", tiaBuildDir);
    }

    /**
     * Verifies that {@code tia.distributed} round-trips through the extension and is readable via
     * {@link TiaBasePlugin#getDistributed()}, the master switch the claim protocol branches on -
     * readable on Maven ({@code isTiaDistributed()}) but for a time absent entirely on Gradle.
     */
    @Test
    void distributed_roundTripsOnExtensionAndPlugin(@TempDir File projectDir) {
        // given a project with tia.distributed explicitly enabled
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TestPlugin plugin = (TestPlugin) project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);

        // when
        ext.setDistributed(true);

        // then
        assertTrue(ext.getDistributed());
        assertTrue(plugin.getDistributed());
    }

    /**
     * Verifies {@code tia.distributed} defaults to {@code null} (falsy) when never configured,
     * matching the Maven side's primitive {@code boolean} default of {@code false}.
     */
    @Test
    void distributed_defaultsToNullWhenNotConfigured() {
        // given a fresh, unconfigured extension
        TiaBaseTaskExtension ext = new TiaBaseTaskExtension();

        // when / then
        assertFalse(Boolean.TRUE.equals(ext.getDistributed()));
    }

    /**
     * Verifies at the Gradle entry point that {@link
     * TiaBasePlugin#printDistributedRunPreviewIfConfigured} with a distributed grouping
     * configuration that {@link org.tiatesting.core.distributed.DistributedRunPlanner#balance}
     * rejects (both a fixed group count and a max-group ceiling, the shape a shared parent pom
     * makes easy to produce) does not throw out of what would otherwise be a
     * {@code doLast} closure - it prints a skip notice instead and lets {@code tia-select-tests}
     * complete normally.
     */
    @Test
    void printDistributedRunPreviewIfConfigured_invalidGroupingShape_doesNotThrowAndPrintsSkipNotice(
            @TempDir File projectDir) {
        // given a plugin configured with a distributed grouping shape balance() rejects
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TestPlugin plugin = (TestPlugin) project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setDistributedGroupCount(4);
        ext.setDistributedMaxGroups(8);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

            // when
            assertDoesNotThrow(() ->
                    plugin.printDistributedRunPreviewIfConfigured(twoSuiteSelection(), "\n"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(originalOut);
        }

        // then - a skip notice was printed instead of the preview block, and nothing propagated
        String printed = new String(captured.toByteArray(), StandardCharsets.UTF_8);
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
    void printDistributedRunPreviewIfConfigured_validGroupingShape_printsPreview(@TempDir File projectDir) {
        // given a plugin configured with a valid, static-groups grouping shape
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TestPlugin plugin = (TestPlugin) project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setDistributedGroupCount(2);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

            // when
            plugin.printDistributedRunPreviewIfConfigured(twoSuiteSelection(), "\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(originalOut);
        }

        // then
        String printed = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(printed.contains("Distributed run grouping preview (not persisted):"),
                "expected the ordinary preview block, got: " + printed);
        assertFalse(printed.contains("skipped"), "no skip notice expected, got: " + printed);
    }

    /**
     * Verifies the seed-run handling at the Gradle {@code createSelectTestsTask} branch this
     * class tests indirectly: {@link TiaBasePlugin#printDistributedRunPreviewIfConfigured} still
     * renders a coherent preview - the seed-run notice, one group, no target verdict - when called
     * with a seed selection ({@link TestSelectorResult#isRunAllTests()} true), the exact selection
     * shape {@code createSelectTestsTask} passes on the "all (no stored mapping for this branch
     * yet)" branch. {@link org.tiatesting.core.distributed.DistributedRunPlanner#balance}
     * collapses a seed selection to a single empty group regardless of the configured group count,
     * so this also proves that collapse reaches the console unchanged on the Gradle side.
     */
    @Test
    void printDistributedRunPreviewIfConfigured_seedSelection_printsSeedRunPreview(@TempDir File projectDir) {
        // given a plugin configured with a distributed grouping shape, previewing a seed selection
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TestPlugin plugin = (TestPlugin) project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setDistributedGroupCount(2);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

            // when
            plugin.printDistributedRunPreviewIfConfigured(seedSelection(), "\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(originalOut);
        }

        // then
        String printed = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(printed.contains("Distributed run grouping preview (not persisted):"),
                "expected the preview block, got: " + printed);
        assertTrue(printed.contains("Seed run: no stored mapping exists yet for this branch"),
                "expected the seed-run notice, got: " + printed);
        assertTrue(printed.contains("Groups: 1"), "expected the single collapsed group, got: " + printed);
        assertFalse(printed.contains("Target:"), "a seed run has no target verdict to print: " + printed);
        assertFalse(printed.contains("skipped"), "no skip notice expected, got: " + printed);
    }
}
