package org.tiatesting.core.report.html;

import j2html.tags.DomContent;

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
        Crumb(String label, String href) { this.label = label; this.href = href; }
        static Crumb link(String label, String href) { return new Crumb(label, href); }
        static Crumb current(String label) { return new Crumb(label, null); }
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
            items.add(c.href != null ? li(a(c.label).withHref(c.href)) : li(c.label));
        }
        return nav(attrs(".tia-breadcrumb"), ol(each(items, item -> item)))
                .attr("aria-label", "breadcrumb");
    }

    static DomContent pageFooter() {
        return footer(attrs(".tia-footer"),
                small(text("Generated by "), a("Tia").withHref("https://github.com/mtgleeson/tiatesting"))
        );
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
