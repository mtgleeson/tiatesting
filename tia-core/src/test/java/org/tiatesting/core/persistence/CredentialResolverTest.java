package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CredentialResolver}, covering the precedence between a configured value,
 * the environment fallback and the default, and the null-vs-empty rule that lets a build pin an
 * explicitly empty password.
 */
class CredentialResolverTest {

    /**
     * Build an environment lookup holding the given database password.
     *
     * @param password the value {@value CredentialResolver#ENV_DB_PASSWORD} should return
     * @return an environment map to be used as a lookup via {@code ::get}
     */
    private static Map<String, String> envWithPassword(final String password) {
        Map<String, String> env = new HashMap<>();
        env.put(CredentialResolver.ENV_DB_PASSWORD, password);
        return env;
    }

    @Test
    void resolvePasswordPrefersTheConfiguredValueOverTheEnvironment() {
        // given
        Map<String, String> env = envWithPassword("envsecret");

        // when
        String resolved = CredentialResolver.resolvePassword("configured", env::get);

        // then
        assertEquals("configured", resolved);
    }

    /**
     * An explicitly configured empty password must be honoured verbatim rather than falling back to
     * the environment, which is what lets a build pin an empty password and bypass
     * {@value CredentialResolver#ENV_DB_PASSWORD}.
     */
    @Test
    void resolvePasswordPrefersExplicitEmptyOverTheEnvironment() {
        // given
        Map<String, String> env = envWithPassword("envsecret");

        // when
        String resolved = CredentialResolver.resolvePassword("", env::get);

        // then
        assertEquals("", resolved);
    }

    @Test
    void resolvePasswordFallsBackToTheEnvironmentWhenNotConfigured() {
        // given
        Map<String, String> env = envWithPassword("envsecret");

        // when
        String resolved = CredentialResolver.resolvePassword(null, env::get);

        // then
        assertEquals("envsecret", resolved);
    }

    @Test
    void resolvePasswordGivesAnEmptyPasswordWhenNeitherIsSet() {
        // given
        Map<String, String> env = new HashMap<>();

        // when
        String resolved = CredentialResolver.resolvePassword(null, env::get);

        // then
        assertEquals("", resolved);
    }

    @Test
    void resolvePasswordTreatsABlankEnvironmentValueAsUnset() {
        // given
        Map<String, String> env = envWithPassword("   ");

        // when
        String resolved = CredentialResolver.resolvePassword(null, env::get);

        // then
        assertEquals("", resolved);
    }

    /**
     * Leading and trailing whitespace can be significant in a password, so a configured value is
     * never trimmed - unlike the username, where blank means "not configured".
     */
    @Test
    void resolvePasswordNeverTrimsAConfiguredValue() {
        // given
        Map<String, String> env = envWithPassword("envsecret");

        // when
        String resolved = CredentialResolver.resolvePassword("  ", env::get);

        // then
        assertEquals("  ", resolved);
    }

    @Test
    void resolveUserPrefersTheConfiguredValueOverTheEnvironment() {
        // given
        Map<String, String> env = new HashMap<>();
        env.put(CredentialResolver.ENV_DB_USER, "envuser");

        // when
        String resolved = CredentialResolver.resolveUser("configured", "tia", env::get);

        // then
        assertEquals("configured", resolved);
    }

    /**
     * Unlike the password, a blank username is meaningless to every supported database, so it
     * counts as not configured and falls through to the environment.
     */
    @Test
    void resolveUserTreatsABlankConfiguredValueAsUnset() {
        // given
        Map<String, String> env = new HashMap<>();
        env.put(CredentialResolver.ENV_DB_USER, "envuser");

        // when
        String resolved = CredentialResolver.resolveUser("  ", "tia", env::get);

        // then
        assertEquals("envuser", resolved);
    }

    @Test
    void resolveUserFallsBackToTheDefaultWhenNeitherIsSet() {
        // given
        Map<String, String> env = new HashMap<>();

        // when
        String resolved = CredentialResolver.resolveUser(null, "tia", env::get);

        // then
        assertEquals("tia", resolved);
    }

