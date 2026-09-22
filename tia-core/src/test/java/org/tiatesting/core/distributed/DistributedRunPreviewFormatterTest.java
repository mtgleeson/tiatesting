package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DistributedRunPreviewFormatter}, covering the static-groups case (no
 * target), a met target, and each of the two independent not-met reasons - including both applying
 * at once, which the formatter must report as two separate lever lines rather than picking one.
 */
class DistributedRunPreviewFormatterTest {

    /**
     * Build a two-group {@link GroupingResult} fixture with the given weights and outcome flags,
     * for use across the test methods below.
     *
     * @param groupWeightsMs the estimated weight, in ms, of each group in the fixture
     * @param targetMet whether the fixture should report the target as met
     * @param clampedToMaxGroups whether the fixture should report clamping to the max group ceiling
     * @param singleSuiteExceedsTarget whether the fixture should report a single suite exceeding
     *                                 the target
     * @return the constructed grouping result
     */
    private static GroupingResult groupingResult(final long[] groupWeightsMs, final boolean targetMet,
                                                  final boolean clampedToMaxGroups,
                                                  final boolean singleSuiteExceedsTarget) {
        List<SuiteGroup> groups = new java.util.ArrayList<>();
        for (int i = 0; i < groupWeightsMs.length; i++) {
            groups.add(new SuiteGroup(i, Collections.singletonList("Suite" + i), groupWeightsMs[i]));
        }
        return new GroupingResult(groups, targetMet, clampedToMaxGroups, singleSuiteExceedsTarget, false);
    }

    /**
     * Verify that a target missed because the fixed per-JVM cost alone meets it names that as the
     * lever, and does not name the single-suite one. The two are different problems with different
     * fixes: a heavy suite can be split, but a target below what starting a test JVM costs cannot be
     * met by any change to the selection - only by raising the target. Before the fixed cost was
     * modelled at all this plan reported a met target, leaving a user adding runners that each
     * brought another copy of the cost that was blowing the budget.
     */
    @Test
    void previewNamesTheFixedOverheadWhenItAloneMeetsTheTarget() {
        // given
        GroupingResult result = new GroupingResult(
                Collections.singletonList(
                        new SuiteGroup(0, Collections.singletonList("Suite0"), 700L)),
                false, false, false, true);

        // when
        String preview = DistributedRunPreviewFormatter.formatPreview(result, Long.valueOf(500L),
                false, "\n");

        // then
        assertTrue(preview.contains("Target: 500ms - not met"),
                "a target no group count can reach must be reported as missed");
        assertTrue(preview.contains(DistributedRunMissReasons.FIXED_OVERHEAD_EXCEEDS_TARGET),
                "the per-JVM cost must be named as the lever, since it is the only one that "
                        + "applies");
        assertFalse(preview.contains(DistributedRunMissReasons.SINGLE_SUITE_EXCEEDS_TARGET),
                "no suite is at fault, so naming one would send the user after the wrong fix");
    }

    /**
     * The three miss reasons are independent, so a plan that hits more than one reports all of
     * them rather than stopping at the first.
     */
    @Test
    void previewNamesEveryMissReasonThatApplies() {
        // given - clamped, over the per-JVM floor, and carrying an over-long suite
        GroupingResult result = new GroupingResult(
                Collections.singletonList(
                        new SuiteGroup(0, Collections.singletonList("Suite0"), 900L)),
                false, true, true, true);

        // when
        String preview = DistributedRunPreviewFormatter.formatPreview(result, Long.valueOf(500L),
                false, "\n");

        // then
        assertTrue(preview.contains(DistributedRunMissReasons.MAX_GROUPS_LIMITING),
                "the ceiling reason must be reported");
        assertTrue(preview.contains(DistributedRunMissReasons.FIXED_OVERHEAD_EXCEEDS_TARGET),
                "the per-JVM reason must be reported");
        assertTrue(preview.contains(DistributedRunMissReasons.SINGLE_SUITE_EXCEEDS_TARGET),
                "the single-suite reason must be reported");
    }

