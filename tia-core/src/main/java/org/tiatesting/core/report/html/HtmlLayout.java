package org.tiatesting.core.report.html;

import j2html.tags.DomContent;
import j2html.tags.specialized.H2Tag;
import j2html.tags.specialized.H3Tag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static j2html.TagCreator.*;

/**
 * Shared page chrome for every TIA HTML report page: {@code <head>}, top navigation bar,
 * breadcrumb, and footer. All pages render through these helpers so styling and navigation
 * stay consistent and CDN/asset paths live in one place.
 *
 * <p>The {@code assetsRel} parameter on most helpers is the relative path from the page
 * back to the bundled-assets directory. Root pages ({@code index.html},
 * {@code source-code.html}) pass {@code "assets"}; one-level-deep pages
 * ({@code test-suites/*.html}, {@code methods/*.html}, {@code libraries/*.html}) pass
 * {@code "../assets"}.
 */
final class HtmlLayout {

    /** Identifies which top-nav entry is highlighted as the current page. */
    enum NavKey { HOME, TEST_SUITES, SOURCE_CODE }

    /** A single breadcrumb crumb. {@code href == null} marks the current page (rendered as text). */
    static final class Crumb {
        final String label;
        final String href;
        final String title;
        Crumb(String label, String href, String title) { this.label = label; this.href = href; this.title = title; }
        static Crumb link(String label, String href) { return new Crumb(label, href, null); }
        static Crumb current(String label) { return new Crumb(label, null, null); }
        static Crumb current(String label, String title) { return new Crumb(label, null, title); }
    }

    private HtmlLayout() {}

    /**
     * Build the {@code <head>} block: page title, Pico classless CSS, simple-datatables CSS,
     * the bespoke {@code tia.css}, and favicon link.
     */
    static DomContent pageHead(String pageTitle, String assetsRel) {
        return head(
                meta().withCharset("UTF-8"),
                meta().withName("viewport").withContent("width=device-width, initial-scale=1"),
                title("Tia — " + pageTitle),
                link().withRel("icon").withType("image/x-icon")
                        .withHref(assetsRel + "/images/tia_favicon.ico"),
                link().withRel("stylesheet").withType("text/css")
                        .withHref(assetsRel + "/css/pico.classless.min.css"),
                link().withRel("stylesheet").withType("text/css")
                        .withHref(assetsRel + "/css/simple-datatables.css"),
                link().withRel("stylesheet").withType("text/css")
                        .withHref(assetsRel + "/css/tia.css")
        );
    }

    /**
     * Top nav rendered on every page. {@code rootRel} is the relative path to the report root
     * (where {@code index.html} lives) — empty string for root pages, {@code "../"} for
     * one-level-deep pages.
     */
    static DomContent topNav(NavKey active, String assetsRel, String rootRel,
                             int pendingLibraryCount) {
        return div(attrs(".tia-topnav"),
                a(attrs(".tia-brand"),
                        img().withSrc(assetsRel + "/images/tia_logo.png").withAlt("Tia")
                ).withHref(rootRel + "index.html"),
                ul(
                        li(navLink("Home", rootRel + "index.html", active == NavKey.HOME)),
                        li(navLink("Test Suites", rootRel + "test-suites/tia-test-suites.html",
                                active == NavKey.TEST_SUITES)),
                        li(navLinkWithBadge("Source Code", rootRel + "source-code.html",
                                active == NavKey.SOURCE_CODE, pendingLibraryCount))
                )
        );
    }

    private static DomContent navLink(String label, String href, boolean active) {
        return active
                ? a(label).withHref(href).attr("aria-current", "page")
                : a(label).withHref(href);
    }

    private static DomContent navLinkWithBadge(String label, String href, boolean active, int badgeCount) {
        if (badgeCount <= 0) {
            return navLink(label, href, active);
        }
        DomContent badge = span(String.valueOf(badgeCount)).withClass("tia-badge");
        return active
                ? a(text(label), badge).withHref(href).attr("aria-current", "page")
                : a(text(label), badge).withHref(href);
    }

    /**
     * Breadcrumb trail for non-root pages. Pass crumbs in trail order; the last one should
     * usually be a {@link Crumb#current} (rendered as plain text). Returns empty content for
     * empty input so root pages can call {@code breadcrumb()} unconditionally.
     */
    static DomContent breadcrumb(Crumb... crumbs) {
        if (crumbs == null || crumbs.length == 0) {
            return text("");
        }
        List<DomContent> items = new ArrayList<>(crumbs.length);
        for (Crumb c : crumbs) {
            if (c.href != null) {
                items.add(li(a(c.label).withHref(c.href)));
            } else if (c.title != null) {
                items.add(li(c.label).attr("title", c.title));
            } else {
                items.add(li(c.label));
            }
        }
        return nav(attrs(".tia-breadcrumb"), ol(each(items, item -> item)))
                .attr("aria-label", "breadcrumb");
    }

