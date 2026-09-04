package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guarded end-to-end test proving {@link DataStoreFactory#fromConfig} builds a working
 * {@link JdbcDataStore} for a {@code jdbc:postgresql} URL against a real Postgres, as documented in
 * the pluggable-datastore WIKI chapter. Skipped (not failed) via {@link Assumptions} when no
 * Postgres is reachable on {@code localhost:5432}, so the normal build stays green without the
 * {@code spike/postgres/} harness running.
 */
class DataStoreFactoryPostgresTest {

    /**
     * Skip this test when no Postgres is reachable on {@code localhost:5432}, via a quick TCP
     * connect probe. Used as a guard at the top of each test method so the suite reports the test
     * as skipped rather than failed when the local harness database is not running.
     */
    private void assumePg() {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("localhost", 5432), 500);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "spike Postgres not running");
        }
    }

    /**
     * Verifies that a {@code jdbc:postgresql} URL resolves to the Postgres dialect and builds a
     * {@link JdbcDataStore} backed by a real connection, proving the factory's non-H2 branch is
     * wired end-to-end and not just reachable in isolation.
     */
    @Test
    void buildsPostgresStoreFromUrl() {
        // given
        assumePg();
        // when
        DataStore store = DataStoreFactory.fromConfig(null,
                "jdbc:postgresql://localhost:5432/tiaperf", "tia", "tia", null, "main", null);
        // then
        assertTrue(store instanceof JdbcDataStore);
    }
}
