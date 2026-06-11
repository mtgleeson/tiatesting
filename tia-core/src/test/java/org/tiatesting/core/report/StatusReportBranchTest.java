package org.tiatesting.core.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the status report renders the stored branch (and falls back to {@code N/A} when the
 * branch is unset). Backed by a real temp-directory embedded H2 database, matching the other
 * datastore-backed tests.
 */
class StatusReportBranchTest {

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true); // force schema creation
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * Persist a minimal core row with the given branch.
     *
     * @param branch the branch to stamp, or {@code null}
     */
    private void persistBranch(final String branch) {
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue("abc123");
        tiaData.setBranch(branch);
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
    }

    @Test
    void statusReportShowsStoredBranch() {
        // given
        persistBranch("feature/x");

        // when
        String report = new StatusReportGenerator().generateSummaryReport(dataStore);

        // then
        assertTrue(report.contains("Branch: feature/x"), report);
    }

    @Test
    void statusReportShowsNAWhenBranchUnset() {
        // given
        persistBranch(null);

        // when
        String report = new StatusReportGenerator().generateSummaryReport(dataStore);

        // then
        assertTrue(report.contains("Branch: N/A"), report);
    }
}
