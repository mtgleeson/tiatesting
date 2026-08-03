package org.tiatesting.core.report;

import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.persistence.DataStore;

import java.util.*;

/**
 * Generate the pending impacted-methods listing for a single tracked library - one table row per
 * pending method, with the publish it shipped in (sequence + version) and the method's tracked
 * name and line range - followed by a section listing the library's pending forced-selection
 * batches (produced when one of the library's own static test selection rules matched a changed
 * file at publish time). Backs the {@code library-pending-methods} task/mojo, which takes the
 * {@code group:artifact} coordinate as input. Drain-time application of forced selections stays
 * log-only; this report is pending-state visibility only. See the library publish-time stamping
 * chapter in {@code WIKI.md}.
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
        List<PendingLibraryForcedSelection> pendingForced =
                new ArrayList<>(dataStore.readPendingLibraryForcedSelections(coordinate));

        StringBuilder sb = new StringBuilder(lineSep);
        sb.append("Pending impacted methods for library ").append(coordinate).append(":").append(lineSep);
        if (pending.isEmpty() && pendingForced.isEmpty()) {
            sb.append("none");
            return sb.toString();
        }

        if (pending.isEmpty()) {
            sb.append("none");
        } else {
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
        }

        if (!pendingForced.isEmpty()) {
            sb.append(lineSep).append(lineSep);
            sb.append(renderForcedSelectionsSection(pendingForced, coordinate, lineSep));
        }

        return sb.toString();
    }

    /**
     * Render the "Pending forced selections" section for one library: a heading followed by one
     * table row per pending forced-selection batch, carrying the batch's publish sequence, stamp
     * version, matching rule name, selection mode and suite-name patterns. These batches are
     * produced at publish time when one of the library's own static test selection rules matches
     * a file changed since the library's previous publish; drain-time application of them stays
     * log-only, so this rendering is the only place they are surfaced. See the library
     * publish-time stamping chapter in {@code WIKI.md}.
     *
     * @param pendingForced the library's pending forced-selection batches; must not be empty
     * @param coordinate the {@code groupId:artifactId} of the library being reported on
     * @param lineSep the line separator to use
     * @return the formatted forced-selections section, including its heading
     */
    private String renderForcedSelectionsSection(List<PendingLibraryForcedSelection> pendingForced,
                                                   String coordinate, String lineSep) {
        pendingForced.sort(Comparator.comparingLong(PendingLibraryForcedSelection::getPublishSeq));

        StringBuilder sb = new StringBuilder();
        sb.append("Pending forced selections for library ").append(coordinate).append(":").append(lineSep);

        TextTable table = new TextTable("Seq", "Version", "Rule", "Mode", "Patterns");
        for (PendingLibraryForcedSelection batch : pendingForced) {
            List<String> patterns = batch.getSuiteNamePatterns();
            table.addRow(
                    String.valueOf(batch.getPublishSeq()),
                    batch.getStampVersion(),
                    batch.getRuleName(),
                    batch.getMode() != null ? batch.getMode().name() : null,
                    patterns == null || patterns.isEmpty() ? "-" : String.join(", ", patterns));
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
