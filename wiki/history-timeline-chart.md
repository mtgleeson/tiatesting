# History timeline chart

The History page opens with a bar chart of recent run wall clocks, sitting directly above the
history table. Where the table is built for looking a run up, the chart is built for reading the
trend at a glance: how long recent runs took, and which of them failed. It is rendered by
`HtmlHistoryTimeline` and dropped into `history/tia-history.html` by `HtmlHistoryReport`, just
before the table.

### What a bar shows

One bar per run, evenly spaced, oldest on the left and newest on the right. The spacing is
categorical - one slot per run, not a real time axis - so several runs on the same day sit side by
side and a quiet week leaves no gap. Reading left-to-right is therefore "in order of run", not "in
proportion to elapsed time".

- **Height is the run's wall clock** (`TestRunHistoryEntry.getRunWallClockMs()`) - the same
  figure the table's Wall clock column shows: `duration_ms` for a single-host run, the slowest
  group for a distributed build. The chart reads in the same end-to-end terms as the table. See
  ["Reporting: two durations, one history row"](distributed-test-runs.md#reporting-two-durations-one-history-row).
- **Colour is pass/fail** - green when the run had no failed suites, red when `num_suites_failed`
  is greater than zero. This is the one thing the eye should catch without hovering.
- **A bar is a link.** Each bar is a real SVG anchor pointing at that run's `history/<id>.html`
  detail page - the same page the table's Id cell links to (see
  [Run history details](run-history-details.md)). Being an anchor rather than a scripted click
  handler means it is keyboard-focusable and "open in new tab" works; each carries an `aria-label`
  summarising the run for screen readers.
- **Hover or focus shows a tooltip** with the run's local date/time, its wall clock, its
  wall-clock savings percentage, and the short id. The date is localized in the viewer's timezone
  with the same options as the table's timestamps, and the wall clock is formatted the same way as
  the Wall clock column, so the chart and the table never disagree on how a run reads.

### Default window and "Show 10 more"

A wall of thin bars is unreadable, so the chart shows the **most recent 20 runs** by default. A
"Show 10 more" control below it reveals ten additional older runs per click (20, then 30, then
40 ...); it disappears once every embedded run is shown, and is absent entirely when there are 20
or fewer runs. A caption states what is in view ("Showing last 20 of 47 runs"). Each time the
window grows the y-scale and bar widths recompute for the runs then visible, so taller runs never
clip and more bars simply get thinner.

The full history always remains in the table below - the chart is a recent-trend view, not the
system of record - so the embedded data is capped at the **200 most-recent runs**
(`MAX_TIMELINE_RUNS`) to bound the page size. Nobody reveals ten-at-a-time past that by hand, and
anyone who wants the whole history reads the table.

### Why a hand-rolled inline SVG, and no charting library

The chart is drawn as inline SVG built by a small page script, not by a bundled charting library.
For a single bar chart that is the lighter option once integration is counted: it adds nothing to
the report's asset bundle, keeps the report working offline in a CI artifact archive (the same
no-CDN constraint that has the report inline its own SVG icons), and lets each bar be a real anchor
so click-through and keyboard focus come for free. A library would have to be bundled, licence-
tracked, and wired up to reproduce behaviour the SVG gets natively.

### How it is rendered

The Java side (`HtmlHistoryTimeline`) does only data preparation, and is unit-tested as such:

- `selectTimelineRuns` sorts the history oldest-first and keeps the most recent
  `MAX_TIMELINE_RUNS`.
- `buildRunsJson` serialises those runs to a compact, `<script>`-safe JSON array - one small
  object per run carrying `id`, `t` (timestamp), `d` (wall clock), `s` (wall-clock savings percent) and `f`
  (1 when the run had any failed suite, else 0). String values go through
  `ScriptSafeJson.appendString`, which unicode-escapes `< > &` so the embedded data can never
  terminate the surrounding `<script>` element - the same helper (and the same reason) the Source
  Methods index uses to embed its rows.

The page script then builds the SVG from that array on load and rebuilds it on "Show 10 more" and
on a debounced window resize. Embedding the data once and drawing client-side mirrors how the
Source Methods table feeds its rows through `simple-datatables`' data option. Duration labels on
the y-axis and in the tooltip are formatted by a small JavaScript port of
`ReportUtils.prettyDuration(ms, true)` (whole units, dropping milliseconds at or above a second),
so they match the table exactly. The y-axis ticks fall on round durations: the step is the
smallest round time (1s, 2s, 5s, 10s, 15s, 30s, 1m, 2m, 5m, 10m, 15m, 30m, 1h, 2h, 3h, 6h, 12h,
1d, with 1/2/5 ms steps below a second and 1/2/5 x 10^n days above a day) that covers the tallest
visible bar in at most five intervals, and the axis top is rounded up to the next tick - so a
2.5h run reads `0, 30m, 1h, 1h 30m, 2h, 2h 30m` rather than `41m 40s, 1h 23m 20s ...`. The left gutter is sized to
the widest tick label so multi-unit labels never clip.

The chart is a progressive enhancement. With JavaScript disabled it simply does not draw, and the
full table below is the fallback - the same posture the report already takes for its
`simple-datatables` tables.

### Performance

All of this is on the report-generation path (`generateReport`), never on the hot `select-tests`
read path. The Java work is one sort plus a bounded (at most 200) JSON build, and the client work
is drawing an SVG for at most a few dozen visible bars, so the chart adds no measurable cost to a
build.


---

Prev: [Test-run history log](test-run-history.md) | [Back to the Wiki index](../WIKI.md) | Next: [Run history details](run-history-details.md)
