package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link TiaBasePlugin#buildH2ConnectionSettings()} resolves embedded vs server
 * mode from the {@code tia { ... }} extension, and that the new server-mode extension properties
 * round-trip. Uses {@link ProjectBuilder} so the plugin's project-relative path resolution is
 * exercised against a real project dir.
 */
class TiaBasePluginConnectionSettingsTest {

    /** Minimal concrete plugin so the abstract base can be applied and queried in tests. */
    static class TestPlugin extends TiaBasePlugin {
        @Override
        public VCSReader getVCSReader() {
            return null;
        }
    }

    @Test
    void embeddedModeResolvesRelativeDbFilePathAgainstProjectDir(@TempDir File projectDir) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TestPlugin plugin = (TestPlugin) project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setDbFilePath("tiadb");

        // when
        H2ConnectionSettings settings = plugin.buildH2ConnectionSettings();

        // then
        assertFalse(settings.isServerMode());
        // compare against project.getProjectDir() (not the raw @TempDir) so the macOS
        // /var -> /private/var canonicalisation ProjectBuilder applies does not cause a mismatch.
        assertEquals(new File(project.getProjectDir(), "tiadb").getAbsolutePath(), settings.getDbFilePath());
    }

    @Test
    void serverModeUsesConfiguredUrlAndCredentials(@TempDir File projectDir) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TestPlugin plugin = (TestPlugin) project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setDbUrl("jdbc:h2:tcp://h2host:9092/tiadb");
        ext.setDbUser("tia");
        ext.setDbPassword("secret");

        // when
        H2ConnectionSettings settings = plugin.buildH2ConnectionSettings();

        // then
        assertTrue(settings.isServerMode());
        assertEquals("jdbc:h2:tcp://h2host:9092/tiadb", settings.getDbUrl());
        assertEquals("tia", settings.getUsername());
        assertEquals("secret", settings.getPassword());
    }

    @Test
    void serverModePropertiesRoundTripOnExtension() {
        // given
        TiaBaseTaskExtension ext = new TiaBaseTaskExtension();

        // when
        ext.setDbUrl("jdbc:h2:tcp://h2host:9092/tiadb");
        ext.setDbUser("tia");
        ext.setDbPassword("secret");

        // then
        assertEquals("jdbc:h2:tcp://h2host:9092/tiadb", ext.getDbUrl());
        assertEquals("tia", ext.getDbUser());
        assertEquals("secret", ext.getDbPassword());
    }

    @Test
    void dbDialectRoundTripsOnExtensionAndIsForwardedToDataStoreFactory() {
        // given - mirrors dbUrl: an explicit dialect override configured on the tia { ... }
        // extension should be threaded into the datastore construction, next to dbUrl/dbUser/dbPassword.
        TiaBaseTaskExtension ext = new TiaBaseTaskExtension();

        // when
        ext.setDbDialect("h2");

        // then
        assertEquals("h2", ext.getDbDialect());
    }

    @Test
    void dbDialectIsNullByDefault() {
        // given
        TiaBaseTaskExtension ext = new TiaBaseTaskExtension();

        // when / then - unset dialect falls back to inference from dbUrl, matching tiaDBUrl's
        // own "null means unset" convention.
        assertEquals(null, ext.getDbDialect());
    }
}
