package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the consistency checks on {@link DistributedRunPlan}. The bundle is what makes a plan
 * write a single transaction, so an internally inconsistent bundle must fail at construction
 * rather than reach the database.
 */
class DistributedRunPlanTest {

    /**
     * Build a well-formed two-group plan for use as a starting point in each test. The inputs
     * are built from genuinely mutable collections (never {@code Arrays.asList}) so that any
     * unmodifiability assertion made against the plan's getters is exercising the plan's own
     * copying/wrapping behaviour rather than a fixed-size list the fixture happened to hand in.
     *
     * @return a plan with groups 0 and 1, each holding one suite
     */
    private static DistributedRunPlan validPlan() {
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 2, 1000L, 300L, 5L, null);
        List<DistributedRunGroup> groups = new ArrayList<>();
        groups.add(DistributedRunGroup.pending("run-1", 0, 200L));
        groups.add(DistributedRunGroup.pending("run-1", 1, 100L));
        Map<Integer, List<String>> suites = new HashMap<>();
        List<String> groupZeroSuites = new ArrayList<>();
        groupZeroSuites.add("com.example.ATest");
        suites.put(0, groupZeroSuites);
        List<String> groupOneSuites = new ArrayList<>();
        groupOneSuites.add("com.example.BTest");
        suites.put(1, groupOneSuites);
        return new DistributedRunPlan(run, groups, suites);
    }

    /**
     * Verifies that a valid plan exposes the run it was built from, the correct number of
     * groups, and the suites assigned to each group, since these getters are how every
     * downstream consumer (planner, runner, persistence) reads the plan back out.
     */
    @Test
    void shouldExposeRunGroupsAndSuites() {
        // given
        DistributedRunPlan plan = validPlan();

        // when
        Map<Integer, List<String>> suitesByGroup = plan.getSuitesByGroup();

        // then
        assertEquals("run-1", plan.getRun().getRunId());
        assertEquals(2, plan.getGroups().size());
        assertEquals(Arrays.asList("com.example.ATest"), suitesByGroup.get(0));
    }

    /**
     * Verifies construction fails when the run's declared group count disagrees with the
     * number of groups actually supplied, since a mismatch here means the plan and the run
     * row it will be persisted alongside cannot both be correct.
     */
    @Test
    void shouldRejectGroupCountThatDisagreesWithGroupList() {
        // given
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 3, null, 300L, 5L, null);
        List<DistributedRunGroup> groups = Arrays.asList(
                DistributedRunGroup.pending("run-1", 0, 200L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DistributedRunPlan(run, groups, suites));

        // then
        assertTrue(thrown.getMessage().contains("groupCount"), thrown.getMessage());
    }

    /**
     * Verifies construction fails, and names the offending group, when the suite map has no
     * entry for one of the declared groups. This is a distinct failure mode from a bad group
     * count: without it a runner could claim a group and find no suite assignment at all.
     */
    @Test
    void shouldRejectSuiteMapWithNoEntryForAGroup() {
        // given
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 2, null, 300L, 5L, null);
        List<DistributedRunGroup> groups = Arrays.asList(
                DistributedRunGroup.pending("run-1", 0, 200L),
                DistributedRunGroup.pending("run-1", 1, 100L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DistributedRunPlan(run, groups, suites));

        // then
        assertTrue(thrown.getMessage().contains("no suite assignment for group 1"),
                thrown.getMessage());
    }

    /**
     * Verifies construction fails, and names the offending suite, when the same suite name
     * appears under two different groups, since a doubly-assigned suite would run twice or
     * race across two runners.
     */
    @Test
    void shouldRejectSuiteAssignedToMoreThanOneGroup() {
        // given
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 2, null, 300L, 5L, null);
        List<DistributedRunGroup> groups = Arrays.asList(
                DistributedRunGroup.pending("run-1", 0, 200L),
                DistributedRunGroup.pending("run-1", 1, 100L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));
        suites.put(1, Arrays.asList("com.example.ATest"));

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DistributedRunPlan(run, groups, suites));

        // then
        assertTrue(thrown.getMessage().contains("com.example.ATest"), thrown.getMessage());
    }

    /**
     * Verifies the group list returned by {@link DistributedRunPlan#getGroups()} rejects
     * mutation. The fixture builds its groups from a mutable {@code ArrayList}, so this only
     * passes if the plan itself wraps or copies into an unmodifiable list rather than handing
     * back the caller's own reference.
     */
    @Test
    void shouldReturnUnmodifiableGroupsList() {
        // given
        DistributedRunPlan plan = validPlan();

        // when
        List<DistributedRunGroup> groups = plan.getGroups();

        // then
        assertThrows(UnsupportedOperationException.class,
                () -> groups.add(DistributedRunGroup.pending("run-1", 9, 1L)));
    }

    /**
     * Verifies the map returned by {@link DistributedRunPlan#getSuitesByGroup()} rejects
     * mutation at the top level. The fixture builds the map from a mutable {@code HashMap}, so
     * this only passes if the plan wraps or copies into an unmodifiable map.
     */
    @Test
    void shouldReturnUnmodifiableSuitesByGroupMap() {
        // given
        DistributedRunPlan plan = validPlan();

        // when
        Map<Integer, List<String>> suitesByGroup = plan.getSuitesByGroup();

        // then
        assertThrows(UnsupportedOperationException.class,
                () -> suitesByGroup.put(9, new ArrayList<>()));
    }

    /**
     * Verifies the per-group suite list nested inside {@link DistributedRunPlan#getSuitesByGroup()}
     * also rejects mutation, not just the outer map. A caller reaching into one group's suite
     * list must not be able to corrupt the plan any more than one reaching the outer map can.
     */
    @Test
    void shouldReturnUnmodifiableSuiteListWithinSuitesByGroupMap() {
        // given
        DistributedRunPlan plan = validPlan();

        // when
        List<String> groupZeroSuites = plan.getSuitesByGroup().get(0);

        // then
        assertThrows(UnsupportedOperationException.class,
                () -> groupZeroSuites.add("com.example.ZTest"));
    }

    /**
     * Verifies the constructor defensively copies its inputs: mutating the caller's original
     * group list and suite map (including a per-group suite list) after construction must not
     * change the plan. This is distinct from the getters being unmodifiable - it proves the
     * plan does not merely wrap the caller's live collections, which would let the caller
     * corrupt an already-constructed plan through references it still holds.
     */
    @Test
    void shouldNotBeAffectedByMutatingTheOriginalInputsAfterConstruction() {
        // given
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 2, 1000L, 300L, 5L, null);
        List<DistributedRunGroup> groups = new ArrayList<>();
        groups.add(DistributedRunGroup.pending("run-1", 0, 200L));
        groups.add(DistributedRunGroup.pending("run-1", 1, 100L));
        List<String> groupZeroSuites = new ArrayList<>();
        groupZeroSuites.add("com.example.ATest");
        List<String> groupOneSuites = new ArrayList<>();
        groupOneSuites.add("com.example.BTest");
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, groupZeroSuites);
        suites.put(1, groupOneSuites);
        DistributedRunPlan plan = new DistributedRunPlan(run, groups, suites);

        // when
        groups.add(DistributedRunGroup.pending("run-1", 9, 1L));
        suites.put(9, Arrays.asList("com.example.ZTest"));
        groupZeroSuites.add("com.example.MutatedTest");

        // then
        assertEquals(2, plan.getGroups().size());
        assertEquals(2, plan.getSuitesByGroup().size());
        assertEquals(Arrays.asList("com.example.ATest"), plan.getSuitesByGroup().get(0));
    }

    /**
     * Verifies construction fails, and names the offending group, when the suite map has an
     * entry for a group number that was not declared in the group list. Without this check the
     * constructor silently drops that entry - since {@code copiedSuites} is only ever populated
     * by iterating the declared groups, an undeclared key in {@code suitesByGroup} is never read
     * and its suites vanish rather than being persisted or rejected, which would leave a real
     * suite unclaimed by any runner.
     */
    @Test
    void shouldRejectSuiteMapEntryForAnUndeclaredGroup() {
        // given
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 2, null, 300L, 5L, null);
        List<DistributedRunGroup> groups = Arrays.asList(
                DistributedRunGroup.pending("run-1", 0, 200L),
                DistributedRunGroup.pending("run-1", 1, 100L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));
        suites.put(1, Arrays.asList("com.example.BTest"));
        suites.put(2, Arrays.asList("com.example.CTest"));

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DistributedRunPlan(run, groups, suites));

        // then
        assertTrue(thrown.getMessage().contains("group 2"), thrown.getMessage());
    }

    /**
     * Verifies a group is allowed to have zero suites, since the number of runnable groups can
     * legitimately be smaller than the configured group count when the selected test set is
     * small - such a group must still round-trip as present-but-empty rather than being
     * rejected as missing.
     */
    @Test
    void shouldAllowAnEmptyGroupWhenSelectionIsSmallerThanTheGroupCount() {
        // given
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 2, null, 300L, 5L, null);
        List<DistributedRunGroup> groups = Arrays.asList(
                DistributedRunGroup.pending("run-1", 0, 200L),
                DistributedRunGroup.pending("run-1", 1, 0L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));
        suites.put(1, new ArrayList<String>());

        // when
        DistributedRunPlan plan = new DistributedRunPlan(run, groups, suites);

        // then
        assertTrue(plan.getSuitesByGroup().get(1).isEmpty());
    }
}
