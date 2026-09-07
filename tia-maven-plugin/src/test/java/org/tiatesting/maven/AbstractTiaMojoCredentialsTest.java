package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
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
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover how the Maven goals resolve the database password and hand it to the forked test JVM.
 *
 * <p>The guarantee these tests exist to pin: the password must never appear in
 * {@code fork.properties}. Every key written there is republished as a system property in the fork
 * by {@code ForkSystemProperties.applyToSystemProperties}, and Surefire dumps the fork's system
 * properties into {@code target/surefire-reports/TEST-*.xml} - the artifact CI publishes. A path is
 * not a secret, so forwarding one is safe; forwarding the value is not.
 */
class AbstractTiaMojoCredentialsTest {

    private static final String SECRET = "hunter2-super-secret";
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
     * Build a mojo pointed at this test's build directory, with Tia enabled.
     *
     * @return the mojo under test
     */
    private TestMojo mojo() {
        TestMojo mojo = new TestMojo();
        mojo.tiaBuildDir = buildDir.getAbsolutePath();
        mojo.tiaEnabled = true;
        return mojo;
    }

    /**
     * Read back the fork properties file the mojo wrote.
     *
     * @return the properties the forked test JVM would be handed
     * @throws IOException if the file cannot be read
     */
    private Properties forkProperties() throws IOException {
        return ForkSystemProperties.read(new File(buildDir, "fork.properties"));
    }

    /**
     * Write a password file the way a user or an orchestrator would, including the trailing newline
     * {@code echo} appends.
     *
     * @param password the password the file should hold
     * @return the path of the file written
     * @throws IOException if the file cannot be written
     */
    private Path userPasswordFile(final String password) throws IOException {
        Path file = tempDir.toPath().resolve("user-password.txt");
        Files.write(file, (password + "\n").getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * The core guarantee. A password configured in the POM must reach the fork as a path, never as
     * a value, because a value here is published in the Surefire report XML.
     */
    @Test
    void forkPropertiesCarryAPathAndNeverThePassword() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBUrl = SHARED_DB_URL;
        mojo.tiaDBPassword = SECRET;

        // when
        mojo.writeForkPropertiesFile(null);

        // then
        Properties props = forkProperties();
        assertNull(props.getProperty("tiaDBPassword"),
                "the password must not be a key in fork.properties");
        assertFalse(props.toString().contains(SECRET),
                "no value in fork.properties may contain the password: " + props);
        String path = props.getProperty(CredentialResolver.PROP_DB_PASSWORD_FILE);
        assertNotNull(path, "the fork needs a path to read the password from");
        assertEquals(SECRET, CredentialResolver.readPasswordFile(path));
    }

    /**
     * A password the user already keeps in a file is referenced where it lies, so Tia stages no
     * second copy of the secret anywhere.
     */
    @Test
    void aUserSuppliedPasswordFileIsForwardedUnchanged() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBUrl = SHARED_DB_URL;
        Path userFile = userPasswordFile(SECRET);
        mojo.tiaDBPasswordFile = userFile.toString();

        // when
        mojo.writeForkPropertiesFile(null);

        // then
        assertEquals(userFile.toString(),
                forkProperties().getProperty(CredentialResolver.PROP_DB_PASSWORD_FILE));
    }

    /**
     * A build that supplies the password through the environment needs nothing forwarded at all:
     * the Surefire fork inherits the Maven JVM's environment.
     */
    @Test
    void nothingIsForwardedWhenNoPasswordIsConfigured() throws Exception {
        // given
        TestMojo mojo = mojo();

        // when
        mojo.writeForkPropertiesFile(null);

        // then
        assertNull(forkProperties().getProperty(CredentialResolver.PROP_DB_PASSWORD_FILE));
    }

    /**
     * An explicit empty password means "this database has no password" and must bypass the
     * environment fallback on both sides of the fork boundary. If nothing were forwarded, a
     * TIA_DB_PASSWORD that happened to be set in the environment would win inside the fork while
     * the build JVM used the empty value, and the two would connect as different users.
     */
    @Test
    void anExplicitlyEmptyPasswordIsStillForwardedSoTheForkCannotFallBackToTheEnvironment()
            throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBUrl = SHARED_DB_URL;
        mojo.tiaDBPassword = "";

        // when
        mojo.writeForkPropertiesFile(null);

        // then
        String path = forkProperties().getProperty(CredentialResolver.PROP_DB_PASSWORD_FILE);
        assertNotNull(path, "an explicit empty password must still be forwarded, not omitted");
        assertEquals("", CredentialResolver.readPasswordFile(path));
    }

    @Test
    void resolveDbPasswordPrefersTheConfiguredParameter() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBPassword = SECRET;
        mojo.tiaDBPasswordFile = userPasswordFile("from-the-file").toString();

        // when
        String resolved = mojo.resolveDbPassword();

        // then
        assertEquals(SECRET, resolved);
    }

    @Test
    void resolveDbPasswordReadsThePasswordFileWhenNoParameterIsSet() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBPasswordFile = userPasswordFile(SECRET).toString();

        // when
        String resolved = mojo.resolveDbPassword();

        // then
        assertEquals(SECRET, resolved, "the trailing newline echo appends must be stripped");
    }

    /**
     * Maven leaves an unresolvable expression in place rather than erroring, so a POM carrying
     * {@code <tiaDBPassword>${env.TIA_DB_PASSWORD}</tiaDBPassword>} on a machine where the variable
     * is unset hands Tia the literal text. Sending that to the database surfaces as an opaque
     * authentication failure, so fail here instead and say why.
     */
    @Test
    void anUnresolvedMavenExpressionFailsTheBuild() {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBPassword = "${env.TIA_DB_PASSWORD}";

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class,
                mojo::resolveDbPassword);

        // then
        assertTrue(thrown.getMessage().contains("${env.TIA_DB_PASSWORD}"),
                "the message must quote the literal so the cause is obvious: " + thrown.getMessage());
    }

    /**
     * A password that merely contains a dollar sign is a real password, not an unresolved
     * expression, so the guard must not reject it.
     */
    @Test
    void aPasswordContainingADollarSignIsNotMistakenForAnExpression() throws Exception {
        // given
        TestMojo mojo = mojo();
        mojo.tiaDBPassword = "p${a}ss";

        // when
        String resolved = mojo.resolveDbPassword();

        // then
        assertEquals("p${a}ss", resolved);
    }

    /**
     * Concrete agent mojo for the test, supplying only the members the real wrapper plugins supply.
     * Nothing here reaches the VCS or a datastore: these tests drive the credential resolution and
     * the fork-properties write directly.
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
