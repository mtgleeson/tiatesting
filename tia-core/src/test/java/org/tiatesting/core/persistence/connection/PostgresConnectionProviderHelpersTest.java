package org.tiatesting.core.persistence.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PostgresConnectionProvider}'s pure URL and message helpers, which carry the
 * fiddly maintenance-URL derivation and the CREATEDB-gate wording. No database is required. See the
 * pluggable-datastore WIKI chapter.
 */
class PostgresConnectionProviderHelpersTest {

    @Test
    void databaseNameNoPort() {
        // given
        String url = "jdbc:postgresql://localhost/tia_junit5";
        // when
        String db = PostgresConnectionProvider.databaseName(url);
        // then
        assertEquals("tia_junit5", db);
    }

    @Test
    void databaseNameWithPort() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tia_junit5";
        // when
        String db = PostgresConnectionProvider.databaseName(url);
        // then
        assertEquals("tia_junit5", db);
    }

    @Test
    void databaseNameWithParams() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tia_junit5?ssl=true&foo=bar";
        // when
        String db = PostgresConnectionProvider.databaseName(url);
        // then
        assertEquals("tia_junit5", db);
    }

    @Test
    void maintenanceUrlNoPort() {
        // given
        String url = "jdbc:postgresql://localhost/tia_junit5";
        // when
        String maintenance = PostgresConnectionProvider.maintenanceUrl(url);
        // then
        assertEquals("jdbc:postgresql://localhost/postgres", maintenance);
    }

    @Test
    void maintenanceUrlWithPort() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tia_junit5";
        // when
        String maintenance = PostgresConnectionProvider.maintenanceUrl(url);
        // then
        assertEquals("jdbc:postgresql://localhost:5432/postgres", maintenance);
    }

    @Test
    void maintenanceUrlPreservesParams() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tia_junit5?ssl=true&foo=bar";
        // when
        String maintenance = PostgresConnectionProvider.maintenanceUrl(url);
        // then
        assertEquals("jdbc:postgresql://localhost:5432/postgres?ssl=true&foo=bar", maintenance);
    }

    @Test
    void maintenanceUrlPreservesMultiHostAuthority() {
        // given
        String url = "jdbc:postgresql://h1:5432,h2:5432/tia_junit5";
        // when
        String maintenance = PostgresConnectionProvider.maintenanceUrl(url);
        // then
        assertEquals("jdbc:postgresql://h1:5432,h2:5432/postgres", maintenance);
    }

    @Test
    void maintenanceUrlWhenTargetAlreadyPostgres() {
        // given
        String url = "jdbc:postgresql://localhost:5432/postgres";
        // when
        String maintenance = PostgresConnectionProvider.maintenanceUrl(url);
        // then
        assertEquals("jdbc:postgresql://localhost:5432/postgres", maintenance);
    }

    @Test
    void emptyDatabaseSegmentThrows() {
        // given
        String url = "jdbc:postgresql://localhost:5432/";
        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> PostgresConnectionProvider.databaseName(url));
    }

    @Test
    void nonPostgresUrlThrows() {
        // given
        String url = "jdbc:h2:tcp://localhost:9092/tiadb";
        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> PostgresConnectionProvider.maintenanceUrl(url));
    }

    @Test
    void privilegeErrorMessageEmbedsDbNameAndDriverMessage() {
        // given
        String driverMessage = "ERROR: permission denied to create database";
        // when
        String message = PostgresConnectionProvider.createDbPrivilegeErrorMessage("tia_junit5", driverMessage);
        // then
        assertTrue(message.contains("tia_junit5"));
        assertTrue(message.contains("CREATEDB"));
        assertTrue(message.contains(driverMessage));
    }
}
