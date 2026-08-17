package org.tiatesting.core.distributed;

import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunStatus;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.report.ReportUtils;
import org.tiatesting.core.report.TextTable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders the state of a distributed test run - the run row, every group, and each group's runner -
 * as the console report behind the Maven {@code dist-status} goal and the Gradle {@code
 * tia-dist-status} task. Read-only: nothing here claims, completes, seals or clears anything, so it
 * is safe to run against a live build from a second terminal or a CI step while runners are still
 * going.
 *
 * <p>Shared by both build tools for the same reason {@link DistributedRunPreviewFormatter} is - the
 * two must not drift on what a distributed run's state is called - and it takes a {@link DataStore}
 * directly, like {@code StatusReportGenerator}, because the report is assembled from four reads that
 * only make sense together.
 *
 * <p>The report is built around the question it exists to answer: <b>why has this run not sealed
 * yet?</b> A run seals only when every group reaches {@code COMPLETED}, and a group completes only
 * once its {@code Observed} count reaches its {@code Assigned} count - see {@link
 * DataStore#completeGroup}. So those two numbers sit side by side in the group table, and the
 * outstanding block below it names each group still standing between the run and its seal. The two
 * states worth calling out by name are a {@code PENDING} group, which means the pipeline fanned out
 * fewer jobs than the plan has groups and no runner will ever take it, and a run whose groups have
 * all completed while the run itself is still {@code OPEN}, which means the seal was attempted and
 * failed.
 *
 * <p>A seed run's group is assigned no suite names at all - the plan has no stored mapping to draw
 * them from and its runner executes everything it discovers - so its assigned count renders as
 * {@code all} rather than {@code 0}. Rendering the raw zero would repeat the mistake the seed-run
 * claim log made: reporting the one run that executes the entire suite as though it had nothing to
 * do.
 *
 * <p>See the "Distributed test runs" chapter in {@code WIKI.md} for the lifecycle this reports on.
 */
public final class DistributedRunStatusReport {

    private static final DateTimeFormatter LOCAL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Rendered in a column whose value does not exist yet, rather than a zero that would read as a measurement. */
    private static final String NOT_APPLICABLE = "-";

    /** Rendered as a seed run's assigned suite count, which is zero names but every suite. */
    private static final String ALL_SUITES = "all";

    private DistributedRunStatusReport() {
    }

    /**
     * Build the full status report for one distributed run.
     *
     * <p>Reads every run held in the branch's plan tables once, rather than fetching the requested
     * run on its own, so that the "no such run" message can name what is actually there instead -
     * the most common cause of a missing run is a later plan write having cleared it, and a user who
     * is told only that their id is absent has no way to see that a newer id took its place.
     *
     * @param dataStore the datastore to read from; opened by the caller against the branch whose
     *                  schema holds the run, and closed by the caller
     * @param runId the run to report on, or null/blank to report on the most recently planned run -
     *              which is normally the only one, since each plan write clears the previous run's
     *              rows
     * @param includeSuiteNames whether to list each group's assigned suite names under the table;
     *                          the names are read either way, since the assigned count comes from
     *                          the same query, so this only controls whether they are printed
     * @param nowMs the UTC epoch millis to measure "ago" and "elapsed" values against; supplied by
     *              the caller rather than read from the clock here so tests can assert the rendered
     *              text exactly
     * @param lineSep the line separator to join lines with
     * @return the multi-line report, or a single explanatory sentence when there is no such run
     */
    public static String format(final DataStore dataStore, final String runId,
                                final boolean includeSuiteNames, final long nowMs,
                                final String lineSep) {
        List<DistributedRun> allRuns = dataStore.readAllDistributedRuns();
        DistributedRun run = selectRun(allRuns, runId);
        if (run == null) {
            return noSuchRunMessage(allRuns, runId, lineSep);
        }

        List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups(run.getRunId());
        List<List<String>> assignedSuites = readAssignedSuites(dataStore, run, groups);

        StringBuilder report = new StringBuilder();
        appendHeader(report, run, groups, nowMs, lineSep);
        report.append(lineSep).append(lineSep);
        appendGroupTable(report, run, groups, assignedSuites, nowMs, lineSep);
        appendOutstanding(report, run, groups, assignedSuites, nowMs, lineSep);
        if (includeSuiteNames) {
            appendSuiteNames(report, run, groups, assignedSuites, lineSep);
        }
        if (allRuns.size() > 1 && isBlank(runId)) {
            report.append(lineSep).append(lineSep)
                    .append("Note: the plan tables hold ").append(allRuns.size())
                    .append(" runs, and this is the most recently planned one. Pass a run id to ")
                    .append("report on one of the others.");
        }
        return report.toString();
    }

    /**
     * Pick the run to report on: the one matching {@code runId} when given, otherwise the most
     * recently planned. Matching by scanning the already-read list rather than by a second targeted
     * read, since the plan tables normally hold at most one row and the caller needs the full list
     * anyway to explain a miss.
     *
     * @param allRuns every run held in the branch's plan tables, most recently created first
     * @param runId the requested run id, or null/blank for the most recently planned run
     * @return the matching run, or null when there is none
     */
    private static DistributedRun selectRun(final List<DistributedRun> allRuns, final String runId) {
        if (allRuns.isEmpty()) {
            return null;
        }
        if (isBlank(runId)) {
            return allRuns.get(0);
        }
        for (DistributedRun run : allRuns) {
            if (runId.equals(run.getRunId())) {
                return run;
            }
        }
        return null;
    }

    /**
     * Explain an empty report: either the branch has no distributed run at all, or it has runs but
     * none under the requested id. The second case names the ids that are present, because the
     * usual cause is a later build's plan write having cleared the requested run's rows and left its
     * own behind - a fact the user cannot deduce from "not found" alone.
     *
     * @param allRuns every run held in the branch's plan tables
     * @param runId the requested run id, or null/blank when none was requested
     * @param lineSep the line separator to join lines with
     * @return the explanatory message
     */
    private static String noSuchRunMessage(final List<DistributedRun> allRuns, final String runId,
                                           final String lineSep) {
        if (allRuns.isEmpty()) {
            return "No distributed run has been planned on this branch. Run the plan step "
                    + "(Maven dist-plan, Gradle tia-dist-plan) to create one.";
        }

        StringBuilder message = new StringBuilder();
        message.append("No distributed run is planned under run id '").append(runId)
                .append("' on this branch. Either that build was superseded by a later plan, whose ")
                .append("write cleared its rows, or the plan step was never run for that id.")
                .append(lineSep).append(lineSep)
                .append("The plan tables currently hold:");
        for (DistributedRun run : allRuns) {
            message.append(lineSep).append("  '").append(run.getRunId()).append("' (")
                    .append(run.getStatus()).append(", ").append(run.getGroupCount())
                    .append(" group(s), commit ").append(run.getCommitValue()).append(")");
        }
        return message.toString();
    }

    /**
     * Read every group's assigned suite names, in group order, so the assigned count in the table
     * and the optional name listing below it come from a single pass. A seed run is skipped: its
     * plan carries no suite names by construction, so the queries would return nothing and the
     * report renders {@code all} for it regardless.
     *
     * @param dataStore the datastore to read from
     * @param run the run being reported on
     * @param groups the run's groups, in group-number order
     * @return one list of suite names per group, positionally matching {@code groups}; every list is
     *         empty for a seed run
     */
    private static List<List<String>> readAssignedSuites(final DataStore dataStore,
                                                          final DistributedRun run,
                                                          final List<DistributedRunGroup> groups) {
        List<List<String>> assigned = new ArrayList<>(groups.size());
        for (DistributedRunGroup group : groups) {
            assigned.add(run.isSeedRun() ? Collections.<String>emptyList()
                    : dataStore.readDistributedRunGroupSuites(run.getRunId(), group.getGroupNumber()));
        }
        return assigned;
    }

    /**
     * Append the run-level block: what the run is, what it was planned against, how far through it
     * is, and whether it sealed.
     *
     * @param report the buffer to append to
     * @param run the run being reported on
     * @param groups the run's groups, in group-number order
     * @param nowMs the epoch millis to measure "ago" against
     * @param lineSep the line separator to join lines with
     */
    private static void appendHeader(final StringBuilder report, final DistributedRun run,
                                     final List<DistributedRunGroup> groups, final long nowMs,
                                     final String lineSep) {
        int completed = countWithStatus(groups, DistributedRunGroupStatus.COMPLETED);

        report.append("Distributed run '").append(run.getRunId()).append("'").append(lineSep);
        report.append("  Branch:     ").append(run.getBranch()).append(lineSep);
        report.append("  Commit:     ").append(run.getCommitValue()).append(lineSep);
        report.append("  Status:     ").append(run.getStatus()).append(" - ").append(completed)
                .append(" of ").append(groups.size()).append(" group(s) completed").append(lineSep);
        report.append("  Planned:    ").append(timestamp(run.getCreatedAtMs()))
                .append(ago(run.getCreatedAtMs(), nowMs)).append(lineSep);
        if (run.isSeedRun()) {
            report.append("  Seed run:   yes - no stored mapping existed for this branch when the ")
                    .append("plan was written, so it has one group carrying no suite names whose ")
                    .append("runner executes every suite it discovers.").append(lineSep);
        }
        report.append("  Target:     ").append(run.getTargetRunTimeMs() == null
                        ? "none (fixed group count)"
                        : ReportUtils.prettyDuration(run.getTargetRunTimeMs().longValue(), true))
                .append(lineSep);
        report.append("  Estimated:  ").append(ReportUtils.prettyDuration(run.getEstimatedTotalMs(), true))
                .append(" of test time across ").append(groups.size()).append(" group(s)").append(lineSep);
        report.append("  Sealed:     ").append(sealedDescription(run, nowMs));
    }

    /**
     * Describe the run's seal: who performed it and when, or that it has not happened. A sealed run
     * also reports how long after the plan the seal landed, which is the build's end-to-end
     * distributed wall clock - the figure a pipeline's job timeout has to accommodate.
     *
     * @param run the run being reported on
     * @param nowMs the epoch millis to measure an unsealed run's age against
     * @return the single-line seal description
     */
    private static String sealedDescription(final DistributedRun run, final long nowMs) {
        if (run.getStatus() != DistributedRunStatus.SEALED || run.getSealedAtMs() == null) {
            long openForMs = nowMs - run.getCreatedAtMs();
            return openForMs <= 0 ? "not sealed"
                    : "not sealed (open for " + ReportUtils.prettyDuration(openForMs, true) + ")";
        }
        return "by runner '" + run.getSealedBy() + "' at " + timestamp(run.getSealedAtMs().longValue())
                + " (" + ReportUtils.prettyDuration(run.getSealedAtMs().longValue() - run.getCreatedAtMs(), true)
                + " after the plan)";
    }

    /**
     * Append the per-group table and the legend that makes its four count columns readable. The
     * columns a group has not reached yet are dashed rather than zeroed: a {@code PENDING} group has
     * reported nothing, and printing zeros for it would be indistinguishable from a runner that took
     * the group and ran nothing.
     *
     * @param report the buffer to append to
     * @param run the run being reported on
     * @param groups the run's groups, in group-number order
     * @param assignedSuites each group's assigned suite names, positionally matching {@code groups}
     * @param nowMs the epoch millis to measure a claimed group's elapsed time against
     * @param lineSep the line separator to join lines with
     */
    private static void appendGroupTable(final StringBuilder report, final DistributedRun run,
                                         final List<DistributedRunGroup> groups,
                                         final List<List<String>> assignedSuites, final long nowMs,
                                         final String lineSep) {
        TextTable table = new TextTable("Group", "Status", "Runner", "Assigned", "Observed", "Ran",
                "Failed", "Estimated", "Actual", "Elapsed");
        for (int i = 0; i < groups.size(); i++) {
            DistributedRunGroup group = groups.get(i);
            boolean reported = group.getStatus() != DistributedRunGroupStatus.PENDING;
            table.addRow(
                    Integer.toString(group.getGroupNumber()),
                    group.getStatus().toString(),
                    group.getRunnerKey() == null ? NOT_APPLICABLE : group.getRunnerKey(),
                    run.isSeedRun() ? ALL_SUITES : Integer.toString(assignedSuites.get(i).size()),
                    reported ? Integer.toString(group.getSuitesObserved()) : NOT_APPLICABLE,
                    reported ? Integer.toString(group.getSuitesRan()) : NOT_APPLICABLE,
                    reported ? Integer.toString(group.getSuitesFailed()) : NOT_APPLICABLE,
                    ReportUtils.prettyDuration(group.getEstimatedMs(), true),
                    group.getActualDurationMs() == null ? NOT_APPLICABLE
                            : ReportUtils.prettyDuration(group.getActualDurationMs().longValue(), true),
                    elapsed(group, nowMs));
        }

        report.append("Groups:").append(lineSep).append(lineSep);
        report.append(table.render(lineSep)).append(lineSep).append(lineSep);
        report.append("  Assigned = suites the plan gave this group; Observed = suites its runner ")
                .append("saw finish or skip.").append(lineSep);
        report.append("  A group completes once Observed reaches Assigned, and the run seals once ")
                .append("every group completes.").append(lineSep);
        report.append("  Actual = measured test-execution time; Elapsed = wall clock since the ")
                .append("group was claimed.");
    }

    /**
     * Render how long a group has been going, or took: for a claimed group the time since the claim,
     * which is the number that says whether a runner is still working or has died; for a completed
     * one the claim-to-completion wall clock, which includes the runner's own JVM startup and so is
     * legitimately longer than its measured test time.
     *
     * @param group the group to describe
     * @param nowMs the epoch millis to measure a claimed group's elapsed time against
     * @return the rendered elapsed time, or {@code -} for a group that was never claimed
     */
    private static String elapsed(final DistributedRunGroup group, final long nowMs) {
        if (group.getClaimedAtMs() == null) {
            return NOT_APPLICABLE;
        }
        long until = group.getCompletedAtMs() != null ? group.getCompletedAtMs().longValue() : nowMs;
        long elapsedMs = until - group.getClaimedAtMs().longValue();
        // Negative only under clock skew between the planning host and this one, which is real on
        // CI. Reporting a negative duration would look like a bug in Tia rather than in the clocks.
        return elapsedMs < 0 ? NOT_APPLICABLE : ReportUtils.prettyDuration(elapsedMs, true);
    }

    /**
     * Append the block naming what still stands between the run and its seal, when anything does.
     * Skipped entirely for a sealed run, which has nothing outstanding by definition.
     *
     * <p>Two cases get named rather than merely listed. A {@code PENDING} group means no runner ever
     * claimed it, so nothing will complete it on its own - the pipeline fanned out fewer jobs than
     * the plan has groups, and the run will stay open until a later plan clears it. A run whose
     * groups have every one completed while the run row is still {@code OPEN} means the completion
     * barrier was reached and the seal itself failed, which is a different problem from a runner
     * still working and is worth saying out loud rather than leaving the reader to infer from an
     * empty outstanding list.
     *
     * @param report the buffer to append to
     * @param run the run being reported on
     * @param groups the run's groups, in group-number order
     * @param assignedSuites each group's assigned suite names, positionally matching {@code groups}
     * @param nowMs the epoch millis to measure a claimed group's elapsed time against
     * @param lineSep the line separator to join lines with
     */
    private static void appendOutstanding(final StringBuilder report, final DistributedRun run,
                                          final List<DistributedRunGroup> groups,
                                          final List<List<String>> assignedSuites, final long nowMs,
                                          final String lineSep) {
        if (run.getStatus() == DistributedRunStatus.SEALED) {
            return;
        }

        int completed = countWithStatus(groups, DistributedRunGroupStatus.COMPLETED);
        report.append(lineSep).append(lineSep);

        if (completed == groups.size()) {
            report.append("Every group has completed but this run is still OPEN - the completion ")
                    .append(lineSep)
                    .append("barrier was reached and the seal did not happen. Nothing further will ")
                    .append("seal it:").append(lineSep)
                    .append("the next build's plan step will clear these rows and redo the run's work.");
            return;
        }

        report.append("This run is not sealed yet. Outstanding:");
        for (int i = 0; i < groups.size(); i++) {
            DistributedRunGroup group = groups.get(i);
            if (group.getStatus() == DistributedRunGroupStatus.COMPLETED) {
                continue;
            }
            report.append(lineSep).append("  Group ").append(group.getGroupNumber()).append(": ");
            if (group.getStatus() == DistributedRunGroupStatus.PENDING) {
                report.append("PENDING - no runner has claimed it. The pipeline fanned out fewer ")
                        .append("jobs than").append(lineSep)
                        .append("    the plan's ").append(groups.size())
                        .append(" group(s), so nothing will ever complete this one and the run ")
                        .append("cannot seal.");
            } else {
                String runningFor = elapsed(group, nowMs);
                report.append("CLAIMED by '").append(group.getRunnerKey()).append("'")
                        .append(NOT_APPLICABLE.equals(runningFor) ? ""
                                : " (running for " + runningFor + ")")
                        .append(" - observed ")
                        .append(group.getSuitesObserved()).append(" of ")
                        .append(run.isSeedRun() ? ALL_SUITES
                                : Integer.toString(assignedSuites.get(i).size()))
                        .append(" assigned suite(s).");
            }
        }
    }

    /**
     * Append each group's assigned suite names, for the caller that asked for them. A group with no
     * names says why it has none, since an empty list means two different things: a seed run's
     * single group deliberately carries none, while an ordinary group with none was given no work by
     * the balancer.
     *
     * @param report the buffer to append to
     * @param run the run being reported on
     * @param groups the run's groups, in group-number order
     * @param assignedSuites each group's assigned suite names, positionally matching {@code groups}
     * @param lineSep the line separator to join lines with
     */
    private static void appendSuiteNames(final StringBuilder report, final DistributedRun run,
                                         final List<DistributedRunGroup> groups,
                                         final List<List<String>> assignedSuites, final String lineSep) {
        report.append(lineSep).append(lineSep).append("Assigned suites:");
        for (int i = 0; i < groups.size(); i++) {
            List<String> suites = assignedSuites.get(i);
            report.append(lineSep).append("  Group ").append(groups.get(i).getGroupNumber());
            if (run.isSeedRun()) {
                report.append(": no suite names - a seed run's group covers every suite its runner "
                        + "discovers.");
            } else if (suites.isEmpty()) {
                report.append(": none - the plan assigned this group no suites.");
            } else {
                report.append(" (").append(suites.size()).append("):");
                for (String suite : suites) {
                    report.append(lineSep).append("    ").append(suite);
                }
            }
        }
    }

    /**
     * Count the groups in one lifecycle state.
     *
     * @param groups the run's groups
     * @param status the state to count
     * @return how many of {@code groups} are in {@code status}
     */
    private static int countWithStatus(final List<DistributedRunGroup> groups,
                                       final DistributedRunGroupStatus status) {
        int count = 0;
        for (DistributedRunGroup group : groups) {
            if (group.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /**
     * Render a UTC epoch millis value in the JVM's local time zone, matching the format the history
     * report uses so timestamps read the same across Tia's console output.
     *
     * @param epochMs the UTC epoch millis to render
     * @return the local date and time, to the second
     */
    private static String timestamp(final long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(LOCAL_DATE_TIME);
    }

    /**
     * Render the parenthesised "(x ago)" suffix for a past timestamp, or nothing at all when the
     * timestamp is not in the past - which happens under clock skew between the planning host and
     * this one, and is better rendered as no suffix than as a negative age.
     *
     * @param epochMs the UTC epoch millis the age is measured from
     * @param nowMs the UTC epoch millis to measure against
     * @return the suffix, including its leading space, or an empty string
     */
    private static String ago(final long epochMs, final long nowMs) {
        long ageMs = nowMs - epochMs;
        return ageMs <= 0 ? "" : " (" + ReportUtils.prettyDuration(ageMs, true) + " ago)";
    }

    /**
     * Report whether a configured run id was effectively left unset.
     *
     * @param value the value to test
     * @return true when null, empty, or whitespace only
     */
    private static boolean isBlank(final String value) {
        return value == null || value.trim().isEmpty();
    }
}
