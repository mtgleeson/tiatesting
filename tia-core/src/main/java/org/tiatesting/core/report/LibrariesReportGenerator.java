package org.tiatesting.core.report;

import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generate the tracked-libraries listing for the {@code libraries} task / mojo and output it
 * to the user. Covers the same details as the {@code tia-libraries.html} report page - per
 * library: coordinates, project dir, source dirs, last source-project version, last released
 * version (HWM, when tracked) and its pending batches - plus the per-batch detail (stamp
 * version, pending method count, unknown-next-version flag, jar hash) that the HTML page
 * summarises as a count.
 *
 * <p>This listing was previously part of the status report; it now has its own task so the
 * status output stays a quick mapping/stats snapshot.
 */
public class LibrariesReportGenerator {

    /**
     * Build the formatted tracked-libraries report from the data store: every tracked library
     * with its recorded state, each followed by its pending impacted-method batches.
     *
     * @param dataStore the Tia data store to read tracked libraries and pending batches from
     * @return the formatted report text, starting with a leading line separator for clean
     *         display after build-tool log prefixes
     */
    public String generateLibrariesReport(DataStore dataStore) {
        String lineSep = System.lineSeparator();
        Map<String, TrackedLibrary> tracked = dataStore.readTrackedLibraries();
        Map<String, List<PendingLibraryImpactedMethod>> pendingByLib =
                groupPendingByLibrary(dataStore.readAllPendingLibraryImpactedMethods());

        StringBuilder sb = new StringBuilder(lineSep);
        sb.append("Tracked libraries:").append(lineSep);

        if (tracked == null || tracked.isEmpty()) {
            sb.append("\tnone");
            return sb.toString();
        }

        for (TrackedLibrary lib : tracked.values()) {
            sb.append("\t").append(lib.getGroupArtifact()).append(lineSep);
            sb.append("\t\tProject dir: ").append(orDash(lib.getProjectDir())).append(lineSep);
            sb.append("\t\tSource dirs: ").append(orDash(lib.getSourceDirsCsv())).append(lineSep);
            sb.append("\t\tLast applied publish seq: ")
                    .append(lib.getLastAppliedSeq() != null ? lib.getLastAppliedSeq() : "-").append(lineSep);
            sb.append("\t\tMapping baseline commit: ").append(orDash(lib.getMappingBaselineCommit())).append(lineSep);
            sb.append(formatPendingBatches(pendingByLib.get(lib.getGroupArtifact()), lineSep));
        }

        // Trim the trailing line separator so callers control spacing.
        if (sb.length() >= lineSep.length()
                && sb.substring(sb.length() - lineSep.length()).equals(lineSep)) {
            sb.setLength(sb.length() - lineSep.length());
        }
        return sb.toString();
    }

    /**
     * Format one library's pending impacted-method batches: a count line, then one line per
     * batch with the publish sequence, the version the batch's publish shipped under, and the
     * pending method count.
     *
     * @param pending the library's pending batches; may be null or empty
     * @param lineSep the line separator to use
     * @return the formatted pending-batches block, ending with a line separator
     */
    private String formatPendingBatches(List<PendingLibraryImpactedMethod> pending, String lineSep) {
        StringBuilder sb = new StringBuilder();
        int batchCount = pending != null ? pending.size() : 0;
        sb.append("\t\tPending batches: ").append(batchCount).append(lineSep);
        if (batchCount == 0) {
            return sb.toString();
        }

        for (PendingLibraryImpactedMethod batch : pending) {
            int methodCount = batch.getSourceMethodIds() != null ? batch.getSourceMethodIds().size() : 0;
            sb.append("\t\t\tseq ").append(batch.getPublishSeq())
                    .append(" @ ").append(batch.getStampVersion())
                    .append(" - ").append(methodCount).append(" method")
                    .append(methodCount == 1 ? "" : "s").append(" pending")
                    .append(lineSep);
        }
        return sb.toString();
    }

    /**
     * Group the pending batches by their owning library coordinate, preserving read order.
     *
     * @param pending all pending batches from the data store; may be null
     * @return map of {@code groupArtifact} to that library's batches; never null
     */
    private Map<String, List<PendingLibraryImpactedMethod>> groupPendingByLibrary(
            List<PendingLibraryImpactedMethod> pending) {
        Map<String, List<PendingLibraryImpactedMethod>> byLib = new LinkedHashMap<>();
        if (pending == null) {
            return byLib;
        }
        for (PendingLibraryImpactedMethod p : pending) {
            byLib.computeIfAbsent(p.getGroupArtifact(), k -> new ArrayList<>()).add(p);
        }
        return byLib;
    }

    /**
     * Render a possibly-absent value as a dash placeholder.
     *
     * @param s the value to render
     * @return the value, or {@code -} when null or empty
     */
    private String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }
}