    static DomContent pageFooter() {
        return footer(attrs(".tia-footer"),
                small(text("Generated by "), a("Tia").withHref("https://github.com/mtgleeson/tiatesting"))
        );
    }

    // Icon SVGs sourced from Bootstrap Icons (https://icons.getbootstrap.com/), MIT licensed —
    // © 2019-2024 The Bootstrap Authors. Inlined to avoid extra asset/CDN dependencies.
    private static final String SVG_ICON_OPEN =
            "<svg class=\"tia-section-icon\" xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\""
                    + " viewBox=\"0 0 16 16\" fill=\"currentColor\" aria-hidden=\"true\">";
    private static final String SVG_ICON_CLOSE = "</svg>";

    /** Bootstrap Icons "database" — Tia DB section on the summary page. */
    static final String ICON_DB = SVG_ICON_OPEN
            + "<path d=\"M4.318 2.687C5.234 2.271 6.536 2 8 2s2.766.27 3.682.687C12.644 3.125 13 3.627 13 4c0 .374-.356.875-1.318 1.313C10.766 5.729 9.464 6 8 6s-2.766-.27-3.682-.687C3.356 4.875 3 4.373 3 4c0-.374.356-.875 1.318-1.313M13 5.698V7c0 .374-.356.875-1.318 1.313C10.766 8.729 9.464 9 8 9s-2.766-.27-3.682-.687C3.356 7.875 3 7.373 3 7V5.698c.271.202.58.378.904.525C4.978 6.711 6.427 7 8 7s3.022-.289 4.096-.777A5 5 0 0 0 13 5.698M14 4c0-1.007-.875-1.755-1.904-2.223C11.022 1.289 9.573 1 8 1s-3.022.289-4.096.777C2.875 2.245 2 2.993 2 4v9c0 1.007.875 1.755 1.904 2.223C4.978 15.71 6.427 16 8 16s3.022-.289 4.096-.777C13.125 14.755 14 14.007 14 13zm-1 4.698V10c0 .374-.356.875-1.318 1.313C10.766 11.729 9.464 12 8 12s-2.766-.27-3.682-.687C3.356 10.875 3 10.373 3 10V8.698c.271.202.58.378.904.525C4.978 9.711 6.427 10 8 10s3.022-.289 4.096-.777A5 5 0 0 0 13 8.698m0 3V13c0 .374-.356.875-1.318 1.313C10.766 14.729 9.464 15 8 15s-2.766-.27-3.682-.687C3.356 13.875 3 13.373 3 13v-1.302c.271.202.58.378.904.525C4.978 12.711 6.427 13 8 13s3.022-.289 4.096-.777c.324-.147.633-.323.904-.525\"/>"
            + SVG_ICON_CLOSE;

    /** Bootstrap Icons "bar-chart" — Stats section. */
    static final String ICON_STATS = SVG_ICON_OPEN
            + "<path d=\"M4 11H2v3h2zm5-4H7v7h2zm5-5v12h-2V2zm-2-1a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1V2a1 1 0 0 0-1-1zM6 7a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1zm-5 4a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H2a1 1 0 0 1-1-1z\"/>"
            + SVG_ICON_CLOSE;

    /** Bootstrap Icons "exclamation-triangle" — Pending Failed Tests section. */
    static final String ICON_FAILED = SVG_ICON_OPEN
            + "<path d=\"M7.938 2.016A.13.13 0 0 1 8.002 2a.13.13 0 0 1 .063.016.15.15 0 0 1 .054.057l6.857 11.667c.036.06.035.124.002.183a.2.2 0 0 1-.054.06.1.1 0 0 1-.066.017H1.146a.1.1 0 0 1-.066-.017.2.2 0 0 1-.054-.06.18.18 0 0 1 .002-.183L7.884 2.073a.15.15 0 0 1 .054-.057m1.044-.45a1.13 1.13 0 0 0-1.96 0L.165 13.233c-.457.778.091 1.767.98 1.767h13.713c.889 0 1.438-.99.98-1.767z\"/>"
            + "<path d=\"M7.002 12a1 1 0 1 1 2 0 1 1 0 0 1-2 0M7.1 5.995a.905.905 0 1 1 1.8 0l-.35 3.507a.552.552 0 0 1-1.1 0z\"/>"
            + SVG_ICON_CLOSE;

    /** Bootstrap Icons "box-seam" — library / pending-library sections. */
    static final String ICON_LIBRARY = SVG_ICON_OPEN
            + "<path d=\"M8.186 1.113a.5.5 0 0 0-.372 0L1.846 3.5l2.404.961L10.404 2zm3.564 1.426L5.596 5 8 5.961 14.154 3.5zm3.25 1.7-6.5 2.6v7.922l6.5-2.6V4.24zM7.5 14.762V6.838L1 4.239v7.923zM7.443.184a1.5 1.5 0 0 1 1.114 0l7.129 2.852A.5.5 0 0 1 16 3.5v8.662a1 1 0 0 1-.629.928l-7.185 2.874a.5.5 0 0 1-.372 0L.63 13.09a1 1 0 0 1-.63-.928V3.5a.5.5 0 0 1 .314-.464z\"/>"
            + SVG_ICON_CLOSE;

