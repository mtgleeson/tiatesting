package org.tiatesting.core.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LibraryPublishesReportGenerator} - the table listing behind the
 * {@code library-publishes} task / mojo. Uses a temp-directory embedded H2 store seeded through
 * the public persist API, matching the other report tests.
 */
class LibraryPublishesReportGeneratorTest {

    private static final String LIB = "com.example:mylib";

    private H2DataStore dataStore;
    private File tempDir;
    private LibraryPublishesReportGenerator generator;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-lib-publishes-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
        generator = new LibraryPublishesReportGenerator();
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * The report renders one aligned table row per ledger entry with the seq, version,
     * truncated hash/commit, publish time and the pending method count for stamped builds.
     */
    @Test
    void rendersLedgerRowsWithPendingCounts() {
        // given a tracked library with an unstamped seed publish and a stamped publish
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/mylib", null));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0-SNAPSHOT",
                "aaaaaaaaaaaaaaaabbbb", "commit-1-aaaaaaaa", 1000L), Collections.emptySet());
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0-SNAPSHOT",
                "ccccccccccccccccdddd", "commit-2-bbbbbbbb", 2000L), new HashSet<>(Arrays.asList(10, 20)));

        // when the report is generated
        String report = generator.generateLibraryPublishesReport(dataStore, LIB);

        // then the header, separator and both rows render with their detail
        assertTrue(report.contains("Publishes for library " + LIB));
        assertTrue(report.contains("Seq | Version"));
        assertTrue(report.contains("-+-"), "table must render a header separator line");
        assertTrue(report.contains("aaaaaaaaaaaa..."), "jar hash must be truncated to 12 chars");
        assertTrue(report.contains("commit-1-aaa..."), "commit must be truncated to 12 chars");
        assertTrue(report.contains("1970-01-01T00:00:01Z"), "publish time must render as UTC ISO-8601");
        assertTrue(report.contains("2"), "the stamped publish must show its pending method count");
    }

    /**
     * A library with no publishes reports {@code none} rather than an empty table.
     */
    @Test
    void reportsNoneWhenLedgerEmpty() {
        // given a tracked library with no publishes
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/mylib", null));

        // when the report is generated
        String report = generator.generateLibraryPublishesReport(dataStore, LIB);

        // then it reports none
        assertTrue(report.contains("none"));
    }

    /**
     * An unknown coordinate reports "not tracked"; a blank input reports usage.
     */
    @Test
    void reportsNotTrackedAndUsageErrors() {
        // given no tracked libraries

        // when the report is generated for an unknown and a blank coordinate
        String unknown = generator.generateLibraryPublishesReport(dataStore, "com.example:nope");
        String blank = generator.generateLibraryPublishesReport(dataStore, "  ");

        // then each input gets its guidance message
        assertTrue(unknown.contains("Library 'com.example:nope' is not tracked."));
        assertTrue(blank.contains("A library must be specified as groupId:artifactId."));
    }

    /**
     * Pending counts belong to the batch's own publish: a drained (absent) batch renders a dash
     * while a pending one renders its count - the reader can see which builds still await drain.
     */
    @Test
    void showsDashForPublishesWithNothingPending() {
        // given a stamped publish whose batch was drained (deleted) and one still pending
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/mylib", null));
        long drainedSeq = dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0",
                "H1", "c1", 1000L), new HashSet<>(Arrays.asList(10)));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.1.0",
                "H2", "c2", 2000L), new HashSet<>(Arrays.asList(20)));
        dataStore.deletePendingLibraryImpactedMethods(LIB, drainedSeq);

        // when the report is generated
        String report = generator.generateLibraryPublishesReport(dataStore, LIB);

        // then the drained build's row shows a dash in the pending column
        String[] lines = report.split(System.lineSeparator());
        for (String line : lines) {
            if (line.startsWith("1 ")) {
                assertTrue(line.trim().endsWith("-"), "drained publish must show '-' pending: " + line);
            }
            if (line.startsWith("2 ")) {
                assertTrue(line.trim().endsWith("1"), "pending publish must show its count: " + line);
            }
        }
    }
}
