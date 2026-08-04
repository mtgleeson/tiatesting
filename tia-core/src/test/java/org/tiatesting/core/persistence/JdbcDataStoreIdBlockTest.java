package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