    /** Bootstrap Icons "code-slash" — source-method pages. */
    static final String ICON_CODE = SVG_ICON_OPEN
            + "<path d=\"M10.478 1.647a.5.5 0 1 0-.956-.294l-4 13a.5.5 0 0 0 .956.294zM4.854 4.146a.5.5 0 0 1 0 .708L1.707 8l3.147 3.146a.5.5 0 0 1-.708.708l-3.5-3.5a.5.5 0 0 1 0-.708l3.5-3.5a.5.5 0 0 1 .708 0m6.292 0a.5.5 0 0 0 0 .708L14.293 8l-3.147 3.146a.5.5 0 0 0 .708.708l3.5-3.5a.5.5 0 0 0 0-.708l-3.5-3.5a.5.5 0 0 0-.708 0\"/>"
            + SVG_ICON_CLOSE;

    /** Bootstrap Icons "clipboard-check" — test-suite pages. */
    static final String ICON_TEST_SUITE = SVG_ICON_OPEN
            + "<path fill-rule=\"evenodd\" d=\"M10.854 7.146a.5.5 0 0 1 0 .708l-3 3a.5.5 0 0 1-.708 0l-1.5-1.5a.5.5 0 1 1 .708-.708L7.5 9.793l2.646-2.647a.5.5 0 0 1 .708 0\"/>"
            + "<path d=\"M4 1.5H3a2 2 0 0 0-2 2V14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V3.5a2 2 0 0 0-2-2h-1v1h1a1 1 0 0 1 1 1V14a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V3.5a1 1 0 0 1 1-1h1z\"/>"
            + "<path d=\"M9.5 1a.5.5 0 0 1 .5.5v1a.5.5 0 0 1-.5.5h-3a.5.5 0 0 1-.5-.5v-1a.5.5 0 0 1 .5-.5zm-3-1A1.5 1.5 0 0 0 5 1.5v1A1.5 1.5 0 0 0 6.5 4h3A1.5 1.5 0 0 0 11 2.5v-1A1.5 1.5 0 0 0 9.5 0z\"/>"
            + SVG_ICON_CLOSE;

    /** Bootstrap Icons "file-earmark-code" — source-code landing page. */
    static final String ICON_SOURCE_CODE = SVG_ICON_OPEN
            + "<path d=\"M6.646 5.646a.5.5 0 1 1 .708.708L5.707 8l1.647 1.646a.5.5 0 0 1-.708.708l-2-2a.5.5 0 0 1 0-.708zm2.708 0 2 2a.5.5 0 0 1 0 .708l-2 2a.5.5 0 0 1-.708-.708L10.293 8 8.646 6.354a.5.5 0 1 1 .708-.708\"/>"
            + "<path d=\"M14 4.5V14a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V2a2 2 0 0 1 2-2h5.5zm-3 0A1.5 1.5 0 0 1 9.5 3V1H4a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1V4.5z\"/>"
            + SVG_ICON_CLOSE;

    /** Page heading (h2) with an inline SVG icon to its left. */
    static H2Tag pageHeading(String iconSvg, String label) {
        return h2(rawHtml(iconSvg), text(label)).withClass("tia-section-heading");
    }

    /** Section heading (h3) with an inline SVG icon to its left. */
    static H3Tag sectionHeading(String iconSvg, String label) {
        return h3(rawHtml(iconSvg), text(label)).withClass("tia-section-heading");
    }

    /**
     * Inline init for simple-datatables. Kept inline (rather than in a separate JS file) because
     * each page selects a different table id and the config differs minimally — avoiding a JS
     * file per page keeps the report directory tidy.
     */
    static DomContent simpleDatatablesInit(String tableSelector, String assetsRel) {
        return joinScripts(
                script().withSrc(assetsRel + "/js/simple-datatables.min.js"),
                script(rawHtml("const dataTable = new simpleDatatables.DataTable(\"" + tableSelector + "\", {\n" +
                        "\tcolumns: [{ select: 0, sort: \"asc\" }],\n" +
                        "\tsearchable: true,\n" +
                        "\tfixedHeight: true,\n" +
                        "\tpaging: true,\n" +
                        "\tperPage: 20,\n" +
                        "\tperPageSelect: [10, 20, 50, [\"All\", -1]]\n" +
                        "})"))
        );
    }

    private static DomContent joinScripts(DomContent... scripts) {
        return each(Arrays.asList(scripts), s -> s);
    }
}
