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
    void embeddedModeUsesDefaultCredentialsAndCarriesPathAndSuffix() {
        // given
        String dbFilePath = "/var/tia";
        String branch = "main";

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.embedded(dbFilePath, branch);

        // then
        assertFalse(settings.isServerMode());
        assertEquals(dbFilePath, settings.getDbFilePath());
        assertEquals(branch, settings.getBranchSuffix());
        assertNull(settings.getDbUrl());
        assertEquals("sa", settings.getUsername());
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
        assertNull(settings.getBranchSuffix());
    }

    @Test
    void serverModeDefaultsMissingCredentials() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.server(url, null, null);

        // then
        assertEquals("sa", settings.getUsername());
        assertEquals("", settings.getPassword());
    }

    @Test
    void fromConfigPicksServerModeWhenUrlSupplied() {
        // given
        String url = "jdbc:h2:tcp://h2host:9092/tiadb";

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.fromConfig("/var/tia", url, "tia", "secret", "main");

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
        H2ConnectionSettings settings = H2ConnectionSettings.fromConfig("/var/tia", blankUrl, "ignored", "ignored", "main");

        // then
        assertFalse(settings.isServerMode());
        assertEquals("/var/tia", settings.getDbFilePath());
        assertEquals("main", settings.getBranchSuffix());
        assertEquals("sa", settings.getUsername());
        assertEquals("1234", settings.getPassword());
    }

    @Test
    void fromConfigPicksEmbeddedModeWhenUrlNull() {
        // given
        String nullUrl = null;

        // when
        H2ConnectionSettings settings = H2ConnectionSettings.fromConfig("/var/tia", nullUrl, null, null, "main");

        // then
        assertFalse(settings.isServerMode());
        assertEquals("/var/tia", settings.getDbFilePath());
    }
}
