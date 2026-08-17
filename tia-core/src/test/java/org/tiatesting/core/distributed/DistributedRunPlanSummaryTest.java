package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DistributedRunPlanSummary}'s two renderings. {@link
 * DistributedRunPlanSummary#toJson()} is a published contract a CI pipeline parses to decide how
 * many jobs to start, so its field order, shape and escaping are locked down with exact-string
 * assertions rather than loose substring checks. {@link
 * DistributedRunPlanSummary#toConsoleSummary()} has no machine consumer, so it is checked only
 * for the facts it must mention.
 */
class DistributedRunPlanSummaryTest {

    /**
     * Verifies the full {@code tia-run-plan.json} document for a dynamic-groups plan against an
     * exact expected string, locking down field order and shape as well as content. This is the
     * regression guard for the published contract: a CI pipeline's {@code jq} script depends on
     * these exact field names appearing in this exact order.
     */
    @Test
    void toJson_dynamicGroupsPlan_producesExactDocument() {
        // given - a dynamic-groups plan matching the worked sample in the WIKI chapter
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 5, 1500000L, true, false, false, 6900000L,
                1450000L, 412, false);

        // when
        String json = summary.toJson();

        // then - every field, in the exact order and shape the published contract fixes
        String expected = "{\n"
                + "  \"runId\": \"gh-1284471\",\n"
                + "  \"branch\": \"main\",\n"
                + "  \"commit\": \"87a5110\",\n"
                + "  \"seedRun\": false,\n"
                + "  \"groupCount\": 5,\n"
                + "  \"avgGroupMs\": 1380000,\n"
                + "  \"heaviestGroupMs\": 1450000,\n"
                + "  \"targetMs\": 1500000,\n"
                + "  \"targetMet\": true,\n"
                + "  \"clampedToMaxGroups\": false,\n"
                + "  \"singleSuiteExceedsTarget\": false,\n"
                + "  \"totalEstimatedMs\": 6900000,\n"
                + "  \"selectedSuiteCount\": 412\n"
                + "}";
        assertEquals(expected, json);
    }

    /**
     * Verifies that {@code targetMs} is rendered as JSON {@code null}, not {@code 0}, when the
     * plan is in static groups mode. A rendered {@code 0} would read as an (impossible) target of
     * zero ms rather than the absence of a target, so this distinction matters to any pipeline
     * script that reads the field.
     */
    @Test
    void toJson_staticGroupsMode_rendersTargetMsAsJsonNull() {
        // given - a static-groups plan, which has no target run time
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 4, null, true, false, false, 4000000L,
                1050000L, 300, false);

        // when
        String json = summary.toJson();

        // then - targetMs is the bare JSON literal null, not a quoted string or a zero
        assertTrue(json.contains("\"targetMs\": null,"),
                "targetMs should render as the JSON null literal in static groups mode: " + json);
    }

    /**
     * Verifies that a branch name containing both a double quote and a backslash produces a
     * document where those characters are escaped, and asserts the exact escaped output rather
     * than merely that no exception was thrown. An unescaped quote or backslash would terminate
     * the JSON string early and break the user's {@code jq} parsing of the file.
     */
    @Test
    void toJson_branchWithQuoteAndBackslash_producesEscapedOutput() {
        // given - a branch name with a literal double quote and a literal backslash
        String branchWithSpecialChars = "feature/say-\"hi\"\\ok";
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", branchWithSpecialChars, "87a5110", 5, 1500000L, true, false, false,
                6900000L, 1450000L, 412, false);

        // when
        String json = summary.toJson();

        // then - the quote and backslash are both escaped in the JSON output
        assertTrue(json.contains("\"branch\": \"feature/say-\\\"hi\\\"\\\\ok\","),
                "branch value should be escaped for embedding in a JSON string: " + json);
    }

    /**
     * Verifies that {@code avgGroupMs} is the total estimated time divided by the group count,
     * for a case where the division is inexact, confirming the summary reports the balancer's
     * actual output rather than a rounded or hardcoded figure.
     */
    @Test
    void getAvgGroupMs_dividesTotalByGroupCount() {
        // given - a total that does not divide evenly by the group count
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1", "main", "abc123", 3, 1000L, true, false, false, 1000L, 400L, 10, false);

        // when
        long avgGroupMs = summary.getAvgGroupMs();

        // then - integer division of 1000 / 3
        assertEquals(333L, avgGroupMs, "avgGroupMs should be totalEstimatedMs / groupCount");
    }

    /**
     * Verifies that {@code avgGroupMs} is zero, not a division-by-zero failure, when the group
     * count is zero. An empty selection legitimately produces zero groups, and the summary must
     * still be constructible and reportable in that case.
     */
    @Test
    void getAvgGroupMs_zeroGroupCount_doesNotDivideByZero() {
        // given - a plan with no groups at all (an empty selection)
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1", "main", "abc123", 0, 1500000L, true, false, false, 0L, 0L, 0, false);

        // when
        long avgGroupMs = summary.getAvgGroupMs();

        // then - zero, not an exception
        assertEquals(0L, avgGroupMs, "avgGroupMs should be 0 when groupCount is 0");
    }

    /**
     * Verifies that {@link DistributedRunPlanSummary#toConsoleSummary()} mentions both the group
     * count and that the target was met, since these are the two headline facts a developer
     * reading build output needs at a glance.
     */
    @Test
    void toConsoleSummary_targetMet_mentionsGroupCountAndMetStatus() {
        // given - a plan whose target was met
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 5, 1500000L, true, false, false, 6900000L,
                1450000L, 412, false);

        // when
        String consoleSummary = summary.toConsoleSummary();

        // then - the group count and a positive met status both appear
        assertTrue(consoleSummary.contains("5"),
                "console summary should mention the group count: " + consoleSummary);
        assertTrue(consoleSummary.contains("met") && !consoleSummary.contains("not met"),
                "console summary should say the target was met: " + consoleSummary);
    }

    /**
     * Verifies that {@link DistributedRunPlanSummary#toConsoleSummary()} reports a not-met target
     * distinctly from a met one, so a developer scanning build output cannot mistake one state for
     * the other.
     */
    @Test
    void toConsoleSummary_targetNotMet_mentionsGroupCountAndNotMetStatus() {
        // given - a plan whose target was not met
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 8, 1500000L, false, true, false, 16000000L,
                2200000L, 900, false);

        // when
        String consoleSummary = summary.toConsoleSummary();

        // then - the group count and a "not met" status both appear
        assertTrue(consoleSummary.contains("8"),
                "console summary should mention the group count: " + consoleSummary);
        assertTrue(consoleSummary.contains("not met"),
                "console summary should say the target was not met: " + consoleSummary);
    }

    /**
     * Verifies that {@code heaviestGroupMs} round-trips through the constructor to its getter
     * unchanged - it is a value the balancer already computed, not derived here the way {@code
     * avgGroupMs} is.
     */
    @Test
    void getHeaviestGroupMs_returnsConstructorValue() {
        // given - a plan whose heaviest group is well above the average
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 5, 1500000L, true, false, false, 6900000L,
                1450000L, 412, false);

        // when
        long heaviestGroupMs = summary.getHeaviestGroupMs();

        // then
        assertEquals(1450000L, heaviestGroupMs);
    }

    /**
     * Verifies that {@code heaviestGroupMs} appears in the published JSON document as its own
     * field distinct from {@code avgGroupMs}, so a pipeline can read it to set a job timeout - the
     * motivating case this field exists for, since {@code avgGroupMs} understates the wall clock
     * exactly when the packing is uneven.
     */
    @Test
    void toJson_includesHeaviestGroupMsDistinctFromAvgGroupMs() {
        // given - a plan whose heaviest group is heavier than the average group
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 5, 1500000L, true, false, false, 6900000L,
                1450000L, 412, false);

        // when
        String json = summary.toJson();

        // then
        assertTrue(json.contains("\"heaviestGroupMs\": 1450000,"),
                "JSON should contain the heaviestGroupMs field: " + json);
        assertTrue(json.contains("\"avgGroupMs\": 1380000,"),
                "JSON should still contain the distinct avgGroupMs field: " + json);
    }

    /**
     * Verifies that the console summary block names the heaviest group time, not just the
     * average, so a developer reading build output sees the wall-clock figure a job timeout
     * should be set from.
     */
    @Test
    void toConsoleSummary_mentionsHeaviestGroupTime() {
        // given - a plan whose heaviest group is heavier than the average group
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 5, 1500000L, true, false, false, 6900000L,
                1450000L, 412, false);

        // when
        String consoleSummary = summary.toConsoleSummary();

        // then
        assertTrue(consoleSummary.contains("1450000"),
                "console summary should mention the heaviest group time: " + consoleSummary);
    }

    /**
     * Verifies that the console summary's max-groups miss reason names the exact same
     * wording {@link DistributedRunPreviewFormatter#formatPreview} uses for its "lever:" line for
     * the identical grouping outcome, so a developer who saw a miss explained in the {@code
     * select-tests} preview sees the identical explanation once the plan step persists the
     * real plan - the "prints the same" requirement, extended to the miss reasons.
     */
    @Test
    void toConsoleSummary_maxGroupsMissReason_matchesPreviewFormatterWording() {
        // given - a plan summary and a preview grouping result that both report the same miss
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1", "main", "abc123", 2, 6000L, false, true, false, 18000L, 9000L, 2, false);
        GroupingResult preview = new GroupingResult(Collections.singletonList(
                new SuiteGroup(0, Collections.singletonList("Suite0"), 9000L)), false, true, false);

        // when
        String consoleSummary = summary.toConsoleSummary();
        String previewText = DistributedRunPreviewFormatter.formatPreview(preview, 6000L, false, "\n");

        // then - the console summary's "reason:" line and the preview's "lever:" line name the
        // identical explanatory text, just prefixed differently
        assertTrue(consoleSummary.contains("reason: " + DistributedRunMissReasons.MAX_GROUPS_LIMITING),
                "console summary should use the shared max-groups wording: " + consoleSummary);
        assertTrue(previewText.contains("lever: " + DistributedRunMissReasons.MAX_GROUPS_LIMITING),
                "preview should use the shared max-groups wording: " + previewText);
    }

    /**
     * Verifies the same for the second miss reason: the console summary's single-suite miss
     * reason names the exact same wording the preview formatter uses.
     */
    @Test
    void toConsoleSummary_singleSuiteMissReason_matchesPreviewFormatterWording() {
        // given - a plan summary and a preview grouping result that both report the same miss
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1", "main", "abc123", 1, 6000L, false, false, true, 9000L, 9000L, 1, false);
        GroupingResult preview = new GroupingResult(Collections.singletonList(
                new SuiteGroup(0, Collections.singletonList("Suite0"), 9000L)), false, false, true);

        // when
        String consoleSummary = summary.toConsoleSummary();
        String previewText = DistributedRunPreviewFormatter.formatPreview(preview, 6000L, false, "\n");

        // then
        assertTrue(consoleSummary.contains("reason: " + DistributedRunMissReasons.SINGLE_SUITE_EXCEEDS_TARGET),
                "console summary should use the shared single-suite wording: " + consoleSummary);
        assertTrue(previewText.contains("lever: " + DistributedRunMissReasons.SINGLE_SUITE_EXCEEDS_TARGET),
                "preview should use the shared single-suite wording: " + previewText);
    }

    /**
     * Verifies that {@link DistributedRunPlanSummary#isSeedRun()} round-trips through the
     * constructor to its getter unchanged, for a plan that is not a seed run.
     */
    @Test
    void isSeedRun_notASeedRun_returnsFalse() {
        // given - an ordinary, non-seed plan
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 5, 1500000L, true, false, false, 6900000L,
                1450000L, 412, false);

        // when
        boolean seedRun = summary.isSeedRun();

        // then
        assertFalse(seedRun, "an ordinary plan should not report itself as a seed run");
    }

    /**
     * Verifies that {@link DistributedRunPlanSummary#isSeedRun()} reports true for a seed run's
     * summary, the shape a plan collapses to when no stored mapping exists yet for the branch.
     */
    @Test
    void isSeedRun_seedRun_returnsTrue() {
        // given - a seed run's summary: one group, no suites, zero estimated time
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 1, null, true, false, false, 0L, 0L, 0, true);

        // when
        boolean seedRun = summary.isSeedRun();

        // then
        assertTrue(seedRun, "a seed run's summary should report itself as a seed run");
    }

    /**
     * Verifies that {@code seedRun} appears in the published JSON document as its own field, in
     * the position fixed relative to {@code commit} and {@code groupCount}, so a pipeline can
     * explain why it only received one job despite a configured group count.
     */
    @Test
    void toJson_seedRun_includesSeedRunTrueField() {
        // given - a seed run's summary
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 1, null, true, false, false, 0L, 0L, 0, true);

        // when
        String json = summary.toJson();

        // then
        assertTrue(json.contains("\"seedRun\": true,"),
                "JSON should render seedRun as true for a seed run: " + json);
    }

    /**
     * Verifies that {@link DistributedRunPlanSummary#toConsoleSummary()} names the seed run
     * explicitly and does not print a target verdict, since a seed run's trivially-met target
     * would otherwise read as if real balancing against the configured target had happened.
     */
    @Test
    void toConsoleSummary_seedRun_namesSeedRunAndOmitsTargetVerdict() {
        // given - a seed run's summary, as if planned against a dynamic-groups config
        DistributedRunPlanSummary summary = new DistributedRunPlanSummary(
                "gh-1284471", "main", "87a5110", 1, 1500000L, true, false, false, 0L, 0L, 0, true);

        // when
        String consoleSummary = summary.toConsoleSummary();

        // then
        assertTrue(consoleSummary.contains("Seed run:"),
                "console summary should name the seed run explicitly: " + consoleSummary);
        assertTrue(consoleSummary.contains("Groups: 1"),
                "console summary should still report the single group: " + consoleSummary);
        assertFalse(consoleSummary.contains("Target:"),
                "console summary should not print a target verdict for a seed run: " + consoleSummary);
    }
}
