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
        return new GroupingResult(groups, targetMet, clampedToMaxGroups, singleSuiteExceedsTarget);
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
        String preview = DistributedRunPreviewFormatter.formatPreview(result, null, "\n");

        // then it reports the groups and weights and states no target applies
        assertTrue(preview.contains("Groups: 2, average 1500ms per group, heaviest 2000ms"));
        assertTrue(preview.contains("Target: none (static group count)"));
        assertFalse(preview.contains("not met"));
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
        String preview = DistributedRunPreviewFormatter.formatPreview(result, 6000L, "\n");

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
        String preview = DistributedRunPreviewFormatter.formatPreview(result, 6000L, "\n");

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
        String preview = DistributedRunPreviewFormatter.formatPreview(result, 6000L, "\n");

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
        String preview = DistributedRunPreviewFormatter.formatPreview(result, 6000L, "\n");

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
        String preview = DistributedRunPreviewFormatter.formatPreview(result, null, "\n");

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
}
