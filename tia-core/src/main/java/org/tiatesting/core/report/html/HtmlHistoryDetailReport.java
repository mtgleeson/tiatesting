package org.tiatesting.core.report.html;

import j2html.rendering.FlatHtml;
import j2html.tags.DomContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.report.ReportUtils;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static j2html.TagCreator.attrs;
import static j2html.TagCreator.body;
import static j2html.TagCreator.br;
import static j2html.TagCreator.each;
import static j2html.TagCreator.html;
import static j2html.TagCreator.main;
import static j2html.TagCreator.p;
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
 * Renders the per-run "Run detail" HTML page - the summary of a single {@code
 * tia_test_run_history} row plus, when recorded, the breakdown of what drove selection for that
 * run: the per-changed-method and per-static-rule triggers, ranked by how many test suites each
 * one accounts for, and the scalar counts for the other selection sources.
 *
 * <p>The page is a one-level-deep sibling of {@link HtmlHistoryReport}'s {@code
 * tia-history.html}, at {@code history/<entry-id>.html}, so the two pages can link to each other
 * with a plain filename and share the same {@code ASSETS_REL}/{@code ROOT_REL} depth.
 *
 * <p>See the "Run history details" chapter in {@code WIKI.md}.
 */
public class HtmlHistoryDetailReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlHistoryDetailReport.class);
    protected static final String HISTORY_FOLDER = "history";

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
    public HtmlHistoryDetailReport(String filenameExt, File reportOutputDir) {
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + HISTORY_FOLDER);
    }

    /**
     * Generate the detail page for one history entry. Creates the output folder if needed and
     * writes {@code history/<entry.getId()>.html} into it.
     *
     * @param entry the history row this page describes
     * @param triggers the per-method and per-rule selection triggers recorded for this run;
     *                 empty when none were recorded (an all-tests run, or a row written before
     *                 this feature)
     * @param pendingLibraryCount the current count of pending library-impacted methods, shown as
     *                            the top-nav badge
     */
    public void generateReport(TestRunHistoryEntry entry, List<TestRunTrigger> triggers,
                               int pendingLibraryCount) {
        createOutputDir();
        writeDetailHtmlToFile(entry, triggers, pendingLibraryCount);
    }

    /**
     * Write the run-detail page. Renders through {@link FastTextEscaper#reportConfig()} so text
     * and attribute escaping use the report's fast, allocation-light escaper.
     *
     * @param entry the history row this page describes
     * @param triggers the per-method and per-rule selection triggers recorded for this run
     * @param pendingLibraryCount the current count of pending library-impacted methods, shown as
     *                            the top-nav badge
     */
    private void writeDetailHtmlToFile(TestRunHistoryEntry entry, List<TestRunTrigger> triggers,
                                       int pendingLibraryCount) {
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + entry.getId() + ".html";
        log.info("Writing the run detail report to {}", fileName);

        List<TestRunTrigger> sourceMethodTriggers = TestRunTrigger.filterByTypeSortedByCountDesc(
                triggers, TestRunTrigger.Type.SOURCE_METHOD);
        List<TestRunTrigger> staticRuleTriggers = TestRunTrigger.filterByTypeSortedByCountDesc(
                triggers, TestRunTrigger.Type.STATIC_RULE);
        boolean noBreakdownRecorded = (triggers == null || triggers.isEmpty())
                && entry.getNumModifiedTestFiles() == null
                && entry.getNumNewTestFiles() == null
                && entry.getNumPreviouslyFailed() == null
                && entry.getNumUnsealedMapping() == null
                && entry.getNumPendingLibrary() == null;

        try (Writer writer = HtmlLayout.newReportWriter(fileName)) {
            html(
                    HtmlLayout.pageHead("Run detail", ASSETS_REL),
                    body(
                            HtmlLayout.topNav(HtmlLayout.NavKey.HISTORY, ASSETS_REL, ROOT_REL,
                                    pendingLibraryCount),
                            main(
                                    HtmlLayout.breadcrumb(
                                            HtmlLayout.Crumb.link("Home", ROOT_REL + "index.html"),
                                            HtmlLayout.Crumb.link("History", HtmlHistoryReport.TIA_HISTORY_HTML),
                                            HtmlLayout.Crumb.current(firstEightChars(entry.getId()), entry.getId())
                                    ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_HISTORY, "Run detail"),
                                    buildSummaryBlock(entry),
                                    buildSelectionSourcesBlock(entry),
                                    noBreakdownRecorded
                                            ? p(span("No selection breakdown was recorded for this run.")
                                                    .withClass("tia-empty"))
                                            : buildTriggerTables(sourceMethodTriggers, staticRuleTriggers)
                            ),
                            HtmlLayout.pageFooter(),
                            // Localize first so simple-datatables captures the already-formatted
                            // cell text into its model, matching the ordering HtmlHistoryReport uses.
                            HtmlLayout.localTimeRenderingScript(),
                            noBreakdownRecorded ? text("") : buildTriggerTablesInit()
                    )
            ).render(FlatHtml.into(writer, FastTextEscaper.reportConfig())).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the run detail report (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Build the summary block: the run's timestamp, branch, commit, origin (the run source and the
     * host that ran it, which the History table leaves out), whether it was a rerun of failed tests
     * (credited no savings), suite counts, both its times and
     * both its savings, and the groups it used and had available. The wall clock and wall-clock
     * savings are what the History table shows; the serial duration and serial savings are the same
     * run measured as total machine time, as if one machine had run it. The two are equal for a
     * single-host run, whose group lines are dashed. The timestamp is rendered like
     * {@link HtmlHistoryReport}'s table rows - an HTML5 {@code <time>} element carrying the UTC
     * epoch ms, localized client-side by {@link HtmlLayout#localTimeRenderingScript()} - but keeps
     * its seconds, which the table drops. Each label carries a hover tooltip explaining the field.
     *
     * @param entry the history row this page describes
     * @return the summary block content
     */
    private DomContent buildSummaryBlock(TestRunHistoryEntry entry) {
        long ms = entry.getRunTimestampMs();
        // Fallback text shown only when the localizer script doesn't run (JS disabled). Truncate
        // to whole seconds and drop the UTC 'Z' so the displayed text matches the no-ms /
        // no-tz formatting rule even in that edge case.
        String fallback = Instant.ofEpochMilli(ms)
                .atOffset(ZoneOffset.UTC)
                .toLocalDateTime()
                .withNano(0)
                .toString();

        return p(
                field("Date / time", "When the test run started, in your browser's local time.",
                        rawHtml("<time data-epoch-ms=\"" + ms + "\">" + fallback + "</time>")),
                field("Branch", "The VCS branch the run was against.",
                        entry.getBranch() == null ? "" : entry.getBranch()),
                field("Commit", "The VCS commit or changelist the run tested.",
                        entry.getCommit() == null ? "" : entry.getCommit()),
                field("Source", "Where the run came from: CI when a CI system's environment variable "
                                + "(such as CI or BUILD_NUMBER) was present, otherwise LOCAL - typically "
                                + "a developer's machine.",
                        entry.getRunOrigin().getRunSource()),
                // A distributed build spans several machines and names none, so it dashes.
                field("Host", "The machine that ran the tests. A dash for a distributed run, which "
                                + "spans several machines, or when the hostname could not be resolved.",
                        entry.getRunOrigin().getHostName() == null ? "-" : entry.getRunOrigin().getHostName()),
                // A retry of failed tests, credited no savings - see TestRunHistoryEntry#isRerun.
                field("Rerun", "Whether this run was a retry of failed tests, such as by a test-retry "
                                + "plugin, rather than the test task's real run. A rerun is credited no savings.",
                        entry.isRerun() ? "yes" : "no"),
                selectionModeLine(entry),
                field("Suites ran", "Ran: the test suites that executed. Ignored: the suites Tia skipped "
                                + "because no change affected them - skips Tia did not cause, such as "
                                + "@Disabled, are not counted. Failed: the suites with at least one failed test.",
                        entry.getNumSuitesRan() + ", ignored: " + entry.getNumSuitesIgnored()
                                + ", failed: " + entry.getNumSuitesFailed()),
                field("Wall clock", "How long the tests took end to end. For a distributed run, the "
                                + "slowest group's duration.",
                        ReportUtils.prettyDuration(entry.getRunWallClockMs(), true)),
                field("Serial duration", "The total test time across every machine, as if one machine "
                                + "had run all the groups. Equal to the wall clock for a single-host run.",
                        ReportUtils.prettyDuration(entry.getDurationMs(), true)),
                field("Groups used", "How many groups the distributed run's selected tests were split "
                                + "across. A dash for a single-host run.",
                        counterOrDash(entry.getGroupCount())),
                field("Groups available", "How many groups the distributed build had available - more "
                                + "than were used when the selected tests needed fewer. A dash for a "
                                + "single-host run.",
                        counterOrDash(entry.getGroupsAvailable())),
                field("Wall-clock savings", "The time a developer waiting on the build saved: the "
                                + "all-tests run time spread across the groups available, minus this "
                                + "run's wall clock. Fixed when the run was recorded; none for a run of "
                                + "every test or a rerun.",
                        ReportUtils.savingsText(entry.getWallClockSavingsMs(),
                                entry.getWallClockSavingsPercent())),
                field("Serial savings", "The total machine time saved: the all-tests run time minus "
                                + "this run's serial duration. Fixed when the run was recorded; none for "
                                + "a run of every test or a rerun. Equal to the wall-clock savings for a "
                                + "single-host run.",
                        ReportUtils.savingsText(entry.getTimeSavingsMs(), entry.getSavingsPercent()))
        );
    }

    /**
     * Build the five-counter "selection sources" list: the scalar counts for the selection
     * sources that are not broken down into individual triggers. Each counter renders {@code "-"}
     * when its {@link Integer} field is null (not recorded for this row). A run that executed
     * every test shows one line saying why instead - see {@link ReportUtils#selectionOverrideNote}.
     * Each counter's label carries a hover tooltip explaining what it counts.
     *
     * @param entry the history row this page describes
     * @return the selection-sources block content
     */
    private DomContent buildSelectionSourcesBlock(TestRunHistoryEntry entry) {
        String overrideNote = ReportUtils.selectionOverrideNote(entry);
        if (overrideNote != null) {
            return p(span(overrideNote));
        }
        return p(
                field("Modified test files", "Test suites selected because their test file changed "
                                + "since the commit the mapping was recorded against.",
                        counterOrDash(entry.getNumModifiedTestFiles())),
                field("New test files", "Test suites selected because their test file was added "
                                + "since the commit the mapping was recorded against.",
                        counterOrDash(entry.getNumNewTestFiles())),
                field("Previously-failed", "Test suites selected because they failed in an earlier "
                                + "run and have not passed since.",
                        counterOrDash(entry.getNumPreviouslyFailed())),
                field("Unsealed-mapping", "Test suites selected because their stored mapping was "
                                + "written by a run that did not complete, so it is re-captured "
                                + "against this commit.",
                        counterOrDash(entry.getNumUnsealedMapping())),
                field("Pending library", "Test suites selected because they cover library methods "
                                + "changed in a library build this project now uses.",
                        counterOrDash(entry.getNumPendingLibrary()))
        );
    }

    /**
     * Build the "Selection:" line, shown only for a seed or forced run - see
     * {@link ReportUtils#selectionModeLabel}.
     *
     * @param entry the history row this page describes
     * @return the line followed by a break, or empty text for a selective run
     */
    private DomContent selectionModeLine(TestRunHistoryEntry entry) {
        String label = ReportUtils.selectionModeLabel(entry);
        return label == null ? text("") : field("Selection", "Why every test ran. Seed: there was no "
                        + "stored mapping yet, so all tests ran to build it. Forced: tiaSelectAllTests "
                        + "overrode selection. Re-seed: tiaReseed ran all tests and rebuilt the mapping "
                        + "from scratch.",
                label);
    }

    /**
     * Render one plain-text summary field - see {@link #field(String, String, DomContent)}.
     *
     * @param label the field name
     * @param hint what the field means, shown as the label's hover tooltip
     * @param value the field's value
     * @return the field line followed by a break
     */
    private static DomContent field(String label, String hint, String value) {
        return field(label, hint, text(value));
    }

    /**
     * Render one summary field as {@code "<label>: <value>"} followed by a line break, the label
     * carrying a hover tooltip that explains the field, so the page stays compact while each
     * figure is explained where the reader is looking.
     *
     * @param label the field name
     * @param hint what the field means, shown as the label's hover tooltip
     * @param value the field's rendered value
     * @return the field line followed by a break
     */
    private static DomContent field(String label, String hint, DomContent value) {
        return each(span(HtmlLayout.hinted(label, hint), text(": "), value), br());
    }

    /**
     * Render one nullable count - a selection-source counter or a distributed group count - or a
     * dash when it was not recorded or does not apply.
     *
     * @param count the count; null when not recorded or not applicable
     * @return {@code count} as a string, or {@code "-"} when null
     */
    private String counterOrDash(Integer count) {
        return count == null ? "-" : count.toString();
    }

    /**
     * Build the two ranked trigger tables: source-method changes and static rules, each name
     * paired with the number of test suites it accounts for.
     *
     * @param sourceMethodTriggers the source-method triggers, already sorted by count descending
     * @param staticRuleTriggers the static-rule triggers, already sorted by count descending
     * @return the two tables' content
     */
    private DomContent buildTriggerTables(List<TestRunTrigger> sourceMethodTriggers,
                                          List<TestRunTrigger> staticRuleTriggers) {
        List<DomContent> content = new ArrayList<>();
        content.add(HtmlLayout.sectionHeading(HtmlLayout.ICON_CODE, "Source method changes"));
        content.add(buildTriggerTable("sourceMethodTriggers", "Method", sourceMethodTriggers));
        content.add(HtmlLayout.sectionHeading(HtmlLayout.ICON_STATS, "Static rules"));
        content.add(buildTriggerTable("staticRuleTriggers", "Rule", staticRuleTriggers));
        return each(content, c -> c);
    }

    /**
     * Build one ranked, sortable trigger table: name and test count columns.
     *
     * @param tableId the element id used both for the {@code <table>} and its simple-datatables
     *                selector
     * @param nameColumnHeading the heading for the name column ("Method" or "Rule")
     * @param triggers the triggers to render, already sorted by count descending
     * @return the {@code <table>} content
     */
    private DomContent buildTriggerTable(String tableId, String nameColumnHeading,
                                         List<TestRunTrigger> triggers) {
        return table(attrs("#" + tableId),
                thead(tr(th(nameColumnHeading), th("Test count").attr("data-type=\"number\""))),
                tbody(each(triggers, trigger -> tr(
                        td(text(trigger.getName())),
                        td(String.valueOf(trigger.getTestCount()))
                )))
        );
    }

    /**
     * Build the simple-datatables init scripts for both trigger tables, each sorted by its test
     * count column (index 1) descending so the biggest contributors lead.
     *
     * @return the init script tags for both tables
     */
    private DomContent buildTriggerTablesInit() {
        List<DomContent> scripts = new ArrayList<>();
        scripts.add(HtmlLayout.simpleDatatablesInit("#sourceMethodTriggers", ASSETS_REL, 1, "desc"));
        scripts.add(HtmlLayout.simpleDatatablesInit("#staticRuleTriggers", ASSETS_REL, 1, "desc"));
        return each(scripts, s -> s);
    }

    /**
     * First 8 characters of a value, used to keep the breadcrumb's current-page label compact.
     * The full value is available on the crumb's hover title.
     *
     * @param value the source value; may be null defensively
     * @return the first 8 characters of {@code value}, or the whole value if shorter, or
     *         {@code ""} when {@code value} is null
     */
    private static String firstEightChars(String value) {
        if (value == null) return "";
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    /**
     * Create the history output directory if it does not already exist, logging a warning when
     * the directory cannot be created rather than failing the report generation.
     */
    private void createOutputDir() {
        if (!reportOutputDir.exists() && !reportOutputDir.mkdirs()) {
            log.warn("Failed to create report output directory: {}", reportOutputDir.getAbsolutePath());
        }
    }
}
