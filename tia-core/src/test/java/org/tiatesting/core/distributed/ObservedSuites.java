package org.tiatesting.core.distributed;

import org.tiatesting.core.persistence.DataStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Test helper for progress reports: a report now names the suites a runner observed rather than
 * giving a count, so tests that only care how many of a group's suites were observed name the
 * first {@code count} of that group's assigned suites, read back from the plan.
 */
public final class ObservedSuites {

    private ObservedSuites() {
    }

    /**
     * Name the first {@code count} suites the plan assigned to a group, in name order.
     *
     * @param dataStore the store the plan was persisted to
     * @param runId the distributed run
     * @param groupNumber the group whose suites to name
     * @param count how many suites to name; capped at the group's assigned count
     * @return the first {@code count} assigned suite names
     */
    public static Set<String> firstAssigned(final DataStore dataStore, final String runId,
                                            final int groupNumber, final int count) {
        List<String> assigned = dataStore.readDistributedRunGroupSuites(runId, groupNumber);
        return new LinkedHashSet<>(assigned.subList(0, Math.min(count, assigned.size())));
    }
}