    /**
     * The non-H2 case. A dialect with no username convention of its own passes a null default, so
     * Tia leaves the username unset rather than inventing one the vendor never agreed to.
     */
    @Test
    void resolveUserReturnsNullWhenThereIsNoDefault() {
        // given
        Map<String, String> env = new HashMap<>();

        // when
        String resolved = CredentialResolver.resolveUser(null, null, env::get);

        // then
        assertNull(resolved);
    }

    /**
     * Write the given bytes to a file in the temporary directory.
     *
     * @param dir      the temporary directory to write into
     * @param contents the exact bytes to write, with no line ending added
     * @return the path of the file written
     * @throws Exception if the file cannot be written
     */
    private static Path passwordFile(final Path dir, final String contents) throws Exception {
        Path file = dir.resolve("pw.txt");
        Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * The rule that bites in practice: {@code echo secret > pw.txt} appends a newline, and sending
     * "secret\n" to the database is the classic failure of the password-file pattern.
     */
    @Test
    void readPasswordFileStripsExactlyOneTrailingNewline(@TempDir Path dir) throws Exception {
        // given
        Path file = passwordFile(dir, "secret\n");

        // when
        String password = CredentialResolver.readPasswordFile(file.toString());

        // then
        assertEquals("secret", password);
    }

    @Test
    void readPasswordFileStripsACarriageReturnBeforeTheNewline(@TempDir Path dir) throws Exception {
        // given
        Path file = passwordFile(dir, "secret\r\n");

        // when
        String password = CredentialResolver.readPasswordFile(file.toString());

        // then
        assertEquals("secret", password);
    }

    /**
     * Only one line ending is stripped, so a password that genuinely ends in a newline survives
     * when the file carries two.
     */
    @Test
    void readPasswordFileStripsOnlyOneOfTwoTrailingNewlines(@TempDir Path dir) throws Exception {
        // given
        Path file = passwordFile(dir, "secret\n\n");

        // when
        String password = CredentialResolver.readPasswordFile(file.toString());

        // then
        assertEquals("secret\n", password);
    }

    @Test
    void readPasswordFileLeavesAFileWithNoTrailingNewlineUnchanged(@TempDir Path dir) throws Exception {
        // given
        Path file = passwordFile(dir, "secret");

        // when
        String password = CredentialResolver.readPasswordFile(file.toString());

        // then
        assertEquals("secret", password);
    }

    /**
     * Nothing but the single trailing line ending is removed, keeping the rule that whitespace
     * inside a password is significant.
     */
    @Test
    void readPasswordFileNeverTrimsSurroundingWhitespace(@TempDir Path dir) throws Exception {
        // given
        Path file = passwordFile(dir, " secret \n");

        // when
        String password = CredentialResolver.readPasswordFile(file.toString());

        // then
        assertEquals(" secret ", password);
    }

    /**
     * An empty file means an explicitly empty password, matching what an empty configured value
     * means, rather than falling through to the environment.
     */
    @Test
    void readPasswordFileTreatsAnEmptyFileAsAnEmptyPassword(@TempDir Path dir) throws Exception {
        // given
        Path file = passwordFile(dir, "");

        // when
        String password = CredentialResolver.readPasswordFile(file.toString());

        // then
        assertEquals("", password);
    }

    @Test
    void readPasswordFileFailsLoudlyAndNamesThePathWhenTheFileIsMissing(@TempDir Path dir) {
        // given
        String missing = dir.resolve("does-not-exist.txt").toString();

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CredentialResolver.readPasswordFile(missing));

        // then
        assertTrue(thrown.getMessage().contains(missing),
                "the message must name the path so the misconfiguration is obvious: "
                        + thrown.getMessage());
    }

    @Test
    void readPasswordFileFailsWhenThePathIsADirectory(@TempDir Path dir) throws Exception {
        // given
        Path directory = dir.resolve("a-directory");
        Files.createDirectory(directory);

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CredentialResolver.readPasswordFile(directory.toString()));

        // then
        assertTrue(thrown.getMessage().contains(directory.toString()));
    }

    @Test
    void passwordFilePropertyNameIsTheOneTheForkReads() {
        // given
        String expected = "tiaDBPasswordFile";

        // when
        String actual = CredentialResolver.PROP_DB_PASSWORD_FILE;

        // then
        assertEquals(expected, actual, "the build plugins and the fork must agree on this literal");
    }
}
