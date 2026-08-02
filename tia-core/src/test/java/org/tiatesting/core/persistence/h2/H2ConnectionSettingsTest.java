package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link H2ConnectionSettings}, covering the embedded/server mode decision,
 * credential defaulting, and field exposure.
 */
class H2ConnectionSettingsTest {

    @Test
    void embeddedModeUsesDefaultCredentialsAndCarriesPath() {
        // given
        String dbFilePath = "/var/tia";

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.embedded(dbFilePath);

        // then
        assertFalse(settings.isServerMode());
        assertEquals(dbFilePath, settings.getDbFilePath());
        assertNull(settings.getDbUrl());
        assertEquals("tia", settings.getUsername());
        assertEquals("1234", settings.getPassword());
    }

    @Test
    void serverModeUsesSuppliedUrlAndCredentials() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.server(url, "tia", "secret");

        // then
        assertTrue(settings.isServerMode());
        assertEquals(url, settings.getDbUrl());
        assertEquals("tia", settings.getUsername());
        assertEquals("secret", settings.getPassword());
        assertNull(settings.getDbFilePath());
    }

    @Test
    void serverModeDefaultsMissingCredentials() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.server(url, null, null);

        // then
        assertEquals("tia", settings.getUsername());
        assertEquals("", settings.getPassword());
    }

    @Test
    void serverModeFallsBackToEnvCredentialsWhenConfigAbsent() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put(H2ConnectionSettings.ENV_DB_USER, "envuser");
        env.put(H2ConnectionSettings.ENV_DB_PASSWORD, "envsecret");

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.server(url, null, null, env::get);

        // then
        assertEquals("envuser", settings.getUsername());
        assertEquals("envsecret", settings.getPassword());
    }

    @Test
    void serverModeHonoursExplicitEmptyPasswordOverEnv() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put(H2ConnectionSettings.ENV_DB_PASSWORD, "envsecret");

        // when
        // an explicitly-configured empty password must be used verbatim, not treated as "unset"
        H2ConnectionSettings settings = H2ConnectionSettings.server(url, "tia", "", env::get);

        // then
        assertEquals("", settings.getPassword());
    }

    @Test
    void serverModeDoesNotTrimConfiguredPassword() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put(H2ConnectionSettings.ENV_DB_PASSWORD, "envsecret");

        // when
        // whitespace is non-null, so it is honoured verbatim and the env fallback is not consulted
        H2ConnectionSettings settings = H2ConnectionSettings.server(url, "tia", "  ", env::get);

        // then
        assertEquals("  ", settings.getPassword());
    }

    @Test
    void serverModeFallsBackToEnvPasswordOnlyWhenPasswordIsNull() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put(H2ConnectionSettings.ENV_DB_PASSWORD, "envsecret");

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.server(url, "tia", null, env::get);

        // then
        assertEquals("envsecret", settings.getPassword());
    }

    @Test
    void serverModeConfiguredCredentialsTakePrecedenceOverEnv() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put(H2ConnectionSettings.ENV_DB_USER, "envuser");
        env.put(H2ConnectionSettings.ENV_DB_PASSWORD, "envsecret");

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.server(url, "tia", "secret", env::get);

        // then
        assertEquals("tia", settings.getUsername());
        assertEquals("secret", settings.getPassword());
    }

    @Test
    void serverModeDefaultsWhenBothConfigAndEnvAbsent() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.server(url, null, null, name -> null);

        // then
        assertEquals("tia", settings.getUsername());
        assertEquals("", settings.getPassword());
    }

    @Test
    void fromConfigPicksServerModeWhenUrlSupplied() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.fromConfig("/var/tia", url, "tia", "secret");

        // then
        assertTrue(settings.isServerMode());
        assertEquals(url, settings.getDbUrl());
        assertEquals("tia", settings.getUsername());
        assertEquals("secret", settings.getPassword());
    }

    @Test
    void fromConfigPicksEmbeddedModeWhenUrlBlank() {
        // given
        String blankUrl = "   ";

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.fromConfig("/var/tia", blankUrl, "ignored", "ignored");

        // then
        assertFalse(settings.isServerMode());
        assertEquals("/var/tia", settings.getDbFilePath());
        assertEquals("tia", settings.getUsername());
        assertEquals("1234", settings.getPassword());
    }

    @Test
    void fromConfigPicksEmbeddedModeWhenUrlNull() {
        // given
        String nullUrl = null;

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.fromConfig("/var/tia", nullUrl, null, null);

        // then
        assertFalse(settings.isServerMode());
        assertEquals("/var/tia", settings.getDbFilePath());
    }

    @Test
    void fromSystemPropertiesPicksServerModeWhenUrlPropertySet() {
        // given
        System.setProperty(H2ConnectionSettings.PROP_DB_URL, "jdbc:h2:tcp://h2host:9092/tiadb");
        System.setProperty(H2ConnectionSettings.PROP_DB_USER, "tia");
        System.setProperty(H2ConnectionSettings.PROP_DB_PASSWORD, "secret");
        try {
            // when
            H2ConnectionSettings settings = H2ConnectionSettings.fromSystemProperties();

            // then
            assertTrue(settings.isServerMode());
            assertEquals("jdbc:h2:tcp://h2host:9092/tiadb", settings.getDbUrl());
            assertEquals("tia", settings.getUsername());
            assertEquals("secret", settings.getPassword());
        } finally {
            System.clearProperty(H2ConnectionSettings.PROP_DB_URL);
            System.clearProperty(H2ConnectionSettings.PROP_DB_USER);
            System.clearProperty(H2ConnectionSettings.PROP_DB_PASSWORD);
        }
    }

    @Test
    void fromSystemPropertiesPicksEmbeddedModeWhenOnlyFilePathSet() {
        // given
        System.setProperty(H2ConnectionSettings.PROP_DB_FILE_PATH, "/var/tia");
        try {
            // when
            H2ConnectionSettings settings = H2ConnectionSettings.fromSystemProperties();

            // then
            assertFalse(settings.isServerMode());
            assertEquals("/var/tia", settings.getDbFilePath());
        } finally {
            System.clearProperty(H2ConnectionSettings.PROP_DB_FILE_PATH);
        }
    }
}
