package org.tiatesting.core.model;

import org.tiatesting.core.library.LibraryImpactDrainResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A complete distributed run plan: the run itself, its groups, and the suite names assigned to
 * each group. Exists so the whole plan is written in one call and therefore one transaction - a
 * runner must never observe a run row whose groups are still being inserted.
 *
 * <p>Consistency is validated at construction, because an inconsistent plan is a planner defect
 * and is far cheaper to diagnose there than as a runner claiming a group with no suites.
 *
 * <p>Unlike {@link DistributedRun} and {@link DistributedRunGroup}, this class does not implement
 * {@code Serializable}. It is a transient write bundle assembled in memory for the duration of a
 * single {@code persistDistributedRunPlan} call and is never itself persisted, cached, or sent
 * across a process boundary as an object - only the value objects it carries are.
 */
public final class DistributedRunPlan {

    private final DistributedRun run;
    private final List<DistributedRunGroup> groups;
    private final Map<Integer, List<String>> suitesByGroup;
    private final LibraryImpactDrainResult drainResult;

    /**
     * Bundle a run with its groups and suite assignment, validating that the three agree.
     *
     * @param run the run being planned; its group count must match the group list size
     * @param groups one entry per group, numbered 0 to groupCount-1
     * @param suitesByGroup suite names keyed by group number; every group must have an entry,
     *                      possibly empty, and no suite may appear under two groups; every key
     *                      must correspond to a declared group
     * @param drainResult the library-impact drain the plan's own test selection already performed,
     *                    stored so the sealer can apply its cleanup; null if nothing was drained
     * @throws IllegalArgumentException if the group count, group list and suite map disagree, if
     *         any declared group has no suite entry, if any suite is assigned to more than one
     *         group, or if {@code suitesByGroup} has an entry for a group that was not declared
     */
    public DistributedRunPlan(DistributedRun run, List<DistributedRunGroup> groups,
                              Map<Integer, List<String>> suitesByGroup,
                              LibraryImpactDrainResult drainResult) {
        if (run.getGroupCount() != groups.size()) {
            throw new IllegalArgumentException("run groupCount " + run.getGroupCount()
                    + " does not match the number of groups supplied (" + groups.size() + ")");
        }
        Set<String> seenSuites = new HashSet<>();
        Map<Integer, List<String>> copiedSuites = new HashMap<>();
        for (DistributedRunGroup group : groups) {
            List<String> suites = suitesByGroup.get(group.getGroupNumber());
            if (suites == null) {
                throw new IllegalArgumentException("no suite assignment for group "
                        + group.getGroupNumber() + " of run " + run.getRunId());
            }
            for (String suite : suites) {
                if (!seenSuites.add(suite)) {
                    throw new IllegalArgumentException("suite " + suite
                            + " is assigned to more than one group of run " + run.getRunId());
                }
            }
            copiedSuites.put(group.getGroupNumber(),
                    Collections.unmodifiableList(new ArrayList<>(suites)));
        }
        for (Integer suppliedGroup : suitesByGroup.keySet()) {
            if (!copiedSuites.containsKey(suppliedGroup)) {
                throw new IllegalArgumentException("suitesByGroup has an entry for group "
                        + suppliedGroup + " of run " + run.getRunId()
                        + " which is not one of the declared groups");
            }
        }
        this.run = run;
        this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
        this.suitesByGroup = Collections.unmodifiableMap(copiedSuites);
        this.drainResult = drainResult;
    }

    /**
     * The library-impact drain the plan's own test selection performed before the plan was written.
     * That drain deleted pending stamp rows and advanced publish sequences, and it cannot be run a
     * second time - running it per-runner would race - so this is the only record of the cleanup
     * the run still owes. See the drain-rule section of the library publish-time stamping chapter
     * in {@code WIKI.md}.
     *
     * <p>It lives on the write bundle rather than on {@link DistributedRun} deliberately. Only the
     * sealer consumes it, whereas every runner in the build reads the run row to claim a group, and
     * the drain result is a Java-serialized blob. Carrying it on the run row would make every one of
     * those claims deserialize it, so a blob the runners never look at - one written by a planner on
     * a different Tia version, say - would fail the whole build at claim time instead of only the
     * cleanup that actually needs it. Readers that want it ask
     * {@code DataStore.readDistributedRunDrainResult} for it by name.
     *
     * @return the drain the plan performed, or null if it drained nothing
     */
    public LibraryImpactDrainResult getDrainResult() { return drainResult; }

    /** @return the run this plan describes */
    public DistributedRun getRun() { return run; }

    /** @return the run's groups, unmodifiable */
    public List<DistributedRunGroup> getGroups() { return groups; }

    /** @return suite names keyed by group number, unmodifiable */
    public Map<Integer, List<String>> getSuitesByGroup() { return suitesByGroup; }
}
