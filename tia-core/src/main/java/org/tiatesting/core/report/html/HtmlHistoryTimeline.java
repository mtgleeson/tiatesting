package org.tiatesting.core.report.html;

import j2html.tags.DomContent;
import org.tiatesting.core.model.TestRunHistoryEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static j2html.TagCreator.attrs;
import static j2html.TagCreator.button;
import static j2html.TagCreator.div;
import static j2html.TagCreator.rawHtml;
import static j2html.TagCreator.script;
import static j2html.TagCreator.span;
import static j2html.TagCreator.text;

/**
 * Builds the History page's timeline chart - a hand-rolled inline-SVG bar chart of recent
 * test-run durations shown above the history table. This class owns the chart's data preparation:
 * selecting and ordering the runs to embed and serialising them to a compact, script-safe JSON
 * array that the page's inline rendering script draws from. The markup and rendering script are
 * added by {@link HtmlHistoryReport}.
 *
 * <p>Bars read oldest-to-newest, left to right; bar height is the run's {@code durationMs} (the
 * same figure as the table's Duration column) and bar colour is decided by whether the run had any
 * failed suites. Each bar links to the run's existing {@code history/<id>.html} detail page.
 *
 * <p>See the "History timeline chart" chapter in {@code WIKI.md}.
 */
final class HtmlHistoryTimeline {

    /**
     * Upper bound on the number of runs embedded in the page as chart data. The chart defaults to
     * showing the most recent {@link #DEFAULT_VISIBLE} and reveals more on demand; this cap bounds
     * the embedded JSON's size while staying far beyond what anyone reveals by hand. The full
     * history remains available in the table below the chart.
     */
    static final int MAX_TIMELINE_RUNS = 200;

    /** Number of most-recent runs the chart shows before the viewer asks for more. */
    static final int DEFAULT_VISIBLE = 20;

    /** Number of additional older runs each "show more" step reveals. */
    static final int SHOW_MORE_STEP = 10;

    private HtmlHistoryTimeline() {}

    /**
     * Build the timeline chart block for the History page: the heading, the chart host the inline
     * script draws the SVG into, the pass/fail legend, the caption and "show more" control, the
     * hover tooltip element, and the inline rendering script carrying the embedded run data. When
     * there is no history the chart is omitted entirely (returns empty content) so the page falls
     * straight through to its table.
     *
     * @param history the full run history; the chart shows the most recent {@link #MAX_TIMELINE_RUNS}
     * @return the chart block, or empty content when there are no runs to plot
     */
    static DomContent render(List<TestRunHistoryEntry> history) {
        List<TestRunHistoryEntry> runs = selectTimelineRuns(history);
        if (runs.isEmpty()) {
            return text("");
        }
        String runsJson = buildRunsJson(runs);
        return div(attrs(".tia-timeline"),
                HtmlLayout.sectionHeading(HtmlLayout.ICON_STATS, "Run duration timeline"),
                div(attrs("#tiaTimelineChart.tia-timeline-chart")),
                div(attrs(".tia-timeline-legend"),
                        span(span(attrs(".swatch.pass")), text("Passed")),
                        span(span(attrs(".swatch.fail")), text("Failed"))
                ),
                div(attrs(".tia-timeline-controls"),
                        span(attrs("#tiaTimelineCaption.tia-timeline-caption")),
                        button(text("Show " + SHOW_MORE_STEP + " more"))
                                .withId("tiaTimelineMore").withClass("outline secondary")
                                .withType("button")
                ),
                div(attrs("#tiaTimelineTip.tia-timeline-tooltip")),
                script(rawHtml(buildScript(runsJson)))
        );
    }

