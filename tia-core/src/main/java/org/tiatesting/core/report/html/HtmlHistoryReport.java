package org.tiatesting.core.report.html;

import j2html.rendering.FlatHtml;
import j2html.tags.DomContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.report.ReportUtils;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static j2html.TagCreator.a;
import static j2html.TagCreator.attrs;
import static j2html.TagCreator.body;
import static j2html.TagCreator.each;
import static j2html.TagCreator.html;
import static j2html.TagCreator.main;
import static j2html.TagCreator.rawHtml;
import static j2html.TagCreator.span;
import static j2html.TagCreator.table;
import static j2html.TagCreator.tbody;
import static j2html.TagCreator.td;
import static j2html.TagCreator.text;
import static j2html.TagCreator.th;
import static j2html.TagCreator.thead;
import static j2html.TagCreator.tr;

/**
 * Renders the Tia "History" page - a sortable, searchable table of past test-run rows from
 * {@code tia_test_run_history}. Each row's timestamp is emitted as an HTML5 {@code <time>}
 * element carrying the UTC epoch ms in a {@code data-epoch-ms} attribute; the page-level
 * inline script swaps the displayed text for the viewer's local-time rendering on load.
 *
 * <p>The page is a one-level-deep sibling of the other report pages (e.g. {@code test-suites/},
 * {@code libraries/}), at {@code history/tia-history.html}.
 */
