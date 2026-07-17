package org.tiatesting.core.report;

import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.persistence.DataStore;

import java.util.*;

/**
 * Generate the pending impacted-methods listing for a single tracked library - one table row per
 * pending method, with the publish it shipped in (sequence + version) and the method's tracked
 * name and line range. Backs the {@code library-pending-methods} task/mojo, which takes the
 * {@code group:artifact} coordinate as input.
 */
public class LibraryPendingMethodsReportGenerator {

    /**
     * Build the formatted pending-methods report for one library.
     *
     * @param dataStore the Tia data store to read the tracked library, pending stamps and
     *                  tracked method details from
     * @param groupArtifact the {@code groupId:artifactId} of the library to report on
     * @return the formatted report text, starting with a leading line separator for clean
     *         display after build-tool log prefixes
     */
    public String generateLibraryPendingMethodsReport(DataStore dataStore, String groupArtifact) {
        String lineSep = System.lineSeparator();
        if (groupArtifact == null || groupArtifact.trim().isEmpty()) {
            return lineSep + "A library must be specified as groupId:artifactId.";
        }
        String coordinate = groupArtifact.trim();
        if (!dataStore.readTrackedLibraries().containsKey(coordinate)) {
            return lineSep + "Library '" + coordinate + "' is not tracked.";
        }

        List<PendingLibraryImpactedMethod> pending =
                new ArrayList<>(dataStore.readPendingLibraryImpactedMethods(coordinate));
        StringBuilder sb = new StringBuilder(lineSep);
        sb.append("Pending impacted methods for library ").append(coordinate).append(":").append(lineSep);
        if (pending.isEmpty()) {
            sb.append("none");
            return sb.toString();
        }

        pending.sort(Comparator.comparingLong(PendingLibraryImpactedMethod::getPublishSeq));
        Map<Integer, MethodImpactTracker> methodsById =
                dataStore.getMethodsTrackedForIds(collectMethodIds(pending));

        TextTable table = new TextTable("Seq", "Version", "Method id", "Method", "Lines");
        for (PendingLibraryImpactedMethod batch : pending) {
            List<Integer> methodIds = new ArrayList<>(batch.getSourceMethodIds());
            Collections.sort(methodIds);
            for (Integer methodId : methodIds) {
                MethodImpactTracker method = methodsById.get(methodId);
                table.addRow(
                        String.valueOf(batch.getPublishSeq()),
                        batch.getStampVersion(),
                        String.valueOf(methodId),
                        method != null ? method.getMethodName() : null,
                        method != null ? method.getLineNumberStart() + "-" + method.getLineNumberEnd() : null);
            }
        }
        sb.append(table.render(lineSep));
        return sb.toString();
    }

    /**
     * Collect the union of method ids across the library's pending batches for the targeted
     * method-details read.
     *
     * @param pending the library's pending batches
     * @return the distinct pending method ids
     */
    private Set<Integer> collectMethodIds(List<PendingLibraryImpactedMethod> pending) {
        Set<Integer> ids = new HashSet<>();
        for (PendingLibraryImpactedMethod batch : pending) {
            if (batch.getSourceMethodIds() != null) {
                ids.addAll(batch.getSourceMethodIds());
            }
        }
        return ids;
    }
}
