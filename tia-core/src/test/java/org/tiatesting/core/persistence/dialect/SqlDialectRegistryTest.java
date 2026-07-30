package org.tiatesting.core.persistence.dialect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link SqlDialectRegistry}: the null/blank-url and {@code jdbc:h2}-prefixed URLs resolve
 * to {@link H2Dialect}, {@code jdbc:postgresql}-prefixed URLs and an explicit {@code "postgres"}
 * override resolve to {@link PostgresDialect}, an explicit {@code "h2"} override resolves to
 * {@link H2Dialect} even when the URL disagrees, and both the unknown-override and
 * unsupported-URL paths throw with the supported dialect ids named in the message.
 */
class SqlDialectRegistryTest {

    @Test
    void nullUrlResolvesToH2() {
        // given / when
        SqlDialect dialect = SqlDialectRegistry.forUrl(null, null);
        // then
        assertTrue(dialect instanceof H2Dialect);
    }

    @Test
    void blankUrlResolvesToH2() {
        // given / when
        SqlDialect dialect = SqlDialectRegistry.forUrl("   ", null);
        // then
        assertTrue(dialect instanceof H2Dialect);
    }

    @Test
    void h2PrefixedUrlResolvesToH2() {
        // given / when
        SqlDialect dialect = SqlDialectRegistry.forUrl("jdbc:h2:tcp://host:9092/tiadb", null);
        // then
        assertTrue(dialect instanceof H2Dialect);
    }

    @Test
    void explicitH2OverrideResolvesToH2RegardlessOfUrl() {
        // given / when
        SqlDialect dialect = SqlDialectRegistry.forUrl("jdbc:oracle:thin:@x", "h2");
        // then
        assertTrue(dialect instanceof H2Dialect);
    }

    @Test
    void explicitH2OverrideIsCaseInsensitive() {
        // given / when
        SqlDialect dialect = SqlDialectRegistry.forUrl(null, "H2");
        // then
        assertTrue(dialect instanceof H2Dialect);
    }

    @Test
    void postgresPrefixedUrlResolvesToPostgres() {
        // given / when
        SqlDialect dialect = SqlDialectRegistry.forUrl("jdbc:postgresql://host:5432/tiadb", null);
        // then
        assertTrue(dialect instanceof PostgresDialect);
    }

    @Test
    void explicitPostgresOverrideResolvesToPostgresRegardlessOfUrl() {
        // given / when
        SqlDialect dialect = SqlDialectRegistry.forUrl("jdbc:h2:tcp://host:9092/tiadb", "postgres");
        // then
        assertTrue(dialect instanceof PostgresDialect);
    }

    @Test
    void explicitPostgresOverrideIsCaseInsensitive() {
        // given / when
        SqlDialect dialect = SqlDialectRegistry.forUrl(null, "POSTGRES");
        // then
        assertTrue(dialect instanceof PostgresDialect);
    }

    @Test
    void unknownOverrideThrowsWithSupportedList() {
        // given / when / then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SqlDialectRegistry.forUrl(null, "oracle"));
        assertTrue(ex.getMessage().contains("oracle"));
        assertTrue(ex.getMessage().toLowerCase().contains("h2"));
        assertTrue(ex.getMessage().toLowerCase().contains("postgres"));
    }

    @Test
    void unsupportedUrlSchemeThrowsWithSupportedList() {
        // given / when / then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SqlDialectRegistry.forUrl("jdbc:oracle:thin:@x", null));
        assertTrue(ex.getMessage().contains("jdbc:oracle:thin:@x"));
        assertTrue(ex.getMessage().toLowerCase().contains("h2"));
        assertTrue(ex.getMessage().toLowerCase().contains("postgres"));
    }
}
