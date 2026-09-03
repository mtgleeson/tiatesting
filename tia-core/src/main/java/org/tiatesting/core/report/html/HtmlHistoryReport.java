package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.rendering.FlatHtml;
import j2html.tags.DomContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.report.ReportUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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

    private void writeHistoryHtmlToFile(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + TIA_HISTORY_HTML;
        log.info("Writing the test run history report to {}", fileName);

        List<TestRunHistoryEntry> history = tiaData.getTestRunHistory();
        final String numberDataType = "data-type=\"number\"";
        // The two distributed columns only earn their place when the history has a distributed
        // build in it; otherwise every row would dash them, so the table stays as it was.
        final boolean showDistributed = anyDistributed(history);
        // Same rule for the run-origin pair: a history recorded entirely before those columns
        // existed renders neither rather than dashing both on every row.
        final boolean showOrigin = anyKnownOrigin(history);

        try (FileWriter writer = new FileWriter(fileName)) {
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
                                    table(attrs("#tiaTable"),
                                            thead(buildHeaderRow(numberDataType, showDistributed, showOrigin)),
                                            tbody(each(history, entry -> buildRow(entry, showDistributed, showOrigin)))
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
            ).render(FlatHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the history report (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Report whether any row in the history describes a distributed build, which is what decides
     * whether the wall clock and group columns are rendered at all.
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
     * Report whether any row knows where it came from, which decides whether the run-source and
     * host columns are rendered at all. Either half counts: a distributed build records a source
     * with no host, so requiring both would hide the source on a history made up of distributed
     * builds.
     *
     * @param history the history rows about to be rendered; may be null
     * @return true when at least one row carries a run source or a host
     */
    private boolean anyKnownOrigin(List<TestRunHistoryEntry> history) {
        if (history == null) {
            return false;
        }
        for (TestRunHistoryEntry entry : history) {
            RunOrigin origin = entry.getRunOrigin();
            if (origin.getRunSource() != null || origin.getHostName() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Build the table's header row for the layout in use. The two distributed columns sit next to
     * Duration, since the reason they exist is to be read against it: Duration is the
     * serial-equivalent time the build would have taken unsplit and the figure savings come from,
     * while Wall clock is what the build actually took.
     *
     * @param numberDataType the {@code data-type} attribute simple-datatables sorts numerically by
     * @param showDistributed whether the wall clock and group columns are being rendered
     * @param showOrigin whether the run source and host columns are being rendered
     * @return the {@code <tr>} of header cells
     */
    private DomContent buildHeaderRow(String numberDataType, boolean showDistributed,
                                      boolean showOrigin) {
        List<DomContent> cells = new ArrayList<>();
        cells.add(th("Date / time (local)").attr(numberDataType));
        cells.add(th("Branch"));
        cells.add(th("Commit"));
        cells.add(th("Suites ran").attr(numberDataType));
        cells.add(th("Ignored").attr(numberDataType));
        cells.add(th("Failed").attr(numberDataType));
        cells.add(th("Duration").attr(numberDataType));
        if (showDistributed) {
            cells.add(th("Wall clock").attr(numberDataType));
            cells.add(th("Groups").attr(numberDataType));
        }
        cells.add(th("Savings").attr(numberDataType));
        cells.add(th("Savings %").attr(numberDataType));
        if (showOrigin) {
            cells.add(th("Source").withStyle("width: 6em"));
            cells.add(th("Host"));
        }
        cells.add(th("Updated Mapping?").withStyle("width: 8em"));
        cells.add(th("Id"));
        return tr(cells.toArray(new DomContent[0]));
    }

    /**
     * Build a single table row for one history entry. The Date / time cell carries the UTC
     * epoch ms in {@code data-epoch-ms} and {@code data-sort} so the inline localization
     * script and simple-datatables both have what they need.
     *
     * <p>A single-host row rendered in a mixed history dashes the two distributed cells rather than
     * showing zeros, which would read as a build that took no time and used no groups.
     *
     * @param entry the history entry to render as a row
     * @param showDistributed whether the wall clock and group columns are being rendered
     * @param showOrigin whether the run source and host columns are being rendered
     * @return the {@code <tr>} content for this entry
     */
    private DomContent buildRow(TestRunHistoryEntry entry, boolean showDistributed,
                                boolean showOrigin) {
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
                .attr("data-sort", String.valueOf(ms)));
        cells.add(td(text(entry.getBranch() == null ? "" : entry.getBranch())));
        // title on a span inside the td so the tooltip survives simple-datatables
        // re-rendering the row chrome on sort/page changes.
        cells.add(td(span(firstEightChars(entry.getCommit()))
                .attr("title", entry.getCommit() == null ? "" : entry.getCommit())));
        cells.add(td(String.valueOf(entry.getNumSuitesRan())));
        cells.add(td(String.valueOf(entry.getNumSuitesIgnored())));
        cells.add(td(String.valueOf(entry.getNumSuitesFailed())));
        cells.add(td(ReportUtils.prettyDuration(entry.getDurationMs(), true))
                .attr("data-sort", String.valueOf(entry.getDurationMs())));
        if (showDistributed) {
            long wallClockMs = entry.getWallClockMs() == null ? 0L : entry.getWallClockMs().longValue();
            cells.add(td(entry.getWallClockMs() == null
                    ? "-" : ReportUtils.prettyDuration(wallClockMs, true))
                    .attr("data-sort", String.valueOf(wallClockMs)));
            cells.add(td(entry.getGroupCount() == null ? "-" : entry.getGroupCount().toString())
                    .attr("data-sort", entry.getGroupCount() == null
                            ? "0" : entry.getGroupCount().toString()));
        }
        cells.add(td(entry.getTimeSavingsMs() > 0 ? ReportUtils.prettyDuration(entry.getTimeSavingsMs(), true) : "-")
                .attr("data-sort", String.valueOf(entry.getTimeSavingsMs())));
        cells.add(td(entry.getTimeSavingsMs() > 0 ? entry.getSavingsPercent() + "%" : "-")
                .attr("data-sort", String.valueOf(entry.getSavingsPercent())));
        if (showOrigin) {
            // Dashed rather than blank so an absent origin reads as "not recorded" rather than as
            // a rendering slip - and so the column sorts the unknown rows together.
            RunOrigin origin = entry.getRunOrigin();
            cells.add(td(origin.getRunSource() == null ? "-" : origin.getRunSource()));
            cells.add(td(origin.getHostName() == null ? "-" : origin.getHostName()));
        }
        cells.add(td(entry.isUpdatedDbMapping() ? "yes" : "no"));
        // title on a span inside the td so the tooltip survives simple-datatables
        // re-rendering the row chrome on sort/page changes.
        cells.add(td(span(firstEightChars(entry.getId()))
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