    /**
     * Build the inline JavaScript that draws the timeline SVG from the embedded run data and wires
     * its interactions: hover/focus tooltip, click-through to each run's detail page (each bar is
     * an SVG anchor), the "show more" step, and a debounced redraw on resize. The chart shows the
     * most recent {@link #DEFAULT_VISIBLE} runs first and reveals {@link #SHOW_MORE_STEP} more per
     * click. Durations are formatted to match {@code ReportUtils.prettyDuration(ms, true)} and
     * timestamps localized with the same options as {@code HtmlLayout.localTimeRenderingScript} so
     * the chart reads identically to the table below it.
     *
     * @param runsJson the script-safe JSON array of runs (oldest-first) from {@link #buildRunsJson}
     * @return the inline script body
     */
    private static String buildScript(String runsJson) {
        return "(function(){\n"
                + "var RUNS=" + runsJson + ";\n"
                + "if(!RUNS.length){return;}\n"
                + "var DEFAULT_VISIBLE=" + DEFAULT_VISIBLE + ",STEP=" + SHOW_MORE_STEP + ";\n"
                + "var visible=Math.min(DEFAULT_VISIBLE,RUNS.length);\n"
                + "var chart=document.getElementById('tiaTimelineChart');\n"
                + "var tip=document.getElementById('tiaTimelineTip');\n"
                + "var caption=document.getElementById('tiaTimelineCaption');\n"
                + "var moreBtn=document.getElementById('tiaTimelineMore');\n"
                + "if(!chart){return;}\n"
                + "function esc(s){return String(s).replace(/[&<>\"]/g,function(c){"
                + "return c==='&'?'&amp;':c==='<'?'&lt;':c==='>'?'&gt;':'&quot;';});}\n"
                + "function pretty(ms){ms=Math.round(ms);if(ms<=0){return '0';}"
                + "var h=Math.floor(ms/3600000),m=Math.floor(ms/60000)%60,s=Math.floor(ms/1000)%60,mil=ms%1000;"
                + "if(ms>=1000){mil=0;}var o=[];if(h){o.push(h+'h');}if(m){o.push(m+'m');}"
                + "if(s){o.push(s+'s');}if(mil){o.push(mil+'ms');}return o.length?o.join(' '):'0';}\n"
                + "function fmtDate(ms){return new Date(ms).toLocaleString(undefined,"
                + "{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'});}\n"
                + "function niceMax(v){if(v<=0){return 1;}var p=Math.pow(10,Math.floor(Math.log10(v)));"
                + "var n=v/p;var st=n<=1?1:n<=2?2:n<=5?5:10;return st*p;}\n"
                + "function build(w,h){\n"
                + "var runs=RUNS.slice(RUNS.length-visible);\n"
                + "var padL=56,padR=16,padT=16,padB=36;\n"
                + "var plotW=Math.max(10,w-padL-padR),plotH=Math.max(10,h-padT-padB);\n"
                + "var maxD=0;for(var i=0;i<runs.length;i++){if(runs[i].d>maxD){maxD=runs[i].d;}}\n"
                + "var yMax=niceMax(maxD);\n"
                + "var n=runs.length,band=plotW/n,barW=Math.min(band*0.62,46);\n"
                + "var s='<svg viewBox=\"0 0 '+w+' '+h+'\" width=\"'+w+'\" height=\"'+h+'\" "
                + "xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">';\n"
                + "var ticks=4;\n"
                + "for(var t=0;t<=ticks;t++){var val=yMax*t/ticks,y=padT+plotH-(val/yMax)*plotH;"
                + "s+='<line class=\"tia-tl-grid\" x1=\"'+padL+'\" y1=\"'+y+'\" x2=\"'+(w-padR)+'\" y2=\"'+y+'\"/>';"
                + "s+='<text class=\"tia-tl-ylabel\" x=\"'+(padL-8)+'\" y=\"'+(y+3)+'\" text-anchor=\"end\">'"
                + "+esc(pretty(val))+'</text>';}\n"
                + "s+='<line class=\"tia-tl-axis\" x1=\"'+padL+'\" y1=\"'+(padT+plotH)+'\" x2=\"'+(w-padR)"
                + "+'\" y2=\"'+(padT+plotH)+'\"/>';\n"
                + "for(var j=0;j<runs.length;j++){var r=runs[j];"
                + "var bx=padL+band*j+(band-barW)/2;var bh=(r.d/yMax)*plotH;if(bh<1){bh=1;}"
                + "var by=padT+plotH-bh;var cls=r.f?'tia-bar fail':'tia-bar pass';var href=esc(r.id)+'.html';"
                + "s+='<a class=\"tia-tl-band\" href=\"'+href+'\" xlink:href=\"'+href+'\" tabindex=\"0\" '"
                + "+'data-t=\"'+r.t+'\" data-d=\"'+r.d+'\" data-s=\"'+r.s+'\" data-id=\"'+esc(r.id)+'\" '"
                + "+'aria-label=\"'+esc(fmtDate(r.t)+', '+pretty(r.d)+(r.s>0?', '+r.s+'% saved':''))+'\">';"
                + "s+='<rect class=\"tia-tl-hit\" x=\"'+(padL+band*j)+'\" y=\"'+padT+'\" width=\"'+band"
                + "+'\" height=\"'+plotH+'\"/>';"
                + "s+='<rect class=\"'+cls+'\" x=\"'+bx+'\" y=\"'+by+'\" width=\"'+barW+'\" height=\"'+bh"
                + "+'\" rx=\"3\"/>';s+='</a>';}\n"
                + "s+='<text class=\"tia-tl-xcap\" x=\"'+(padL+plotW/2)+'\" y=\"'+(h-10)"
                + "+'\" text-anchor=\"middle\">older → newer</text>';\n"
                + "s+='</svg>';return s;}\n"
                + "function showTip(b){var sv=+b.getAttribute('data-s');"
                + "tip.innerHTML='<strong>'+esc(fmtDate(+b.getAttribute('data-t')))+'</strong>'"
                + "+'<div>Duration: '+esc(pretty(+b.getAttribute('data-d')))+'</div>'"
                + "+'<div>Savings: '+(sv>0?sv+'%':'-')+'</div>'"
                + "+'<div class=\"tia-tl-tip-id\">'+esc(String(b.getAttribute('data-id')).slice(0,8))+'</div>';"
                + "tip.style.display='block';}\n"
                + "function moveTip(x,y){var tw=tip.offsetWidth,vw=window.innerWidth;var left=x+14;"
                + "if(left+tw>vw-8){left=x-tw-14;}tip.style.left=left+'px';tip.style.top=(y+14)+'px';}\n"
                + "function hideTip(){tip.style.display='none';}\n"
                + "chart.addEventListener('mouseover',function(e){var b=e.target.closest('.tia-tl-band');"
                + "if(b){showTip(b);}});\n"
                + "chart.addEventListener('mousemove',function(e){if(tip.style.display==='block'){"
                + "moveTip(e.clientX,e.clientY);}});\n"
                + "chart.addEventListener('mouseout',function(e){var b=e.target.closest('.tia-tl-band');"
                + "if(b&&!b.contains(e.relatedTarget)){hideTip();}});\n"
                + "chart.addEventListener('focusin',function(e){var b=e.target.closest('.tia-tl-band');"
                + "if(b){showTip(b);var rc=b.getBoundingClientRect();moveTip(rc.left+rc.width/2,rc.top);}});\n"
                + "chart.addEventListener('focusout',function(e){var b=e.target.closest('.tia-tl-band');"
                + "if(b){hideTip();}});\n"
                + "function draw(){var w=Math.max(320,chart.clientWidth||800);chart.innerHTML=build(w,300);"
                + "if(visible>=RUNS.length){caption.textContent='Showing all '+RUNS.length+' run'"
                + "+(RUNS.length===1?'':'s');}else{caption.textContent='Showing last '+visible+' of '"
                + "+RUNS.length+' runs';}moreBtn.style.display=visible<RUNS.length?'inline-block':'none';"
                + "moreBtn.textContent='Show '+Math.min(STEP,RUNS.length-visible)+' more';}\n"
                + "if(moreBtn){moreBtn.addEventListener('click',function(){"
                + "visible=Math.min(visible+STEP,RUNS.length);draw();});}\n"
                + "var rt;window.addEventListener('resize',function(){clearTimeout(rt);rt=setTimeout(draw,150);});\n"
                + "draw();\n"
                + "})();";
    }