    /**
     * Verify that a static-groups preview (no configured target) reports the group count, the
     * average and heaviest group time, and an explicit "no target" line rather than a target
     * verdict, since a fixed group count has no target to meet.
     */
    @Test
    void staticGroupsPreviewReportsNoTarget() {
        // given a grouping result from a fixed group count, so there is no target
        GroupingResult result = groupingResult(new long[] {1000L, 2000L}, true, false, false);

        // when the preview is formatted with no target run time
        String preview = DistributedRunPreviewFormatter.formatPreview(result, null, false, "\n");

        // then it reports the groups and weights and states no target applies
        assertTrue(preview.contains("Groups: 2, average 1500ms per group, heaviest 2000ms"), preview);
        assertTrue(preview.contains("Target: none (static group count)"), preview);
        assertFalse(preview.contains("not met"), preview);
    }

    /**
     * Verify the heaviest group is reported here as grouping context - the figure the target verdict
     * is a verdict on - and that the block does not also state it as a duration. Its meaning as the
     * wall clock a distributed build waits for is reported by the {@code select-tests} estimate
     * block printed immediately above this one; saying it in both places said the same thing twice,
     * three lines apart.
     */
    @Test
    void previewReportsTheHeaviestGroupAsShapeNotAsADuration() {
        // given a grouping whose heaviest group differs from its average
        GroupingResult result = groupingResult(new long[] {1000L, 2000L}, true, false, false);

        // when
        String preview = DistributedRunPreviewFormatter.formatPreview(result, null, false, "\n");

        // then
        assertTrue(preview.contains("heaviest 2000ms"), preview);
        assertFalse(preview.contains("Wall clock:"), preview);
        assertFalse(preview.contains("Serial equivalent:"), preview);
    }

    /**
     * Verify a seed run's preview prints no target verdict. Its grouping is a single collapsed
     * group, so the configured group count and target were both ignored and there is no verdict to
     * give - the seed-run line says that instead.
     */
    @Test
    void seedRunPreviewReportsNoTargetVerdict() {
        // given a seed run's collapsed grouping
        GroupingResult result = groupingResult(new long[] {0L}, true, false, false);

        // when
        String preview = DistributedRunPreviewFormatter.formatPreview(result, null, true, "\n");

        // then
        assertTrue(preview.contains("Seed run:"), preview);
        assertFalse(preview.contains("Target:"), preview);
    }

    /**
     * Verify a seed run's preview prints no ms figures at all in its "Groups:" line: the balancer's
     * per-group weights for a seed run are a uniform 1ms device used only to divide suites evenly by
     * count, not a real time estimate, so the average/heaviest ms wording must not appear.
     */
    @Test
    void seedRunPreviewOmitsAverageAndHeaviestMsFigures() {
        // given a seed run split across two groups, each carrying the balancer's uniform weight
        List<SuiteGroup> groups = Arrays.asList(
                new SuiteGroup(0, Collections.singletonList("com.example.ATest"), 1L),
                new SuiteGroup(1, Collections.singletonList("com.example.BTest"), 1L));
        GroupingResult result = new GroupingResult(groups, true, false, false, false);

        // when
        String preview = DistributedRunPreviewFormatter.formatPreview(result, null, true, "\n");

        // then
        assertTrue(preview.contains("Groups: 2"), preview);
        assertFalse(preview.contains("ms per group"), preview);
        assertFalse(preview.contains("heaviest"), preview);
    }

    /**
     * Verify that a dynamic-groups preview whose heaviest group came in at or under the configured
     * target reports the target as met, with no lever lines.
     */
    @Test
    void dynamicGroupsPreviewReportsTargetMet() {
        // given a grouping result that met its configured target
        GroupingResult result = groupingResult(new long[] {5000L, 4000L}, true, false, false);

        // when the preview is formatted against that target
        String preview = DistributedRunPreviewFormatter.formatPreview(result, 6000L, false, "\n");

        // then it reports the target as met and names no lever
        assertTrue(preview.contains("Target: 6000ms - met"));
        assertFalse(preview.contains("lever:"));
    }

