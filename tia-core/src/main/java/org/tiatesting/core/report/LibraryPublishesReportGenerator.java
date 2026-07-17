package org.tiatesting.core.report;

import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.persistence.DataStore;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generate the publish-ledger listing for a single tracked library - one table row per published
 * build (sequence, version, jar hash, commit, publish time) plus the count of impacted methods
 * still pending for that build. Backs the {@code library-publishes} task/mojo, which takes the
 * {@code group:artifact} coordinate as input.
 */
public class LibraryPublishesReportGenerator {

    /**
     * Build the formatted publish-ledger report for one library.
     *
     * @param dataStore the Tia data store to read the tracked library, ledger and pending stamps from
     * @param groupArtifact the {@code groupId:artifactId} of the library to report on
     * @return the formatted report text, starting with a leading line separator for clean
     *         display after build-tool log prefixes
     */
    public String generateLibraryPublishesReport(DataStore dataStore, String groupArtifact) {
        String lineSep = System.lineSeparator();
        if (groupArtifact == null || groupArtifact.trim().isEmpty()) {
            return lineSep + "A library must be specified as groupId:artifactId.";
        }
        String coordinate = groupArtifact.trim();
        if (!dataStore.readTrackedLibraries().containsKey(coordinate)) {
            return lineSep + "Library '" + coordinate + "' is not tracked.";
        }

        List<LibraryPublish> ledger = dataStore.readLibraryPublishes(coordinate);
        StringBuilder sb = new StringBuilder(lineSep);
        sb.append("Publishes for library ").append(coordinate).append(":").append(lineSep);
        if (ledger.isEmpty()) {
            sb.append("none");
            return sb.toString();
        }

        Map<Long, Integer> pendingCountBySeq = countPendingMethodsBySeq(
                dataStore.readPendingLibraryImpactedMethods(coordinate));

        TextTable table = new TextTable("Seq", "Version", "Jar hash", "Commit", "Published at", "Methods pending");
        for (LibraryPublish publish : ledger) {
            Integer pendingCount = pendingCountBySeq.get(publish.getPublishSeq());
            table.addRow(
                    String.valueOf(publish.getPublishSeq()),
                    publish.getPublishedVersion(),
                    truncate(publish.getJarHash()),
                    truncate(publish.getCommitValue()),
                    Instant.ofEpochMilli(publish.getPublishedAt()).toString(),
                    pendingCount != null ? String.valueOf(pendingCount) : "-");
        }
        sb.append(table.render(lineSep));
        return sb.toString();
    }

    /**
     * Count each pending batch's methods by its publish sequence.
     *
     * @param pending the library's pending batches
     * @return map of publish sequence to pending method count
     */
    private Map<Long, Integer> countPendingMethodsBySeq(List<PendingLibraryImpactedMethod> pending) {
        Map<Long, Integer> counts = new HashMap<>();
        for (PendingLibraryImpactedMethod batch : pending) {
            counts.put(batch.getPublishSeq(),
                    batch.getSourceMethodIds() != null ? batch.getSourceMethodIds().size() : 0);
        }
        return counts;
    }

    /**
     * Truncate a hash/commit value to 12 characters for table display.
     *
     * @param value the value to truncate
     * @return the truncated value with an ellipsis, or null when the input is null
     */
    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 12 ? value.substring(0, 12) + "..." : value;
    }
}
