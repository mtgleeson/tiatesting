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
     * Build a well-formed two-group plan for use as a starting point in each test.
     *
     * @return a plan with groups 0 and 1, each holding one suite
     */
    private static DistributedRunPlan validPlan() {
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 2, 1000L, 300L, 5L);
        List<DistributedRunGroup> groups = Arrays.asList(
                DistributedRunGroup.pending("run-1", 0, 200L),
                DistributedRunGroup.pending("run-1", 1, 100L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));
        suites.put(1, Arrays.asList("com.example.BTest"));
        return new DistributedRunPlan(run, groups, suites);
    }

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

    @Test
    void shouldRejectGroupCountThatDisagreesWithGroupList() {
        // given
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 3, null, 300L, 5L);
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

    @Test
    void shouldRejectSuiteMapWithNoEntryForAGroup() {
        // given
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 2, null, 300L, 5L);
        List<DistributedRunGroup> groups = Arrays.asList(
                DistributedRunGroup.pending("run-1", 0, 200L),
                DistributedRunGroup.pending("run-1", 1, 100L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DistributedRunPlan(run, groups, suites));

        // then
        assertTrue(thrown.getMessage().contains("1"), thrown.getMessage());
    }

    @Test
    void shouldRejectSuiteAssignedToMoreThanOneGroup() {
        // given
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 2, null, 300L, 5L);
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

    @Test
    void shouldReturnUnmodifiableCollections() {
        // given
        DistributedRunPlan plan = validPlan();

        // when
        List<DistributedRunGroup> groups = plan.getGroups();

        // then
        assertThrows(UnsupportedOperationException.class,
                () -> groups.add(DistributedRunGroup.pending("run-1", 9, 1L)));
    }

    @Test
    void shouldAllowAnEmptyGroupWhenSelectionIsSmallerThanTheGroupCount() {
        // given
        DistributedRun run = DistributedRun.open("run-1", "main", "abc123", 2, null, 300L, 5L);
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
