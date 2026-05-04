package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.rendering.FlatHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static j2html.TagCreator.*;

/**
 * Lists every tracked library with its on-classpath state and pending-batch count.
 * Per-library detail page is intentionally deferred — the row's pending count links to the
 * pending section on {@code index.html} for now.
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
        int pendingCount = tiaData.getPendingLibraryImpactedMethods() != null
                ? tiaData.getPendingLibraryImpactedMethods().size() : 0;
        Map<String, TrackedLibrary> tracked = tiaData.getLibrariesTracked() != null
                ? tiaData.getLibrariesTracked() : new HashMap<>();

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
                                                            th("Last source-project version"),
                                                            th("Last released version (HWM)"),
                                                            th("Pending batches").attr(numberDataType),
                                                            th("Source dirs")
                                                    )),
                                                    tbody(each(tracked.values(), lib ->
                                                            tr(
                                                                    td(lib.getGroupArtifact()),
                                                                    td(emptyDash(lib.getProjectDir())),
                                                                    td(emptyDash(lib.getLastSourceProjectVersion())),
                                                                    td(emptyDash(lib.getLastReleasedLibraryVersion())),
                                                                    td(String.valueOf(pendingPerLib.getOrDefault(lib.getGroupArtifact(), 0))),
                                                                    td(emptyDash(lib.getSourceDirsCsv()))
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

    private static String emptyDash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }
}
