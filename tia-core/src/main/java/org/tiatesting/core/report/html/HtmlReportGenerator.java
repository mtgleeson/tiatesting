package org.tiatesting.core.report.html;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.report.ReportGenerator;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class HtmlReportGenerator implements ReportGenerator {
    private static final Logger log = LoggerFactory.getLogger(HtmlReportGenerator.class);
    private final String filenameExt;
    private final File reportOutputDir;
    private final DataStore dataStore;

    /**
     * @param filenameExt the branch-named subfolder under {@code <reportOutputDir>/html/} that
     *                    scopes all report files for the current branch
     * @param reportOutputDir the project's configured report-output root (the parent of the
     *                        per-branch {@code html/<branch>/} tree)
     * @param dataStore the datastore used to bulk-load run-history selection triggers when
     *                  generating the per-run history detail pages; may be {@code null}, in which
     *                  case the detail pages are still generated but render with no triggers
     */
    public HtmlReportGenerator(String filenameExt, File reportOutputDir, DataStore dataStore){
        this.filenameExt = filenameExt;
        this.reportOutputDir = reportOutputDir;
        this.dataStore = dataStore;
    }

    @Override
    public void generateReports(TiaData tiaData) {
        copyStaticAssets();
        generateSummaryReport(tiaData);
        generateTestSuiteReport(tiaData);
        generateSourceMethodReport(tiaData);
        generateSourceCodeLandingReport(tiaData);
        generateLibraryReport(tiaData);
        generateHistoryReport(tiaData);
    }

    @Override
    public String generateSummaryReport(TiaData tiaData) {
        new HtmlSummaryReport(filenameExt, reportOutputDir).generateSummaryReport(tiaData);
        return null;
    }

    @Override
    public String generateTestSuiteReport(TiaData tiaData) {
        new HtmlTestSuiteReport(filenameExt, reportOutputDir).generateTestSuiteReport(tiaData);
        return null;
    }

    @Override
    public String generateSourceMethodReport(TiaData tiaData) {
        new HtmlSourceMethodReport(filenameExt, reportOutputDir).generateSourceMethodReport(tiaData);
        return null;
    }

    /**
     * Generate the source-code landing page that links the per-source-file drill-downs.
     * Exposed (rather than private) so profiling harnesses can time this step of
     * {@link #generateReports(TiaData)} in isolation on a real generator instance.
     *
     * @param tiaData the Tia data from the DB
     */
    public void generateSourceCodeLandingReport(TiaData tiaData) {
        new HtmlSourceCodeLandingReport(filenameExt, reportOutputDir).generateReport(tiaData);
    }

    /**
     * Generate the third-party library impact report. Exposed (rather than private) so profiling
     * harnesses can time this step of {@link #generateReports(TiaData)} in isolation on a real
     * generator instance.
     *
     * @param tiaData the Tia data from the DB
     */
    public void generateLibraryReport(TiaData tiaData) {
        new HtmlLibraryReport(filenameExt, reportOutputDir).generateReport(tiaData);
    }

    /**
     * Generate the run-history report (per-run timings and selection savings over time), plus one
     * detail page per history entry. Exposed (rather than private) so profiling harnesses can time
     * this step of {@link #generateReports(TiaData)} in isolation on a real generator instance.
     *
     * <p>Selection triggers for every history entry are bulk-loaded from {@link #dataStore} in a
     * single call (never per-row) so this stays off the hot {@code select-tests} read path and
     * scales with one query regardless of history length. When {@link #dataStore} is {@code null}
     * (e.g. a caller with no live datastore), the detail pages are still generated so the history
     * table's row links never 404 - they simply render with no recorded triggers.
     *
     * @param tiaData the Tia data from the DB
     */
    public void generateHistoryReport(TiaData tiaData) {
        new HtmlHistoryReport(filenameExt, reportOutputDir).generateReport(tiaData);
        generateHistoryDetailReports(tiaData);
    }

    /**
     * Generate the per-run history detail pages linked from the history table's Id column. Bulk-
     * loads every entry's selection triggers in one datastore call, then writes one detail page per
     * history entry so every row link resolves to a real page.
     *
     * @param tiaData the Tia data from the DB; the history list and pending-library count are read
     *                from this
     */
    private void generateHistoryDetailReports(TiaData tiaData) {
        List<TestRunHistoryEntry> history = tiaData.getTestRunHistory();
        if (history == null) {
            history = Collections.emptyList();
        }
        int pendingLibraryCount = pendingLibraryCount(tiaData);

        List<String> historyIds = new ArrayList<>(history.size());
        for (TestRunHistoryEntry entry : history) {
            historyIds.add(entry.getId());
        }
        Map<String, List<TestRunTrigger>> triggersById = (dataStore != null && !historyIds.isEmpty())
                ? dataStore.readTestRunTriggersByHistoryId(historyIds)
                : Collections.emptyMap();

        HtmlHistoryDetailReport detailReport = new HtmlHistoryDetailReport(filenameExt, reportOutputDir);
        for (TestRunHistoryEntry entry : history) {
            List<TestRunTrigger> triggers = triggersById.getOrDefault(entry.getId(), Collections.emptyList());
            detailReport.generateReport(entry, triggers, pendingLibraryCount);
        }
    }

    /**
     * Count the pending library-impacted methods for the top-nav badge, mirroring
     * {@code HtmlHistoryReport.pendingCount}.
     *
     * @param tiaData the Tia data from the DB
     * @return the number of pending library-impacted methods, or 0 when none are recorded
     */
    private int pendingLibraryCount(TiaData tiaData) {
        return tiaData.getPendingLibraryImpactedMethods() != null
                ? tiaData.getPendingLibraryImpactedMethods().size() : 0;
    }

    /**
     * Extract bundled CSS / JS / images from the {@code tia-core} JAR into
     * {@code <reportOutputDir>/html/<branch>/assets/} so every page can reference them
     * via relative URLs. Exposed (rather than private) so profiling harnesses can time this
     * step of {@link #generateReports(TiaData)} in isolation on a real generator instance.
     */
    public void copyStaticAssets() {
        File branchDir = new File(reportOutputDir.getAbsoluteFile()
                + File.separator + "html" + File.separator + filenameExt);
        if (!branchDir.exists() && !branchDir.mkdirs()) {
            log.warn("Failed to create per-branch report dir, asset copy may fail: {}",
                    branchDir.getAbsolutePath());
        }
        HtmlAssetCopier.copyAssetsTo(branchDir);
    }
}
