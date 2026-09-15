package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Source Methods index feeds its rows to simple-datatables via the constructor
 * {@code data} option (empty DOM {@code <tbody>}), which is the scaling fix for tables holding
 * tens of thousands of methods, and that the emitted row data is correctly HTML- and
 * script-escaped.
 */
class HtmlSourceMethodReportDataOptionTest {

    /**
     * Generate the Source Methods report for a method whose short name contains angle brackets
     * (a constructor, {@code <init>}) and assert: the DOM table body is empty, the rows are wired
     * through the {@code data} option, the anchor markup is unicode-escaped so it cannot break out
     * of the {@code <script>}, and no raw {@code <init>} / DOM row leaks into the page.
     */
    @Test
    void feedsRowsViaDataOptionWithEscapedAnchor(@TempDir Path tempDir) throws IOException {
        // given
        TiaData tiaData = buildTiaDataWithOneConstructorMethod();

        // when
        new HtmlSourceMethodReport("branch", tempDir.toFile()).generateSourceMethodReport(tiaData);

        // then
        File methodsList = new File(tempDir.toFile(), "html/branch/methods/tia-source-methods.html");
        assertTrue(methodsList.isFile(), "source-methods index not generated");
        String html = read(methodsList);

        // rows come from the data option, not the DOM body
        assertTrue(html.contains("<tbody></tbody>"), "DOM table body should be empty");
        assertTrue(html.contains("new simpleDatatables.DataTable(\"#tiaSourceMethodsTable\""),
                "should init simple-datatables on the source-methods table");
        assertTrue(html.contains("data: { data: ["), "rows should be supplied via the data option");
        assertTrue(html.contains("type: \"number\""), "metric columns should be typed numeric");

        // the link target still points at the per-method drill-down page
        assertTrue(html.contains("42.html"), "row should link to the per-method page");
        // and the row carries the metric values [numSuites, lineStart, lineEnd]
        assertTrue(html.contains("1,10,20]"), "row should carry the method's metric values");

        // the anchor markup is unicode-escaped in the script (cannot terminate <script>)
        assertTrue(html.contains("\\u003ca href="), "anchor '<' should be unicode-escaped");
        assertTrue(html.contains("\\u0026lt;init\\u0026gt;"),
                "the '<init>' name should be HTML- then unicode-escaped");

        // no raw anchor row or raw angle-bracketed name leaked into the DOM/script
        assertFalse(html.contains("<a href=\"42.html\""), "no raw anchor should appear in the DOM");
        assertFalse(html.contains("<init>"), "no raw angle-bracketed method name should appear");
    }

    /**
     * Generate the Source Methods report and assert it emits a loading indicator and that the init
     * script defers the (synchronous, main-thread-blocking) {@code DataTable} construction by a
     * frame so the indicator paints first, then hides it once the table is built.
     */
    @Test
    void showsLoadingIndicatorAndDefersConstruction(@TempDir Path tempDir) throws IOException {
        // given
        TiaData tiaData = buildTiaDataWithOneConstructorMethod();

        // when
        new HtmlSourceMethodReport("branch", tempDir.toFile()).generateSourceMethodReport(tiaData);

        // then
        File methodsList = new File(tempDir.toFile(), "html/branch/methods/tia-source-methods.html");
        String html = read(methodsList);

        // the indicator element is present with its spinner and status semantics
        assertTrue(html.contains("id=\"tiaSourceMethodsLoading\""), "loading element id missing");
        assertTrue(html.contains("tia-table-loading"), "loading element class missing");
        assertTrue(html.contains("tia-spinner"), "spinner element missing");
        assertTrue(html.contains("Loading methods"), "loading text missing");
        assertTrue(html.contains("role=\"status\""), "loading element should be a status region");

        // construction is deferred a frame, then the indicator is hidden
        assertTrue(html.contains("getElementById(\"tiaSourceMethodsLoading\")"),
                "init should look up the loading element");
        assertTrue(html.contains("requestAnimationFrame("),
                "construction should be deferred so the indicator paints first");
        assertTrue(html.contains("loading.style.display = \"none\""),
                "the indicator should be hidden after the table is built");

        // the spinner style ships in the bundled stylesheet
        String css = readResource("/report/assets/css/tia.css");
        assertTrue(css.contains(".tia-spinner"), "spinner CSS should be bundled in tia.css");
        assertTrue(css.contains("@keyframes tia-spin"), "spinner keyframes should be bundled");
    }

    /**
     * Verify {@link HtmlSourceMethodReport#appendJsonString(StringBuilder, String)} produces a
     * double-quoted JSON string with the standard escapes and, additionally, unicode-escapes
     * {@code < > &} so the value is safe to inline inside a {@code <script>} element.
     */
    @Test
    void appendJsonStringEscapesForScriptSafety() {
        // given
        String[][] cases = {
                {"abc", "\"abc\""},
                {"a\"b", "\"a\\\"b\""},
                {"a\\b", "\"a\\\\b\""},
                {"<a>&", "\"\\u003ca\\u003e\\u0026\""},
                {"x\ty\nz", "\"x\\ty\\nz\""},
                {"", "\"\""}
        };

        // when / then
        for (String[] c : cases) {
            StringBuilder sb = new StringBuilder();
            HtmlSourceMethodReport.appendJsonString(sb, c[0]);
            assertEquals(c[1], sb.toString(), "escaping differs for input: " + c[0]);
        }
    }

    /**
     * Build a minimal {@link TiaData} with a single tracked constructor method (id 42) covered by
     * one test suite, sufficient to exercise the Source Methods index render path.
     *
     * @return the populated Tia data
     */
    private TiaData buildTiaDataWithOneConstructorMethod() {
        TiaData tiaData = new TiaData();

        Map<Integer, MethodImpactTracker> methods = new LinkedHashMap<>();
        methods.put(42, new MethodImpactTracker("com/example/Foo.<init>(Lcom/example/Bar;)V", 10, 20));
        tiaData.setMethodsTracked(methods);

        TestSuiteTracker suite = new TestSuiteTracker("MyTestSuite");
        suite.setTestStats(new TestStats());
        suite.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker("com/example/Foo.java", Arrays.asList(42))));
        Map<String, TestSuiteTracker> suites = new LinkedHashMap<>();
        suites.put("MyTestSuite", suite);
        tiaData.setTestSuitesTracked(suites);

        return tiaData;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private String readResource(String path) throws IOException {
        try (java.io.InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("resource not found on classpath: " + path);
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
