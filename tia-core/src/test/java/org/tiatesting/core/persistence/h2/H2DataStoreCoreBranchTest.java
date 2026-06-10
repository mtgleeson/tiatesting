package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Round-trip and migration tests for the {@code tia_core.branch} column in {@link H2DataStore}.
 * Uses a temp-directory embedded H2 database per test for isolation.
 */
class H2DataStoreCoreBranchTest {

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        // force schema creation
        dataStore.getTiaData(true);
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
     * Build a minimal core {@link TiaData} for persistence. The commit and lastUpdated values are
     * required by the persist SQL; the branch is the value under test.
     *
     * @param commit the commit value to stamp
     * @param branch the branch to stamp, or {@code null}
     * @return a populated TiaData ready for {@code persistCoreData}
     */
    private TiaData coreData(final String commit, final String branch) {
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue(commit);
        tiaData.setBranch(branch);
        tiaData.setLastUpdated(Instant.now());
        return tiaData;
    }

    @Test
    void persistAndReadCoreBranchRoundTrips() {
        // given
        TiaData tiaData = coreData("abc123", "feature/x");

        // when
        dataStore.persistCoreData(tiaData);
        TiaData read = dataStore.getTiaCore();

        // then
        assertEquals("abc123", read.getCommitValue());
        assertEquals("feature/x", read.getBranch());
    }

    @Test
    void updateOverwritesStoredBranch() {
        // given
        // first persist takes the INSERT path, the second the UPDATE path (commit now set)
        dataStore.persistCoreData(coreData("commit1", "main"));

        // when
        dataStore.persistCoreData(coreData("commit2", "release"));
        TiaData read = dataStore.getTiaCore();

        // then
        assertEquals("commit2", read.getCommitValue());
        assertEquals("release", read.getBranch());
    }

    @Test
    void nullBranchStoredAsSqlNullNotLiteralNull() {
        // given
        TiaData tiaData = coreData("abc123", null);

        // when
        dataStore.persistCoreData(tiaData);
        TiaData read = dataStore.getTiaCore();

        // then
        // the branch is genuinely unset (e.g. a stats-only run): it must come back as null, not "null"
        assertNull(read.getBranch());
    }
}