    /**
     * Select and order the runs the chart embeds: the most recent {@link #MAX_TIMELINE_RUNS}
     * entries from the history, returned oldest-first so the chart reads left (oldest) to right
     * (newest). The input is not mutated.
     *
     * @param history the full run history to draw from; may be null or empty
     * @return the chart's runs, oldest-first and capped to the most recent {@link #MAX_TIMELINE_RUNS};
     *         an empty list when {@code history} is null or empty
     */
    static List<TestRunHistoryEntry> selectTimelineRuns(List<TestRunHistoryEntry> history) {
        if (history == null || history.isEmpty()) {
            return new ArrayList<>();
        }
        List<TestRunHistoryEntry> ordered = new ArrayList<>(history);
        ordered.sort(Comparator.comparingLong(TestRunHistoryEntry::getRunTimestampMs));
        if (ordered.size() > MAX_TIMELINE_RUNS) {
            // Keep the newest MAX_TIMELINE_RUNS (the tail of the ascending list); drop the oldest.
            ordered = new ArrayList<>(ordered.subList(ordered.size() - MAX_TIMELINE_RUNS, ordered.size()));
        }
        return ordered;
    }

    /**
     * Serialise the given runs to a compact JSON array literal, safe to embed inside an inline
     * {@code <script>}. Each element carries only what the chart draws: {@code id} (the run's full
     * id, used for the bar's {@code history/<id>.html} link), {@code t} (run timestamp in UTC
     * millis, shown localized on hover), {@code d} ({@code durationMs}, the bar height), {@code s}
     * ({@code savingsPercent}, shown on hover) and {@code f} (1 when the run had any failed suite,
     * else 0, deciding the bar colour). The array preserves the given order.
     *
     * @param runs the runs to serialise, already ordered oldest-first
     * @return a script-safe JSON array-of-objects literal
     */
    static String buildRunsJson(List<TestRunHistoryEntry> runs) {
        StringBuilder sb = new StringBuilder(Math.max(16, runs.size() * 80));
        sb.append('[');
        boolean first = true;
        for (TestRunHistoryEntry run : runs) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":");
            ScriptSafeJson.appendString(sb, run.getId() == null ? "" : run.getId());
            sb.append(",\"t\":").append(run.getRunTimestampMs())
                    .append(",\"d\":").append(run.getDurationMs())
                    .append(",\"s\":").append(run.getSavingsPercent())
                    .append(",\"f\":").append(run.getNumSuitesFailed() > 0 ? 1 : 0)
                    .append('}');
        }
        sb.append(']');
        return sb.toString();
    }
}
