package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test: builds a populated {@link TiaData}, runs the full
 * {@link HtmlReportGenerator}, and verifies the expected file tree exists with structural
 * markers (Pico CSS link, top-nav, breadcrumb, page-specific tables) on each page. Catches
 * NPEs and missing-link regressions; doesn't validate visual styling.
 */
class HtmlReportGeneratorSmokeTest {

    @Test
    void generatesAllPagesAndAssetsForPopulatedTiaData(@TempDir Path tempDir) throws IOException {
        TiaData tiaData = buildSampleTiaData();

        File reportRoot = tempDir.toFile();
        new HtmlReportGenerator("smoke-branch", reportRoot).generateReports(tiaData);

        File branchDir = new File(reportRoot, "html/smoke-branch");
        File assetsDir = new File(branchDir, "assets");

        // Asset extraction
        assertTrue(new File(assetsDir, "css/pico.classless.min.css").isFile(), "Pico CSS missing");
        assertTrue(new File(assetsDir, "css/simple-datatables.css").isFile(), "simple-datatables CSS missing");
        assertTrue(new File(assetsDir, "css/tia.css").isFile(), "tia.css missing");
        assertTrue(new File(assetsDir, "js/simple-datatables.min.js").isFile(), "simple-datatables JS missing");
        assertTrue(new File(assetsDir, "images/tia_logo.png").isFile(), "logo missing");
        assertTrue(new File(assetsDir, "images/tia_favicon.ico").isFile(), "favicon missing");

        // Pages
        File index = new File(branchDir, "index.html");
        File sourceCode = new File(branchDir, "source-code.html");
        File testSuites = new File(branchDir, "test-suites/tia-test-suites.html");
        File methodsList = new File(branchDir, "methods/tia-source-methods.html");
        File libraries = new File(branchDir, "libraries/tia-libraries.html");

        for (File f : new File[]{index, sourceCode, testSuites, methodsList, libraries}) {
            assertTrue(f.isFile(), "Expected page missing: " + f);
        }

        String indexHtml = read(index);
        // Pico is referenced
        assertTrue(indexHtml.contains("pico.classless.min.css"), "index.html should reference Pico");
        // Top nav present, Home is current
        assertTrue(indexHtml.contains("Source Code"), "index.html should link to Source Code");
        assertTrue(indexHtml.contains("aria-current=\"page\""), "index.html should mark active nav");
        // Pending library section rendered
        assertTrue(indexHtml.contains("Pending Library Changes"),
                "index.html should have pending library section header");
        assertTrue(indexHtml.contains("com.example:libA"),
                "index.html should list pending library coordinate");
        // Pending count badge in nav
        assertTrue(indexHtml.contains("tia-badge"), "index.html should show nav badge for pending count");

        String suitesHtml = read(testSuites);
        assertTrue(suitesHtml.contains("tia-breadcrumb"), "test-suites should have breadcrumb nav");
        assertTrue(suitesHtml.contains("../assets/css/pico.classless.min.css"),
                "deep page should reference assets via relative ../");
        assertTrue(suitesHtml.contains("MyTestSuite"), "test-suites should list the suite");

        String librariesHtml = read(libraries);
        assertTrue(librariesHtml.contains("Tracked Libraries"));
        assertTrue(librariesHtml.contains("com.example:libA"));
        assertTrue(librariesHtml.contains("../source-code.html"),
                "libraries breadcrumb should link to Source Code landing");

        String sourceCodeHtml = read(sourceCode);
        assertTrue(sourceCodeHtml.contains("tia-card-grid"), "source-code should render card grid");
        assertTrue(sourceCodeHtml.contains("methods/tia-source-methods.html"));
        assertTrue(sourceCodeHtml.contains("libraries/tia-libraries.html"));

        String methodsHtml = read(methodsList);
        assertTrue(methodsHtml.contains("Source Methods"));
        assertTrue(methodsHtml.contains("../source-code.html"),
                "methods breadcrumb should link to Source Code landing");

        // No leftover CDN URLs from the old pre-bundled implementation.
        for (File f : new File[]{index, sourceCode, testSuites, methodsList, libraries}) {
            String html = read(f);
            assertFalse(html.contains("cdn.jsdelivr.net"),
                    "Old CDN URL leaked into " + f.getName() + " — should be bundled now");
        }
    }

    @Test
    void emptyTiaDataDoesNotThrow(@TempDir Path tempDir) {
        TiaData empty = new TiaData();
        empty.setTestStats(new TestStats());
        empty.setTestSuitesTracked(Collections.emptyMap());
        empty.setTestSuitesFailed(Collections.emptySet());
        empty.setMethodsTracked(Collections.emptyMap());
        empty.setLibrariesTracked(Collections.emptyMap());
        empty.setPendingLibraryImpactedMethods(Collections.emptyList());

        // Should generate every page without NPE on empty collections.
        new HtmlReportGenerator("empty-branch", tempDir.toFile()).generateReports(empty);

        File branchDir = new File(tempDir.toFile(), "html/empty-branch");
        assertTrue(new File(branchDir, "index.html").isFile());
        assertTrue(new File(branchDir, "source-code.html").isFile());
        assertTrue(new File(branchDir, "libraries/tia-libraries.html").isFile());
    }

    private TiaData buildSampleTiaData() {
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue("abc123");
        tiaData.setLastUpdated(Instant.now());

        TestStats stats = new TestStats();
        stats.setNumRuns(10);
        stats.setNumSuccessRuns(8);
        stats.setNumFailRuns(2);
        stats.setAvgRunTime(120L);
        tiaData.setTestStats(stats);

        TestStats suiteStats = new TestStats();
        suiteStats.setNumRuns(5);
        suiteStats.setNumSuccessRuns(5);
        suiteStats.setNumFailRuns(0);
        suiteStats.setAvgRunTime(50L);

        org.tiatesting.core.model.TestSuiteTracker suite = new org.tiatesting.core.model.TestSuiteTracker("MyTestSuite");
        suite.setTestStats(suiteStats);
        suite.setClassesImpacted(Collections.emptyList());

        Map<String, org.tiatesting.core.model.TestSuiteTracker> suites = new LinkedHashMap<>();
        suites.put("MyTestSuite", suite);
        tiaData.setTestSuitesTracked(suites);

        tiaData.setTestSuitesFailed(new LinkedHashSet<>(Collections.singletonList("FailingSuite")));
        tiaData.setMethodsTracked(Collections.emptyMap());

        TrackedLibrary lib = new TrackedLibrary();
        lib.setGroupArtifact("com.example:libA");
        lib.setProjectDir("/abs/path/to/libA");
        lib.setSourceDirsCsv("/abs/path/to/libA/src/main/java");
        lib.setLastSourceProjectVersion("1.0.0");
        lib.setLastReleasedLibraryVersion("1.0.0");
        Map<String, TrackedLibrary> libs = new LinkedHashMap<>();
        libs.put(lib.getGroupArtifact(), lib);
        tiaData.setLibrariesTracked(libs);

        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod();
        pending.setGroupArtifact("com.example:libA");
        pending.setStampVersion("1.1.0");
        pending.setUnknownNextVersion(false);
        pending.setSourceMethodIds(new java.util.HashSet<>(java.util.Arrays.asList(1, 2, 3)));
        List<PendingLibraryImpactedMethod> pendingList = new ArrayList<>();
        pendingList.add(pending);
        tiaData.setPendingLibraryImpactedMethods(pendingList);

        return tiaData;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
