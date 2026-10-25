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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the HTML reports tolerate a suite whose edges name a method that is not in the method
 * catalogue. A distributed runner writes its suites' edges, flagged unsealed, before the seal adds
 * newly seen methods to {@code tia_source_method}, so a report generated in that window (or after
 * a run that never sealed) sees edges to uncatalogued method ids.
 */
class HtmlReportUncataloguedMethodTest {

    private static final int CATALOGUED_METHOD_ID = 1;
    private static final int UNCATALOGUED_METHOD_ID = 2;

    @Test
    void reportsSkipMethodsMissingFromTheCatalogue(@TempDir Path tempDir) throws IOException {
        // given a suite covering one catalogued and one uncatalogued method
        TiaData tiaData = buildTiaDataWithUncataloguedEdge();
        File reportRoot = tempDir.toFile();

        // when
        new HtmlReportGenerator("uncatalogued", reportRoot, null).generateReports(tiaData);

        // then the suite page lists only the catalogued method
        File branchDir = new File(reportRoot, "html/uncatalogued");
        String suiteHtml = read(new File(branchDir, "test-suites/com.example.MySpec.html"));
        assertTrue(suiteHtml.contains(CATALOGUED_METHOD_ID + ".html"),
                "suite page should link the catalogued method");
        assertFalse(suiteHtml.contains(UNCATALOGUED_METHOD_ID + ".html"),
                "suite page should not link a method missing from the catalogue");

        // and the suite index counts only the catalogued method
        String indexHtml = read(new File(branchDir, "test-suites/tia-test-suites.html"));
        assertTrue(indexHtml.contains("<td>1</td></tr>"),
                "suite index should count only the catalogued method. Index was:\n" + indexHtml);

        // and only the catalogued method gets a drill-down page
        assertTrue(new File(branchDir, "methods/" + CATALOGUED_METHOD_ID + ".html").isFile());
        assertFalse(new File(branchDir, "methods/" + UNCATALOGUED_METHOD_ID + ".html").exists());
    }

    /**
     * Build Tia data with one suite whose single class covers a catalogued method and a method id
     * that has no catalogue entry.
     *
     * @return the Tia data to report on
     */
    private TiaData buildTiaDataWithUncataloguedEdge() {
        TiaData tiaData = new TiaData();
        tiaData.setTestStats(new TestStats());
        tiaData.setTestSuitesFailed(Collections.emptySet());
        tiaData.setLibrariesTracked(Collections.emptyMap());
        tiaData.setPendingLibraryImpactedMethods(Collections.emptyList());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(CATALOGUED_METHOD_ID, new MethodImpactTracker("com/example/Car.drive.()V", 10, 20));
        tiaData.setMethodsTracked(methods);

        TestSuiteTracker suite = new TestSuiteTracker("com.example.MySpec");
        suite.setTestStats(new TestStats());
        suite.setClassesImpacted(Collections.singletonList(new ClassImpactTracker("com/example/Car.java",
                new HashSet<>(Arrays.asList(CATALOGUED_METHOD_ID, UNCATALOGUED_METHOD_ID)))));
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(suite.getName(), suite);
        tiaData.setTestSuitesTracked(suites);

        return tiaData;
    }

    /**
     * Read a generated report file as UTF-8 text.
     *
     * @param f the file to read
     * @return the file's contents
     * @throws IOException if the file cannot be read
     */
    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
