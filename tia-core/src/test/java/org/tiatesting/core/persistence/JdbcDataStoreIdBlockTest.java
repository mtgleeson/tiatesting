package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the atomicity of {@code tia_source_class} id block allocation. Ids are assigned
 * application-side so rows can be inserted in chunks, so the allocator is the only thing
 * preventing two concurrent writers from handing out the same ids and colliding on the
 * primary key.
 */
class JdbcDataStoreIdBlockTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-id-block-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
    }

    @Test
    void sequentialAllocationsReturnNonOverlappingBlocks() throws Exception {
        // given
        Connection connection = dataStore.getConnection();

        // when
        long firstBlockStart = dataStore.allocateSourceClassIdBlock(connection, 10);
        long secondBlockStart = dataStore.allocateSourceClassIdBlock(connection, 5);
        connection.close();

        // then
        assertEquals(firstBlockStart + 10, secondBlockStart,
                "the second block must start immediately after the first block ends");
    }

    @Test
    void concurrentAllocationsNeverOverlap() throws Exception {
        // given
        int threads = 8;
        int blockSize = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Long>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(new Callable<Long>() {
                @Override
                public Long call() throws Exception {
                    Connection connection = dataStore.getConnection();
                    try {
                        return dataStore.allocateSourceClassIdBlock(connection, blockSize);
                    } finally {
                        connection.close();
                    }
                }
            });
        }

        // when
        List<Long> starts = new ArrayList<>();
        for (Future<Long> future : executor.invokeAll(jobs)) {
            starts.add(future.get());
        }
        executor.shutdown();

        // then
        Collections.sort(starts);
        for (int i = 1; i < starts.size(); i++) {
            assertTrue(starts.get(i) - starts.get(i - 1) >= blockSize,
                    "blocks must not overlap: " + starts);
        }
    }

    @Test
    void seedsFromExistingMaxIdWhenBlockRowIsAbsent() throws Exception {
        // given - a tia_source_class row already present (simulating a DB created before the
        // id-block table existed) but the tia_id_block counter row has never been seeded
        Connection connection = dataStore.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO tia_source_class (id, tia_test_suite_id, source_filename) "
                    + "VALUES (500, NULL, 'com/example/Foo.java')");
        }

        // when
        long firstBlockStart = dataStore.allocateSourceClassIdBlock(connection, 10);
        connection.close();

        // then
        assertEquals(501, firstBlockStart, "the counter must seed from MAX(id) + 1 of the existing rows");
    }

    @Test
    void repeatedAllocationDoesNotReseedOrResetTheCounter() throws Exception {
        // given - the block is already seeded and advanced by a first allocation
        Connection connection = dataStore.getConnection();
        long firstBlockStart = dataStore.allocateSourceClassIdBlock(connection, 10);

        // when - a later allocation runs the (now short-circuited) seed path again
        long secondBlockStart = dataStore.allocateSourceClassIdBlock(connection, 10);
        connection.close();

        // then - the counter continues on from where the first allocation left it, rather than
        // being reset back to the original seed value
        assertEquals(firstBlockStart + 10, secondBlockStart,
                "re-running the seed path on an already-seeded block must not reset the counter");
    }

    @Test
    void genuineSeedFailureIsNotSwallowedAsALostRace() throws Exception {
        // given - the tia_id_block table exists (created by ensureSchema in setUp) but the
        // tia_source_class counter row has never been seeded; shrink block_name so the seed's
        // conditional insert fails for a real reason (value too long for column) rather than a
        // lost race against another writer
        Connection ddlConnection = dataStore.getConnection();
        try (Statement statement = ddlConnection.createStatement()) {
            statement.executeUpdate("ALTER TABLE tia_id_block ALTER COLUMN block_name VARCHAR(3)");
        }
        ddlConnection.close();

        Connection connection = dataStore.getConnection();

        // when / then
        assertThrows(SQLException.class, () -> dataStore.allocateSourceClassIdBlock(connection, 10),
                "a genuine seed failure must propagate rather than being swallowed as a lost race");
        connection.close();
    }

    @Test
    void concurrentSuiteMappingPersistsDoNotCollideOnSourceClassIds() throws Exception {
        // given - two writers persisting disjoint suites at the same time
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int writerIndex = i;
            jobs.add(new Callable<Void>() {
                @Override
                public Void call() {
                    dataStore.persistTestSuites(suiteWithClasses("Suite" + writerIndex, 25));
                    return null;
                }
            });
        }

        // when
        for (Future<Void> future : executor.invokeAll(jobs)) {
            future.get();
        }
        executor.shutdown();

        // then - every suite kept all of its source-class rows (no PK collision dropped any)
        Map<String, TestSuiteTracker> stored = dataStore.getTiaData(true).getTestSuitesTracked();
        assertEquals(threads, stored.size(), "every suite must have persisted");
        for (TestSuiteTracker tracker : stored.values()) {
            assertEquals(25, tracker.getClassesImpacted().size(),
                    "suite " + tracker.getName() + " lost source-class rows");
        }
    }

    /**
     * Build a single-suite map whose suite covers {@code classCount} distinct source files, each
     * with one impacted method. Used to give concurrent writers enough source-class rows that
     * overlapping id blocks would collide.
     *
     * @param suiteName the test suite name
     * @param classCount how many source classes the suite covers
     * @return a map of suite name to tracker, ready for {@code persistTestSuites}
     */
    private Map<String, TestSuiteTracker> suiteWithClasses(String suiteName, int classCount) {
        TestSuiteTracker tracker = new TestSuiteTracker(suiteName);
        List<ClassImpactTracker> classes = new ArrayList<>();
        for (int i = 0; i < classCount; i++) {
            MethodIdSet methods = new MethodIdSet();
            methods.add(suiteName.hashCode() + i);
            classes.add(new ClassImpactTracker("com/example/" + suiteName + "Class" + i + ".java", methods));
        }
        tracker.setClassesImpacted(classes);
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(suiteName, tracker);
        return suites;
    }
}
