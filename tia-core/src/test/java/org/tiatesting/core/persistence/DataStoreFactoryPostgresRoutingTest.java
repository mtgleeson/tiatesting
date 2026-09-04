package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.connection.PostgresConnectionProvider;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DataStoreFactory#fromConfig} routes a {@code jdbc:postgresql} URL to a
 * {@link PostgresConnectionProvider} (the auto-creating provider), not the generic
 * {@link org.tiatesting.core.persistence.connection.JdbcConnectionProvider}. Builds the store only -
 * no connection is opened - so this runs in the normal build without a Postgres instance. See the
 * pluggable-datastore WIKI chapter.
 */
class DataStoreFactoryPostgresRoutingTest {

    @Test
    void routesPostgresUrlToPostgresConnectionProvider() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tiaperf";
        // when
        DataStore store = DataStoreFactory.fromConfig(null, url, "tia", "tia", null, "main", null);
        // then
        assertTrue(store instanceof JdbcDataStore);
        assertTrue(((JdbcDataStore) store).getConnectionProvider() instanceof PostgresConnectionProvider);
    }
}
