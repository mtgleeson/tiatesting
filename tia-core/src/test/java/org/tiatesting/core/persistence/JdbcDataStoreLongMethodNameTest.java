package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the {@code method_name} column width. A method's stored name is its full
 * descriptor including its complete parameter-type list, which for a high-arity method or
 * constructor can exceed 2000 characters. Both the persistent method catalogue
 * ({@code tia_source_method}) and the distributed-run method staging table
 * ({@code tia_distributed_run_method_stage}) must accept such a descriptor. These tests round-trip
 * an over-length method name through both tables against embedded H2; they failed at insert time
 * while the column was declared {@code VARCHAR(2000)} and pass now that it is unbounded.
 */
class JdbcDataStoreLongMethodNameTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so each
     * test starts from an isolated store with nothing persisted.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-long-method-name-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData(true);
    }

    /**
     * Close the data store so its embedded H2 database releases its file lock.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
    }

    /**
     * Verify that staging a method tracker whose name is longer than 2000 characters and reading it
     * back for the same run id reproduces the name byte-for-byte and preserves its line range. This
     * insert failed with a "value too long" error while the staging table's {@code method_name} was
     * declared {@code VARCHAR(2000)}.
     */
    @Test
    void shouldRoundTripAnOverLengthMethodNameThroughTheStagingTable() {
        // given
        String longMethodName = buildLongMethodDescriptor();
        assertTrue(longMethodName.length() > 2000,
                "the built descriptor must exceed 2000 chars for the test to be meaningful");
        Map<Integer, MethodImpactTracker> staged = new HashMap<>();
        staged.put(101, new MethodImpactTracker(longMethodName, 10, 20));

        // when
        dataStore.persistStagedMethodTrackers("run-1", staged);
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-1");

        // then
        assertEquals(1, read.size());
        assertEquals(longMethodName, read.get(101).getMethodName());
        assertEquals(10, read.get(101).getLineNumberStart());
        assertEquals(20, read.get(101).getLineNumberEnd());
    }

    /**
     * Verify that persisting a method tracker whose name is longer than 2000 characters into the
     * catalogue and reading it back reproduces the name byte-for-byte and preserves its line range.
     * This insert failed with a "value too long" error while the catalogue table's
     * {@code method_name} was declared {@code VARCHAR(2000)}.
     */
    @Test
    void shouldRoundTripAnOverLengthMethodNameThroughTheCatalogue() {
        // given
        String longMethodName = buildLongMethodDescriptor();
        assertTrue(longMethodName.length() > 2000,
                "the built descriptor must exceed 2000 chars for the test to be meaningful");
        Map<Integer, MethodImpactTracker> methodsTracked = new HashMap<>();
        methodsTracked.put(101, new MethodImpactTracker(longMethodName, 10, 20));

        // when
        dataStore.persistSourceMethods(methodsTracked);
        Map<Integer, MethodImpactTracker> read = dataStore.getMethodsTracked();

        // then
        assertEquals(1, read.size());
        assertEquals(longMethodName, read.get(101).getMethodName());
        assertEquals(10, read.get(101).getLineNumberStart());
        assertEquals(20, read.get(101).getLineNumberEnd());
    }

    /**
     * Build a method descriptor longer than 2000 characters by giving a constructor a long list of
     * reference-type parameters, standing in for a high-arity method or constructor whose full
     * parameter-type list overflows a fixed-width column.
     *
     * @return a fully-qualified method descriptor guaranteed to exceed 2000 characters
     */
    private static String buildLongMethodDescriptor() {
        StringBuilder params = new StringBuilder();
        // each token is "Lcom/example/Dependency;" (24 chars); ~100 tokens is well over 2000 chars.
        for (int i = 0; i < 100; i++) {
            params.append("Lcom/example/Dependency;");
        }
        return "com/example/pkg/SomeType.<init>.(" + params + ")V";
    }
}