public class HtmlHistoryReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlHistoryReport.class);
    protected static final String HISTORY_FOLDER = "history";
    protected static final String TIA_HISTORY_HTML = "tia-history.html";

    /** Path back to the assets dir from a one-level-deep page. */
    private static final String ASSETS_REL = "../" + HtmlAssetCopier.ASSETS_DIR_NAME;
    /** Path back to the report root from a one-level-deep page. */
    private static final String ROOT_REL = "../";

    private final File reportOutputDir;

    /**
     * @param filenameExt the branch-named subfolder under {@code <reportOutputDir>/html/}
     *                    that scopes all report files for the current branch
     * @param reportOutputDir the project's configured report-output root (the parent of
     *                        the per-branch {@code html/<branch>/} tree)
     */
    public HtmlHistoryReport(String filenameExt, File reportOutputDir){
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + HISTORY_FOLDER);
    }

    /**
     * Generate the history page from {@code tiaData.getTestRunHistory()}. Creates the output
     * folder if needed and writes {@code tia-history.html} into it.
     *
     * @param tiaData the loaded Tia data (the history list is read from this)
     */
    public void generateReport(TiaData tiaData) {
        createOutputDir();
        writeHistoryHtmlToFile(tiaData);
    }

    private int pendingCount(TiaData tiaData) {
        return tiaData.getPendingLibraryImpactedMethods() != null
                ? tiaData.getPendingLibraryImpactedMethods().size() : 0;
    }

    /**
     * Write the run-history page. Renders through {@link FastTextEscaper#reportConfig()} so
     * text and attribute escaping use the report's fast, allocation-light escaper.
     *
     * @param tiaData the Tia data from the DB
     */
    private void writeHistoryHtmlToFile(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + TIA_HISTORY_HTML;
        log.info("Writing the test run history report to {}", fileName);

        List<TestRunHistoryEntry> history = tiaData.getTestRunHistory();
        final String numberDataType = "data-type=\"number\"";
        // The Groups column only earns its place when the history has a distributed build in it;
        // otherwise every row would dash it.
        final boolean showDistributed = anyDistributed(history);
        // Same rule for the run-origin pair: a history recorded entirely before those columns
        // existed renders neither rather than dashing both on every row.
        final boolean showHost = anyHost(history);

        try (Writer writer = HtmlLayout.newReportWriter(fileName)) {
            html(
                    HtmlLayout.pageHead("History", ASSETS_REL),
                    body(
                            HtmlLayout.topNav(HtmlLayout.NavKey.HISTORY, ASSETS_REL, ROOT_REL,
                                    pendingCount(tiaData)),
                            main(
                                    HtmlLayout.breadcrumb(
                                            HtmlLayout.Crumb.link("Home", ROOT_REL + "index.html"),
                                            HtmlLayout.Crumb.current("History")
                                    ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_HISTORY, "Test Run History"),
                                    HtmlHistoryTimeline.render(history),
                                    table(attrs("#tiaTable"),
                                            thead(buildHeaderRow(numberDataType, showDistributed, showHost)),
                                            tbody(each(history, entry -> buildRow(entry, showDistributed, showHost)))
                                    )
                            ),
                            HtmlLayout.pageFooter(),
                            // Localize first so simple-datatables captures the already-formatted
                            // cell text into its model. If init runs before localization,
                            // simple-datatables rebuilds the DOM with the raw ISO fallback and
                            // the <time> elements no longer exist for the localizer to swap.
                            HtmlLayout.localTimeRenderingScript(),
                            HtmlLayout.simpleDatatablesInit("#tiaTable", ASSETS_REL, 0, "desc")
                    )
            ).render(FlatHtml.into(writer, FastTextEscaper.reportConfig())).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the history report (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Report whether any row in the history describes a distributed build, which is what decides
     * whether the Groups column is rendered at all.
     *
     * @param history the history rows about to be rendered; may be null
     * @return true when at least one row carries a distributed run's group count
     */
    private boolean anyDistributed(List<TestRunHistoryEntry> history) {
        if (history == null) {
            return false;
        }
        for (TestRunHistoryEntry entry : history) {
            if (entry.getGroupCount() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Report whether any row names the machine that ran it, which decides whether the Host column
     * is rendered at all. A distributed build spans several machines and names none, so a history
     * made up entirely of distributed builds would otherwise carry a column of dashes. The Source
     * column needs no such gate - every recorded run resolves a source.
     *
     * @param history the history rows about to be rendered; may be null
     * @return true when at least one row carries a host
     */
    private boolean anyHost(List<TestRunHistoryEntry> history) {
        if (history == null) {
            return false;
        }
        for (TestRunHistoryEntry entry : history) {
            if (entry.getRunOrigin().getHostName() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Build the table's header row for the layout in use. Every time on the table is wall-clock
     * time - how long the run took end to end, and how much end-to-end time Tia saved it - so the
     * table reads the same for single-host and distributed runs. The serial duration and serial
     * savings live on each run's detail page. See the "Test-run history log" chapter in {@code
     * WIKI.md}.
     *
     * @param numberDataType the {@code data-type} attribute simple-datatables sorts numerically by
     * @param showDistributed whether the Groups column is being rendered
     * @param showHost whether the host column is being rendered
     * @return the {@code <tr>} of header cells
     */
    private DomContent buildHeaderRow(String numberDataType, boolean showDistributed,
                                      boolean showHost) {
        List<DomContent> cells = new ArrayList<>();
        cells.add(th("Date / time (local)").attr(numberDataType));
        cells.add(th("Branch"));
        cells.add(th("Commit"));
        cells.add(th("Suites ran").attr(numberDataType));
        cells.add(th("Ignored").attr(numberDataType));
        cells.add(th("Failed").attr(numberDataType));
        cells.add(th("Wall clock").attr(numberDataType));
        if (showDistributed) {
            cells.add(th("Groups").attr(numberDataType));
        }
        cells.add(th("Savings").attr(numberDataType));
        cells.add(th("Savings %").attr(numberDataType));
        cells.add(th("Source").withStyle("width: 6em"));
        if (showHost) {
            cells.add(th("Host"));
        }
        cells.add(th("Updated Mapping?").withStyle("width: 8em"));
        cells.add(th("Id"));
        return tr(cells.toArray(new DomContent[0]));
    }

    /**
     * Build a single table row for one history entry. The Date / time cell carries the UTC
     * epoch ms in {@code data-epoch-ms} and {@code data-order} so the inline localization
     * script and simple-datatables both have what they need. Numeric columns whose displayed
     * text is not itself a plain number (durations, and the savings columns that dash to "-")
     * carry their sort value in {@code data-order}: simple-datatables reads a cell's custom sort
     * value from that attribute, and a number column with none falls back to parsing the cell
     * text, which yields {@code NaN} for a "-" cell and breaks the column's sort.
     *
     * <p>A single-host row rendered in a mixed history dashes the Groups cell rather than showing a
     * zero, which would read as a build that used no groups.
     *
     * @param entry the history entry to render as a row
     * @param showDistributed whether the Groups column is being rendered
     * @param showHost whether the host column is being rendered
     * @return the {@code <tr>} content for this entry
     */
    private DomContent buildRow(TestRunHistoryEntry entry, boolean showDistributed,
                                boolean showHost) {
        long ms = entry.getRunTimestampMs();
        // Fallback text shown only when the localizer script doesn't run (JS disabled). Truncate
        // to whole seconds and drop the UTC 'Z' so the displayed text matches the no-ms /
        // no-tz formatting rule even in that edge case.
        String fallback = Instant.ofEpochMilli(ms)
                .atOffset(ZoneOffset.UTC)
                .toLocalDateTime()
                .withNano(0)
                .toString();
        List<DomContent> cells = new ArrayList<>();
        cells.add(td(rawHtml("<time data-epoch-ms=\"" + ms + "\">" + fallback + "</time>"))
                .attr("data-order", String.valueOf(ms)));
        cells.add(td(text(entry.getBranch() == null ? "" : entry.getBranch())));
        // title on a span inside the td so the tooltip survives simple-datatables
        // re-rendering the row chrome on sort/page changes.
        cells.add(td(span(firstEightChars(entry.getCommit()))
                .attr("title", entry.getCommit() == null ? "" : entry.getCommit())));
        cells.add(td(String.valueOf(entry.getNumSuitesRan())));
        cells.add(td(String.valueOf(entry.getNumSuitesIgnored())));
        cells.add(td(String.valueOf(entry.getNumSuitesFailed())));
        cells.add(td(ReportUtils.prettyDuration(entry.getRunWallClockMs(), true))
                .attr("data-order", String.valueOf(entry.getRunWallClockMs())));
        if (showDistributed) {
            cells.add(td(entry.getGroupCount() == null ? "-" : entry.getGroupCount().toString())
                    .attr("data-order", entry.getGroupCount() == null
                            ? "0" : entry.getGroupCount().toString()));
        }
        long savingsMs = entry.getWallClockSavingsMs();
        cells.add(td(savingsMs > 0 ? ReportUtils.prettyDuration(savingsMs, true) : "-")
                .attr("data-order", String.valueOf(savingsMs)));
        cells.add(td(savingsMs > 0 ? entry.getWallClockSavingsPercent() + "%" : "-")
                .attr("data-order", String.valueOf(entry.getWallClockSavingsPercent())));
        RunOrigin origin = entry.getRunOrigin();
        cells.add(td(origin.getRunSource()));
        if (showHost) {
            // Dashed rather than blank so a distributed build's absent host reads as "no single
            // machine" rather than as a rendering slip, and sorts those rows together.
            cells.add(td(origin.getHostName() == null ? "-" : origin.getHostName()));
        }
        cells.add(td(entry.isUpdatedDbMapping() ? "yes" : "no"));
        // Linked to the sibling detail page history/<id>.html, generated by HtmlHistoryDetailReport
        // for every history entry. Title on the anchor itself (the td's only child) so the tooltip
        // survives simple-datatables re-rendering the row chrome on sort/page changes.
        cells.add(td(a(text(firstEightChars(entry.getId())))
                .withHref(entry.getId() == null ? "#" : entry.getId() + ".html")
                .attr("title", entry.getId() == null ? "" : entry.getId())));
        return tr(cells.toArray(new DomContent[0]));
    }

    /**
     * First 8 characters of a value, used to keep wide identifier columns (entry id, commit
     * hash) compact in the table. The full value lives on the cell's hover {@code title}.
     *
     * @param value the source value; may be {@code null} defensively
     * @return the first 8 characters of {@code value}, or the whole value if shorter, or
     *         {@code ""} when {@code value} is {@code null}
     */
    private static String firstEightChars(String value) {
        if (value == null) return "";
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private void createOutputDir() {
        if (!reportOutputDir.exists() && !reportOutputDir.mkdirs()) {
            log.warn("Failed to create report output directory: {}", reportOutputDir.getAbsolutePath());
        }
    }
}
