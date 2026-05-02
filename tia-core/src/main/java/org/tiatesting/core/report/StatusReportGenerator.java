package org.tiatesting.core.report;

import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;

import java.text.DecimalFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generate core information about the stored DB and output it to the user.
 * This is intended as a quick snapshot as to the state of the stored DB for Tia.
 */
public class StatusReportGenerator {
    public String generateSummaryReport(DataStore dataStore) {
        TiaData tiaData = dataStore.getTiaCore();
        Locale locale = Locale.getDefault();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss zzz", locale).withZone(ZoneId.systemDefault());
        String lineSep = System.lineSeparator();

        StringBuilder sb = new StringBuilder(lineSep);
        sb.append("Tia Status:" + lineSep);
        sb.append("DB last updated: " + (tiaData.getLastUpdated()!= null ? dtf.format(tiaData.getLastUpdated()) : "N/A") + lineSep);
        sb.append("Test mapping valid for commit: " + tiaData.getCommitValue() + lineSep + lineSep);

        int numTestSuites = dataStore.getNumTestSuites();
        sb.append("Number of tests classes with mappings: " + numTestSuites + lineSep);
        int numSourceMethods = dataStore.getNumSourceMethods();
        sb.append("Number of source methods tracked for tests: " + numSourceMethods + lineSep);

        TestStats stats = tiaData.getTestStats();
        double percSuccess = ((double)stats.getNumSuccessRuns()) / (double)(stats.getNumRuns()) * 100;
        double percFail = ((double)stats.getNumFailRuns()) / (double)(stats.getNumRuns()) * 100;
        DecimalFormat avgFormat = new DecimalFormat("###.#");

        sb.append("Number of runs: " + stats.getNumRuns() + lineSep);
        sb.append("Average run time: " + ReportUtils.prettyDuration(stats.getAvgRunTime()) + lineSep);
        sb.append("Number of successful runs: " + stats.getNumSuccessRuns() + " (" + avgFormat.format(percSuccess) + "%)" + lineSep);
        sb.append("Number of failed runs: " + stats.getNumFailRuns() + " (" + avgFormat.format(percFail) + "%)" + lineSep + lineSep);

        sb.append(formatTrackedLibraries(dataStore.readTrackedLibraries(), lineSep));
        sb.append(lineSep).append(lineSep);

        Set<String> getTestSuitesFailed = dataStore.getTestSuitesFailed();
        String failedTests = getTestSuitesFailed.stream().map(test ->
                "\t" + test).collect(Collectors.joining(lineSep));
        failedTests = (failedTests != null && !failedTests.isEmpty()) ? failedTests : "\tnone";
        sb.append("Pending failed tests:" + lineSep + failedTests + lineSep + lineSep);

        sb.append(formatPendingLibraryChanges(dataStore.readAllPendingLibraryImpactedMethods(), lineSep));

        return sb.toString();
    }

    private String formatTrackedLibraries(Map<String, TrackedLibrary> tracked, String lineSep) {
        StringBuilder sb = new StringBuilder("Tracked libraries:").append(lineSep);
        if (tracked == null || tracked.isEmpty()) {
            sb.append("\tnone");
            return sb.toString();
        }
        for (TrackedLibrary lib : tracked.values()) {
            sb.append("\t").append(lib.getGroupArtifact()).append(lineSep);
            sb.append("\t\tProject dir: ").append(orDash(lib.getProjectDir())).append(lineSep);
            sb.append("\t\tLast source-project version: ").append(orDash(lib.getLastSourceProjectVersion())).append(lineSep);
            sb.append("\t\tLast released version (HWM): ").append(orDash(lib.getLastReleasedLibraryVersion())).append(lineSep);
        }
        // Trim the trailing line separator so callers control spacing.
        if (sb.length() >= lineSep.length()
                && sb.substring(sb.length() - lineSep.length()).equals(lineSep)) {
            sb.setLength(sb.length() - lineSep.length());
        }
        return sb.toString();
    }

    private String formatPendingLibraryChanges(List<PendingLibraryImpactedMethod> pending, String lineSep) {
        StringBuilder sb = new StringBuilder("Pending library changes:").append(lineSep);
        if (pending == null || pending.isEmpty()) {
            sb.append("\tnone");
            return sb.toString();
        }
        // Group by library so each library's pending batches are listed together.
        Map<String, List<PendingLibraryImpactedMethod>> byLib = new LinkedHashMap<>();
        for (PendingLibraryImpactedMethod p : pending) {
            byLib.computeIfAbsent(p.getGroupArtifact(), k -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<String, List<PendingLibraryImpactedMethod>> entry : byLib.entrySet()) {
            sb.append("\t").append(entry.getKey()).append(lineSep);
            for (PendingLibraryImpactedMethod batch : entry.getValue()) {
                int methodCount = batch.getSourceMethodIds() != null ? batch.getSourceMethodIds().size() : 0;
                sb.append("\t\t@ ").append(batch.getStampVersion())
                        .append(" — ").append(methodCount).append(" method")
                        .append(methodCount == 1 ? "" : "s").append(" pending");
                if (batch.isUnknownNextVersion()) {
                    sb.append(" (unknown next version)");
                }
                if (batch.getStampJarHash() != null) {
                    String hash = batch.getStampJarHash();
                    sb.append(" [hash: ").append(hash.length() > 12 ? hash.substring(0, 12) + "…" : hash).append("]");
                }
                sb.append(lineSep);
            }
        }
        // Trim trailing newline.
        if (sb.length() >= lineSep.length()
                && sb.substring(sb.length() - lineSep.length()).equals(lineSep)) {
            sb.setLength(sb.length() - lineSep.length());
        }
        return sb.toString();
    }

    private String orDash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }
}
