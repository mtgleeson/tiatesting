package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.rendering.FlatHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static j2html.TagCreator.*;

/**
 * Landing page for the "Source Code" nav entry. Shows two card-style links: one to the
 * existing methods report, one to the new libraries report. Lives at the report root
 * alongside {@code index.html}.
 */
public class HtmlSourceCodeLandingReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlSourceCodeLandingReport.class);
    protected static final String SOURCE_CODE_HTML = "source-code.html";
    private final File reportOutputDir;

    public HtmlSourceCodeLandingReport(String filenameExt, File reportOutputDir){
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt);
    }

    public void generateReport(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        if (!reportOutputDir.exists()) {
            reportOutputDir.mkdirs();
        }
        String fileName = reportOutputDir + File.separator + SOURCE_CODE_HTML;
        log.info("Writing the source-code landing page to {}", fileName);

        String assetsRel = HtmlAssetCopier.ASSETS_DIR_NAME;
        String rootRel = "";
        int methodCount = tiaData.getMethodsTracked() != null ? tiaData.getMethodsTracked().size() : 0;
        int libraryCount = tiaData.getLibrariesTracked() != null ? tiaData.getLibrariesTracked().size() : 0;
        int pendingCount = tiaData.getPendingLibraryImpactedMethods() != null
                ? tiaData.getPendingLibraryImpactedMethods().size() : 0;

        try (FileWriter writer = new FileWriter(fileName)) {
            html(
                    HtmlLayout.pageHead("Source Code", assetsRel),
                    body(
                            HtmlLayout.topNav(HtmlLayout.NavKey.SOURCE_CODE, assetsRel, rootRel, pendingCount),
                            main(
                                    HtmlLayout.breadcrumb(
                                            HtmlLayout.Crumb.link("Home", "index.html"),
                                            HtmlLayout.Crumb.current("Source Code")
                                    ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_SOURCE_CODE, "Source Code"),
                                    p("Choose a source-code view."),
                                    div(attrs(".tia-card-grid"),
                                            a(attrs(".tia-card"),
                                                    article(
                                                            header("Methods"),
                                                            p(methodCount + " source method"
                                                                    + (methodCount == 1 ? "" : "s") + " tracked.")
                                                    )
                                            ).withHref(HtmlSourceMethodReport.METHODS_FOLDER + "/"
                                                    + HtmlSourceMethodReport.TIA_SOURCE_METHODS_HTML),
                                            a(attrs(".tia-card"),
                                                    article(
                                                            header("Libraries"),
                                                            p(libraryCount + " " + (libraryCount == 1 ? "library" : "libraries")
                                                                    + " tracked"
                                                                    + (pendingCount > 0
                                                                            ? " · " + pendingCount + " pending change"
                                                                                    + (pendingCount == 1 ? "" : "s")
                                                                            : "") + ".")
                                                    )
                                            ).withHref(HtmlLibraryReport.LIBRARIES_FOLDER + "/"
                                                    + HtmlLibraryReport.TIA_LIBRARIES_HTML)
                                    )
                            ),
                            HtmlLayout.pageFooter()
                    )
            ).render(FlatHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }
}