    /**
     * Verify that a preview whose target was missed solely because the configured max-group
     * ceiling limited the group count names only that lever, not the single-suite one.
     */
    @Test
    void targetNotMetNamesOnlyMaxGroupsLeverWhenOnlyThatApplies() {
        // given a grouping result clamped to the max group ceiling, but no single suite over target
        GroupingResult result = groupingResult(new long[] {9000L, 9000L}, false, true, false);

        // when the preview is formatted against a missed target
        String preview = DistributedRunPreviewFormatter.formatPreview(result, 6000L, false, "\n");

        // then it reports the target as not met and names the max-groups lever only
        assertTrue(preview.contains("Target: 6000ms - not met"));
        assertTrue(preview.contains("the configured max group count is limiting the group count"));
        assertFalse(preview.contains("a single suite is longer than the whole target"));
    }

    /**
     * Verify that a preview whose target was missed solely because a single suite alone is longer
     * than the whole target names only that lever, not the max-groups one.
     */
    @Test
    void targetNotMetNamesOnlySingleSuiteLeverWhenOnlyThatApplies() {
        // given a grouping result where one suite alone is heavier than the target, no clamping
        GroupingResult result = groupingResult(new long[] {9000L}, false, false, true);

        // when the preview is formatted against a missed target
        String preview = DistributedRunPreviewFormatter.formatPreview(result, 6000L, false, "\n");

        // then it reports the target as not met and names the single-suite lever only
        assertTrue(preview.contains("Target: 6000ms - not met"));
        assertFalse(preview.contains("the configured max group count is limiting the group count"));
        assertTrue(preview.contains("a single suite is longer than the whole target"));
    }

    /**
     * Verify that a preview whose target was missed for both independent reasons at once names
     * both levers, rather than only reporting one as if they were mutually exclusive.
     */
    @Test
    void targetNotMetNamesBothLeversWhenBothApply() {
        // given a grouping result that is both clamped to the ceiling and has an over-target suite
        GroupingResult result = groupingResult(new long[] {9000L, 9000L}, false, true, true);

        // when the preview is formatted against a missed target
        String preview = DistributedRunPreviewFormatter.formatPreview(result, 6000L, false, "\n");

        // then it reports the target as not met and names both levers
        assertTrue(preview.contains("Target: 6000ms - not met"));
        assertTrue(preview.contains("the configured max group count is limiting the group count"));
        assertTrue(preview.contains("a single suite is longer than the whole target"));
    }

    /**
     * Verify the preview is prefixed with a blank line (two line separators) so it renders as a
     * distinct section after the estimate block it follows in {@code select-tests} output.
     */
    @Test
    void previewIsPrefixedWithBlankLine() {
        // given any grouping result
        GroupingResult result = groupingResult(new long[] {1000L}, true, false, false);

        // when the preview is formatted
        String preview = DistributedRunPreviewFormatter.formatPreview(result, null, false, "\n");

        // then it starts with a blank line before the header
        assertTrue(preview.startsWith("\n\nDistributed run grouping preview (not persisted):"));
    }

    /**
     * Sanity check that the helper fixture itself produces the expected group count, guarding
     * against a fixture bug masquerading as a formatter bug in the tests above.
     */
    @Test
    void fixtureBuildsExpectedGroupCount() {
        // given a three-group fixture
        GroupingResult result = groupingResult(new long[] {1L, 2L, 3L}, true, false, false);

        // when reading its group count
        // then it matches the number of weights supplied
        assertEquals(3, result.getGroupCount());
        assertEquals(Arrays.asList(0, 1, 2),
                Arrays.asList(result.getGroups().get(0).getGroupNumber(),
                        result.getGroups().get(1).getGroupNumber(),
                        result.getGroups().get(2).getGroupNumber()));
    }

