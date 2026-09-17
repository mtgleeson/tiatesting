package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

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
        new HtmlReportGenerator("smoke-branch", reportRoot, null).generateReports(tiaData);

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

        // History table and its per-run detail page - generated with no DataStore (null was
        // passed to the generator), so the row link must still resolve to a real file with an
        // empty selection breakdown.
        String historyEntryId = tiaData.getTestRunHistory().get(0).getId();
        File historyTable = new File(branchDir, "history/tia-history.html");
        File historyDetail = new File(branchDir, "history/" + historyEntryId + ".html");
        assertTrue(historyTable.isFile(), "history table page missing");
        assertTrue(historyDetail.isFile(),
                "history detail page must be generated even with no DataStore (empty triggers)");
        String historyTableHtml = read(historyTable);
        assertTrue(historyTableHtml.contains("href=\"" + historyEntryId + ".html\""),
                "history table Id cell should link to the sibling detail page");
        String historyDetailHtml = read(historyDetail);
        assertTrue(historyDetailHtml.contains("No selection breakdown was recorded for this run."),
                "detail page rendered with a null DataStore should show the empty-breakdown message");

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
        new HtmlReportGenerator("empty-branch", tempDir.toFile(), null).generateReports(empty);

        File branchDir = new File(tempDir.toFile(), "html/empty-branch");
        assertTrue(new File(branchDir, "index.html").isFile());
        assertTrue(new File(branchDir, "source-code.html").isFile());
        assertTrue(new File(branchDir, "libraries/tia-libraries.html").isFile());
    }

    /**
     * With a real DataStore that has a history entry and persisted selection triggers, verifies
     * generateReports bulk-loads those triggers and writes them into the entry's detail page -
     * the end-to-end path a live Maven/Gradle report run takes, as opposed to the null-DataStore
     * case covered by the other tests here.
     *
     * @param tempDir a JUnit-managed temp directory used for both the H2 database file and the
     *                report output tree
     */
    @Test
    void historyDetailPageIncludesTriggersLoadedFromADataStore(@TempDir Path tempDir) throws IOException {
        // given a DataStore with one history entry and one persisted trigger for it
        File dbDir = new File(tempDir.toFile(), "db");
        dbDir.mkdirs();
        H2ConnectionSettings settings = H2ConnectionSettings.embedded(dbDir.getAbsolutePath());
        JdbcDataStore dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings),
                BranchSchema.schemaName("trigger-branch", null));
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setPendingLibraryImpactedMethods(Collections.emptyList());

        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc123", 1_700_000_000_000L,
                10, 2, 1, 5_000L, true, 4_000L, 80, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);
        dataStore.persistTestRunHistoryEntry(entry);
        dataStore.persistTestRunTriggers(entry.getId(),
                Collections.singletonList(new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, "Foo.save", 3)));
        TiaData reloaded = dataStore.getTiaData(true);

        // when the report is generated with the DataStore wired through
        File reportRoot = new File(tempDir.toFile(), "report");
        new HtmlReportGenerator("trigger-branch", reportRoot, dataStore).generateReports(reloaded);
        dataStore.close();

        // then the detail page for the entry exists and names the trigger
        File historyDetail = new File(reportRoot, "html/trigger-branch/history/" + entry.getId() + ".html");
        assertTrue(historyDetail.isFile(), "history detail page should be generated for the DataStore-backed entry");
        String detailHtml = read(historyDetail);
        assertTrue(detailHtml.contains("Foo.save"), "detail page should include the persisted trigger's name");
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
        lib.setLastAppliedSeq(1L);
        lib.setMappingBaselineCommit("baseline-1");
        Map<String, TrackedLibrary> libs = new LinkedHashMap<>();
        libs.put(lib.getGroupArtifact(), lib);
        tiaData.setLibrariesTracked(libs);

        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod();
        pending.setGroupArtifact("com.example:libA");
        pending.setStampVersion("1.1.0");
        pending.setPublishSeq(2L);
        pending.setSourceMethodIds(new java.util.HashSet<>(java.util.Arrays.asList(1, 2, 3)));
        List<PendingLibraryImpactedMethod> pendingList = new ArrayList<>();
        pendingList.add(pending);
        tiaData.setPendingLibraryImpactedMethods(pendingList);

        TestRunHistoryEntry historyEntry = TestRunHistoryEntry.create("main", "abc123", 1_700_000_000_000L,
                10, 2, 1, 5_000L, true, 4_000L, 80, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);
        tiaData.setTestRunHistory(Collections.singletonList(historyEntry));

        return tiaData;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
