package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsProblem;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.agent.ForkSystemProperties;
import org.tiatesting.core.persistence.CredentialResolver;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@code tiaDBServerId}: taking the database credentials from a {@code <server>} entry in
 * {@code ~/.m2/settings.xml} through Maven's own {@link SettingsDecrypter}.
 *
 * <p>This is what lets the reference live in a committed parent POM while the secret stays on each
 * developer's machine, and it is the only route on which Maven's password encryption applies -
 * {@code mvn --encrypt-password} covers {@code <server>} and {@code <proxy>} passwords, never an
 * arbitrary {@code <properties>} entry.
 *
 * <p>A POM naming a server id that a machine has no {@code <server>} for falls through to the next
 * channel rather than failing, which is what lets one parent POM serve both CI (on
 * {@value CredentialResolver#ENV_DB_PASSWORD}) and developer machines.
 */
class AbstractTiaMojoServerIdTest {

    private static final String SERVER_ID = "tia-db";
    private static final String SHARED_DB_URL = "jdbc:h2:tcp://localhost:9092/tiadb";

    @TempDir
    File tempDir;

    private File buildDir;

    @BeforeEach
    void setUp() {
        buildDir = new File(tempDir, "build");
        buildDir.mkdirs();
    }

    /**
     * Build a mojo pointed at this test's build directory, with an empty settings and a decrypter
     * that passes values through untouched - the shape of a machine with no encryption configured.
     *
     * @return the mojo under test
     */
    private TestMojo mojo() {
        TestMojo mojo = new TestMojo();
        mojo.tiaBuildDir = buildDir.getAbsolutePath();
        mojo.tiaEnabled = true;
        mojo.settings = new Settings();
        mojo.settingsDecrypter = passThroughDecrypter();
        return mojo;
    }

    /**
     * Add a {@code <server>} entry to the mojo's settings.
     *
     * @param mojo     the mojo whose settings to populate
     * @param id       the server id
     * @param username the entry's username, or null for none
     * @param password the entry's password, as stored (encrypted or not)
     */
    private static void withServer(final TestMojo mojo, final String id, final String username,
                                   final String password) {
        Server server = new Server();
        server.setId(id);
        server.setUsername(username);
        server.setPassword(password);
        mojo.settings.addServer(server);
    }

    /**
     * A decrypter that returns the server unchanged with no problems, standing in for a machine
     * where the stored password is plaintext or decrypts cleanly.
     *
     * @return a pass-through settings decrypter
     */
    private static SettingsDecrypter passThroughDecrypter() {
        return request -> result(request.getServers().get(0), Collections.emptyList());
    }

    /**
     * A decrypter that fails the way the real one does: it reports the failure only through
     * {@code getProblems()} and hands back the server with its password still encrypted. Verified
     * against Maven 3.9 - a missing {@code settings-security.xml} produces exactly this, and the
     * build otherwise succeeds with the ciphertext used as the password.
     *
     * @param message the problem message the real decrypter would report
     * @return a settings decrypter that reports an ERROR problem and returns the ciphertext
     */
    private static SettingsDecrypter failingDecrypter(final String message) {
        return request -> result(request.getServers().get(0),
                Collections.singletonList(new DefaultSettingsProblem(message,
                        SettingsProblem.Severity.ERROR, "settings.xml", -1, -1, null)));
    }

    /**
     * Build a decryption result carrying one server and the given problems.
     *
     * @param server   the server the decrypter hands back
     * @param problems the problems the decrypter reports
     * @return the decryption result
     */
    private static SettingsDecryptionResult result(final Server server,
                                                   final List<SettingsProblem> problems) {
        return new SettingsDecryptionResult() {
            @Override
            public Server getServer() {
                return server;
            }

            @Override
            public List<Server> getServers() {
                return Collections.singletonList(server);
            }

            @Override
            public Proxy getProxy() {
                return null;
            }

            @Override
            public List<Proxy> getProxies() {
                return Collections.emptyList();
            }

            @Override
            public List<SettingsProblem> getProblems() {
                return problems;
            }
        };
    }

    @Test
    void takesThePasswordFromTheNamedServerEntry() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBServerId = SERVER_ID;
        withServer(mojo, SERVER_ID, "tia", "server-secret");

        // when
        String resolved = mojo.resolveDbPassword();

        // then
        assertEquals("server-secret", resolved);
    }

    @Test
    void takesTheUsernameFromTheNamedServerEntry() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBServerId = SERVER_ID;
        withServer(mojo, SERVER_ID, "server-user", "server-secret");

        // when
        String resolved = mojo.resolveDbUser();

        // then
        assertEquals("server-user", resolved);
    }

    @Test
    void anExplicitlyConfiguredUserOutranksTheServerEntry() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBServerId = SERVER_ID;
        mojo.tiaDBUser = "configured-user";
        withServer(mojo, SERVER_ID, "server-user", "server-secret");

        // when
        String resolved = mojo.resolveDbUser();

        // then
        assertEquals("configured-user", resolved);
    }

    @Test
    void anExplicitlyConfiguredPasswordOutranksTheServerEntry() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBServerId = SERVER_ID;
        mojo.tiaDBPassword = "configured-secret";
        withServer(mojo, SERVER_ID, "tia", "server-secret");

        // when
        String resolved = mojo.resolveDbPassword();

        // then
        assertEquals("configured-secret", resolved);
    }

    /**
     * The property that lets one parent POM serve both CI and developer machines: a server id no
     * machine defines is not an error, it simply falls through to the next channel.
     */
    @Test
    void aServerIdWithNoMatchingEntryFallsThroughToTheNextChannel() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBServerId = SERVER_ID;
        Path file = tempDir.toPath().resolve("pw.txt");
        Files.write(file, "from-the-file\n".getBytes(StandardCharsets.UTF_8));
        mojo.tiaDBPasswordFile = file.toString();

        // when
        String resolved = mojo.resolveDbPassword();

        // then
        assertEquals("from-the-file", resolved);
    }

    /**
     * The important failure mode. {@link SettingsDecrypter} reports a decryption failure only
     * through {@code getProblems()} and hands back the raw ciphertext as the password, so without
     * this check Tia would send "{pazwCbxc...=}" to the database and the user would see only an
     * opaque authentication error.
     */
    @Test
    void aDecryptionFailureFailsTheBuildInsteadOfUsingTheCiphertext() {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBServerId = SERVER_ID;
        withServer(mojo, SERVER_ID, "tia", "{pazwCbxcAkoHENVlUV6ueF2GUQ162IC/H0sJwkarOic=}");
        mojo.settingsDecrypter = failingDecrypter(
                "Failed to decrypt password for server tia-db: FileNotFoundException");

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class,
                mojo::resolveDbPassword);

        // then
        assertTrue(thrown.getMessage().contains(SERVER_ID), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("settings-security.xml"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("pazwCbxc"),
                "the failure message must not echo the ciphertext: " + thrown.getMessage());
    }

    /**
     * A password reaching the build through a server entry still has to reach the fork, and it must
     * do so as a path like every other configured password - never as a value in fork.properties.
     */
    @Test
    void aServerEntryPasswordIsStagedForTheForkAsAPath() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBUrl = SHARED_DB_URL;
        mojo.tiaDBServerId = SERVER_ID;
        withServer(mojo, SERVER_ID, "tia", "server-secret");

        // when
        mojo.writeForkPropertiesFile(null);

        // then
        java.util.Properties props = ForkSystemProperties.read(new File(buildDir, "fork.properties"));
        assertFalse(props.toString().contains("server-secret"),
                "the password must not appear in fork.properties: " + props);
        String path = props.getProperty(CredentialResolver.PROP_DB_PASSWORD_FILE);
        assertNotNull(path, "a server-entry password still needs a path forwarded to the fork");
        assertEquals("server-secret", CredentialResolver.readPasswordFile(path));
    }

    /**
     * A username reaching the build through a server entry has to reach the fork too. Forwarding
     * the raw parameter instead of the resolved value leaves the fork falling back to TIA_DB_USER
     * and then to H2's "tia" default, so the build JVM and the fork connect as different users -
     * invisible on H2 with the username "tia", and a plain authentication failure on Postgres.
     */
    @Test
    void aServerEntryUsernameReachesTheFork() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBUrl = SHARED_DB_URL;
        mojo.tiaDBServerId = SERVER_ID;
        withServer(mojo, SERVER_ID, "server-user", "server-secret");

        // when
        mojo.writeForkPropertiesFile(null);

        // then
        java.util.Properties props = ForkSystemProperties.read(new File(buildDir, "fork.properties"));
        assertEquals("server-user", props.getProperty("tiaDBUser"));
    }

    /**
     * An explicitly configured username still wins on the fork side, matching the build JVM.
     */
    @Test
    void anExplicitlyConfiguredUserIsTheOneForwardedToTheFork() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBUrl = SHARED_DB_URL;
        mojo.tiaDBServerId = SERVER_ID;
        mojo.tiaDBUser = "configured-user";
        withServer(mojo, SERVER_ID, "server-user", "server-secret");

        // when
        mojo.writeForkPropertiesFile(null);

        // then
        java.util.Properties props = ForkSystemProperties.read(new File(buildDir, "fork.properties"));
        assertEquals("configured-user", props.getProperty("tiaDBUser"));
    }

    /**
     * Concrete agent mojo for the test. Nothing here reaches the VCS or a datastore: these tests
     * drive the credential resolution and the fork-properties write directly.
     */
    private static final class TestMojo extends AbstractTiaAgentMojo {

        private final MavenProject mavenProject;

        private TestMojo() {
            Model model = new Model();
            model.setBuild(new Build());
            this.mavenProject = new MavenProject(model);
        }

        /**
         * @return never called by these tests
         */
        @Override
        public VCSReader getVCSReader() {
            throw new UnsupportedOperationException("these tests do not reach the VCS");
        }

        /**
         * @return a bare Maven project carrying an empty build section
         */
        @Override
        public MavenProject getProject() {
            return mavenProject;
        }

        /**
         * @return a placeholder agent jar path, since no JVM is forked in these tests
         */
        @Override
        File getAgentJarFile() {
            return new File("tia-agent.jar");
        }

        /**
         * @return the agent artifact name the wrapper plugin would supply
         */
        @Override
        public String getAgentArtifactName() {
            return "org.tiatesting:tia-junit5-agent";
        }

        /**
         * @return an empty artifact map, unused because {@link #getAgentJarFile()} is overridden
         */
        @Override
        public Map<String, Artifact> getPluginArtifactMap() {
            return Collections.emptyMap();
        }
    }
}