    /**
     * Verify that a preview of a seed run - the shape {@link DistributedRunPlanner#balance}
     * produces for a selection with no stored mapping yet - shows exactly one group and names it
     * as a seed run, regardless of the configured target run time.
     */
    @Test
    void seedRunPreviewShowsOneGroupAndNamesSeedRun() {
        // given the single-empty-group result a seed run always produces
        GroupingResult result = groupingResult(new long[] {0L}, true, false, false);

        // when the preview is formatted as a seed run against a configured target
        String preview = DistributedRunPreviewFormatter.formatPreview(result, 6000L, true, "\n");

        // then it names the seed run and reports exactly one group
        assertTrue(preview.contains("Seed run:"),
                "preview should name the seed run explicitly: " + preview);
        assertTrue(preview.contains("Groups: 1"),
                "preview should report exactly one group: " + preview);
    }

    /**
     * Verify that a seed-run preview omits the target verdict entirely, since {@link
     * DistributedRunPlanner#balance} always reports a trivially-met target for a seed run and
     * printing it as if real balancing happened against the configured target would be misleading.
     */
    @Test
    void seedRunPreviewOmitsTargetVerdict() {
        // given the single-empty-group result a seed run always produces
        GroupingResult result = groupingResult(new long[] {0L}, true, false, false);

        // when the preview is formatted as a seed run against a configured target
        String preview = DistributedRunPreviewFormatter.formatPreview(result, 6000L, true, "\n");

        // then no target verdict is printed
        assertFalse(preview.contains("Target:"),
                "seed-run preview should not print a target verdict: " + preview);
    }

    /**
     * Verify that a non-seed preview never mentions "Seed run", so the two cases stay visually
     * distinct in build output.
     */
    @Test
    void nonSeedRunPreviewDoesNotMentionSeedRun() {
        // given an ordinary, non-seed grouping result
        GroupingResult result = groupingResult(new long[] {1000L, 2000L}, true, false, false);

        // when the preview is formatted as a non-seed run
        String preview = DistributedRunPreviewFormatter.formatPreview(result, null, false, "\n");

        // then it does not mention a seed run
        assertFalse(preview.contains("Seed run:"),
                "non-seed preview should not mention a seed run: " + preview);
    }

    /**
     * Verify a fallback seed's preview - the balancer's single empty group, produced when nothing
     * was found on disk to split - names the fallback wording exactly: a single group covering the
     * whole suite, not the split wording a discovered seed gets.
     */
    @Test
    void fallbackSeedPreviewNamesTheFallbackWordingExactly() {
        // given - the single empty group a seed run collapses to when nothing was found on disk
        GroupingResult result = new GroupingResult(Collections.singletonList(
                new SuiteGroup(0, Collections.<String>emptyList(), 0L)), true, false, false, false);

        // when
        String preview = DistributedRunPreviewFormatter.formatPreview(result, null, true, "\n");

        // then
        assertTrue(preview.contains("Seed run: no stored mapping exists yet for this branch, so a "
                        + "real distributed run would plan a single group covering the whole "
                        + "suite and record the mapping for the next build."),
                "fallback seed preview should use the fallback wording: " + preview);
    }

    /**
     * Verify a split seed's preview - suites discovered on disk and divided across the groups -
     * names the split wording exactly, and does not claim a single group covering the whole suite.
     */
    @Test
    void splitSeedPreviewNamesTheSplitWordingExactly() {
        // given - two groups, each carrying real suite names found on disk
        List<SuiteGroup> groups = Arrays.asList(
                new SuiteGroup(0, Collections.singletonList("com.example.ATest"), 0L),
                new SuiteGroup(1, Collections.singletonList("com.example.BTest"), 0L));
        GroupingResult result = new GroupingResult(groups, true, false, false, false);

        // when
        String preview = DistributedRunPreviewFormatter.formatPreview(result, null, true, "\n");

        // then
        assertTrue(preview.contains("Seed run: no stored mapping exists yet for this branch, so a "
                        + "real distributed run would discover its suites on disk and split them "
                        + "across the groups by even count (no run times yet to balance by), and "
                        + "record the mapping for the next build."),
                "split seed preview should use the split wording: " + preview);
        assertFalse(preview.contains("plan a single group covering the whole suite"),
                "split seed preview should not claim a single group covering the whole suite: "
                        + preview);
    }
}
