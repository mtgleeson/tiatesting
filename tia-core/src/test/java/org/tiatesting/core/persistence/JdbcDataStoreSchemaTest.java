package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link DataStoreFactory#fromConfig} wires a per-branch schema through to the H2
 * connection, so a persist lands its tables in the branch's own schema rather than the vendor
 * default. See the per-branch schema WIKI chapter.
 */
class JdbcDataStoreSchemaTest {

    @Test
    void createsAndUsesBranchSchemaOnH2(@TempDir Path dir) throws Exception {
        // given a factory-built H2 store on branch "featureX"
        DataStore store = DataStoreFactory.fromConfig(dir.toString(), null, "tia", "", null, "featureX");
        // when it bootstraps the schema (as every real Tia run does via getTiaData/getTiaCore
        // before any write) and then persists something. persistTestSuitesFailed alone does not
        // bootstrap the schema itself - it assumes an earlier read already did, exactly as it does
        // in the production TestRunnerService flow - so getTiaData(true) is called first here too.
        store.getTiaData(true);
        store.persistTestSuitesFailed(new java.util.HashSet<>(java.util.Arrays.asList("x")));
        // then the tia tables exist under the branch schema (H2 preserves the case of a quoted
        // identifier, and BranchSchema.schemaName always lower-cases, so the stored schema name is
        // "tia_featurex")
        try (Connection c = new org.tiatesting.core.persistence.connection.H2ConnectionProvider(
                org.tiatesting.core.persistence.h2.H2ConnectionSettings.embedded(dir.toString())).get();
             ResultSet rs = c.getMetaData().getTables(null, "tia_featurex", "%", new String[]{"TABLE"})) {
            // then - at least one Tia table is present in the branch schema
            assertTrue(rs.next(), "expected Tia tables in schema tia_featurex");
        }
    }
}
