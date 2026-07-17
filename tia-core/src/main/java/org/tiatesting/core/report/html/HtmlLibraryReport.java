package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.rendering.FlatHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static j2html.TagCreator.*;

/**
 * Lists every tracked library with its ledger state and pending-batch count, followed by the
 * per-library publish ledger (one row per published build) and the pending impacted-method
 * batches (one row per publish still awaiting its drain).
 */
public class HtmlLibraryReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlLibraryReport.class);
    protected static final String LIBRARIES_FOLDER = "libraries";
    protected static final String TIA_LIBRARIES_HTML = "tia-libraries.html";
    private final File reportOutputDir;

    /** Path back to the assets dir from a one-level-deep page. */
    private static final String ASSETS_REL = "../" + HtmlAssetCopier.ASSETS_DIR_NAME;
    /** Path back to the report root from a one-level-deep page. */
    private static final String ROOT_REL = "../";

    public HtmlLibraryReport(String filenameExt, File reportOutputDir){
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + LIBRARIES_FOLDER);
    }

    public void generateReport(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        if (!reportOutputDir.exists()) {
            reportOutputDir.mkdirs();
        }
        String fileName = reportOutputDir + File.separator + TIA_LIBRARIES_HTML;
        log.info("Writing the libraries report to {}", fileName);

        Map<String, Integer> pendingPerLib = countPendingPerLibrary(tiaData);
        List<PendingLibraryImpactedMethod> pending = tiaData.getPendingLibraryImpactedMethods() != null
                ? tiaData.getPendingLibraryImpactedMethods() : new java.util.ArrayList<>();
        int pendingCount = pending.size();
        Map<String, TrackedLibrary> tracked = tiaData.getLibrariesTracked() != null
                ? tiaData.getLibrariesTracked() : new HashMap<>();
        List<LibraryPublish> publishes = tiaData.getLibraryPublishes() != null
                ? tiaData.getLibraryPublishes() : new java.util.ArrayList<>();
        Map<String, Integer> pendingMethodsBySeq = countPendingMethodsBySeq(pending);

        try (FileWriter writer = new FileWriter(fileName)) {
            final String numberDataType = "data-type=\"number\"";

            html(
                    HtmlLayout.pageHead("Libraries", ASSETS_REL),
                    body(
                            HtmlLayout.topNav(HtmlLayout.NavKey.SOURCE_CODE, ASSETS_REL, ROOT_REL, pendingCount),
                            main(
                                    HtmlLayout.breadcrumb(
                                            HtmlLayout.Crumb.link("Home", ROOT_REL + "index.html"),
                                            HtmlLayout.Crumb.link("Source Code", ROOT_REL + "source-code.html"),
                                            HtmlLayout.Crumb.current("Libraries")
                                    ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_LIBRARY, "Tracked Libraries"),
                                    tracked.isEmpty()
                                            ? p(span("No libraries tracked.").withClass("tia-empty"))
                                            : table(attrs("#tiaLibrariesTable"),
                                                    thead(tr(
                                                            th("Group:Artifact"),
                                                            th("Project Dir"),
                                                            th("Last applied publish seq").attr(numberDataType),
                                                            th("Pending batches").attr(numberDataType),
                                                            th("Source dirs")
                                                    )),
                                                    tbody(each(tracked.values(), lib ->
                                                            tr(
                                                                    td(lib.getGroupArtifact()),
                                                                    td(emptyDash(lib.getProjectDir())),
                                                                    td(lib.getLastAppliedSeq() != null
                                                                            ? String.valueOf(lib.getLastAppliedSeq()) : "—"),
                                                                    td(String.valueOf(pendingPerLib.getOrDefault(lib.getGroupArtifact(), 0))),
                                                                    td(emptyDash(lib.getSourceDirsCsv()))
                                                            )
                                                    ))
                                            ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_LIBRARY, "Publishes"),
                                    publishes.isEmpty()
                                            ? p(span("No publishes recorded.").withClass("tia-empty"))
                                            : table(attrs("#tiaLibraryPublishesTable"),
                                                    thead(tr(
                                                            th("Library"),
                                                            th("Seq").attr(numberDataType),
                                                            th("Version"),
                                                            th("Jar hash"),
                                                            th("Commit"),
                                                            th("Published at"),
                                                            th("Methods pending").attr(numberDataType)
                                                    )),
                                                    tbody(each(publishes, publish ->
                                                            tr(
                                                                    td(publish.getGroupArtifact()),
                                                                    td(String.valueOf(publish.getPublishSeq())),
                                                                    td(publish.getPublishedVersion()),
                                                                    publish.getJarHash() != null
                                                                            ? td(truncate(publish.getJarHash())).attr("title", publish.getJarHash())
                                                                            : td("—"),
                                                                    td(emptyDash(truncate(publish.getCommitValue()))),
                                                                    td(Instant.ofEpochMilli(publish.getPublishedAt()).toString()),
                                                                    td(String.valueOf(pendingMethodsBySeq.getOrDefault(
                                                                            publish.getGroupArtifact() + "|" + publish.getPublishSeq(), 0)))
                                                            )
                                                    ))
                                            ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_LIBRARY, "Pending changes"),
                                    pending.isEmpty()
                                            ? p(span("No pending library changes.").withClass("tia-empty"))
                                            : table(attrs("#tiaLibraryPendingTable"),
                                                    thead(tr(
                                                            th("Library"),
                                                            th("Publish seq").attr(numberDataType),
                                                            th("Version"),
                                                            th("Methods pending").attr(numberDataType)
                                                    )),
                                                    tbody(each(pending, batch ->
                                                            tr(
                                                                    td(batch.getGroupArtifact()),
                                                                    td(String.valueOf(batch.getPublishSeq())),
                                                                    td(batch.getStampVersion()),
                                                                    td(String.valueOf(batch.getSourceMethodIds() != null
                                                                            ? batch.getSourceMethodIds().size() : 0))
                                                            )
                                                    ))
                                            )
                            ),
                            HtmlLayout.pageFooter(),
                            tracked.isEmpty()
                                    ? text("")
                                    : HtmlLayout.simpleDatatablesInit("#tiaLibrariesTable", ASSETS_REL)
                    )
            ).render(FlatHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private Map<String, Integer> countPendingPerLibrary(TiaData tiaData) {
        Map<String, Integer> counts = new HashMap<>();
        List<PendingLibraryImpactedMethod> pending = tiaData.getPendingLibraryImpactedMethods();
        if (pending == null) {
            return counts;
        }
        for (PendingLibraryImpactedMethod m : pending) {
            counts.merge(m.getGroupArtifact(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Count each pending batch's methods keyed by {@code groupArtifact|publishSeq}, so the
     * publishes table can show how many of a build's stamped methods still await their drain.
     *
     * @param pending all pending batches across libraries
     * @return map of {@code groupArtifact|publishSeq} to the batch's pending method count
     */
    private Map<String, Integer> countPendingMethodsBySeq(List<PendingLibraryImpactedMethod> pending) {
        Map<String, Integer> counts = new HashMap<>();
        for (PendingLibraryImpactedMethod batch : pending) {
            counts.put(batch.getGroupArtifact() + "|" + batch.getPublishSeq(),
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
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 12 ? value.substring(0, 12) + "…" : value;
    }

    private static String emptyDash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }
}
