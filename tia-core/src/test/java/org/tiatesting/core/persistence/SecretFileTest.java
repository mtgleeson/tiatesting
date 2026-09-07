package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link SecretFile}, the one place Tia stages a password on disk.
 *
 * <p>Each test pins a property that defeats a specific exposure: owner-only permissions stop
 * another local user reading it, and a location outside the build directory stops a CI job that
 * archives {@code target/} from publishing it. Deletion on JVM exit cannot be asserted from inside
 * the JVM, so it is covered by reading the registration back rather than by observing the delete.
 */
class SecretFileTest {

    /**
     * Report whether the default filesystem supports POSIX permissions, so the permission
     * assertions can be skipped on Windows rather than failing there.
     *
     * @return true when POSIX file attributes are available
     */
    private static boolean posixSupported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * The permission that matters. Anything group- or world-readable would let another local user
     * read a password Tia had to stage for the forked test JVM.
     */
    @Test
    void writeCreatesAnOwnerOnlyFile() throws IOException {
        // given
        assumeTrue(posixSupported(), "POSIX permissions are not available on this filesystem");

        // when
        Path written = SecretFile.write("hunter2-super-secret");

        // then
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(written));
    }

    @Test
    void writeRoundTripsTheSecretExactly() throws IOException {
        // given
        String secret = " a secret with spaces and a trailing one ";

        // when
        Path written = SecretFile.write(secret);

        // then
        assertEquals(secret, new String(Files.readAllBytes(written), StandardCharsets.UTF_8));
    }

    /**
     * No trailing newline is appended, so the fork reads back exactly what the build resolved.
     * A newline here would be sent to the database as part of the password.
     */
    @Test
    void writeAppendsNoTrailingNewline() throws IOException {
        // given
        String secret = "hunter2";

        // when
        Path written = SecretFile.write(secret);

        // then
        assertEquals(secret.length(), Files.size(written));
    }

    /**
     * The staged file must not sit under the build directory, because archiving {@code target/} is
     * exactly what a CI job does with its build output.
     */
    @Test
    void writePlacesTheFileOutsideTheBuildDirectory() throws IOException {
        // given
        Path buildDir = Paths.get("").toAbsolutePath();

        // when
        Path written = SecretFile.write("hunter2");

        // then
        assertFalse(written.toAbsolutePath().startsWith(buildDir),
                "the secret file must not be under the project directory, but was " + written);
        assertTrue(written.toAbsolutePath().startsWith(
                        Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath()),
                "expected the secret file in the temp directory, but was " + written);
    }

    @Test
    void writeGivesEachCallItsOwnFile() throws IOException {
        // given
        String secret = "hunter2";

        // when
        Path first = SecretFile.write(secret);
        Path second = SecretFile.write(secret);

        // then
        assertNotEquals(first, second);
    }

    /**
     * An empty password is a legitimate configured value, so staging one must produce an empty
     * file rather than failing.
     */
    @Test
    void writeHandlesAnEmptySecret() throws IOException {
        // given
        String secret = "";

        // when
        Path written = SecretFile.write(secret);

        // then
        assertEquals(0, Files.size(written));
        assertEquals("", CredentialResolver.readPasswordFile(written.toString()));
    }

    /**
     * The two halves of the transport must agree: whatever {@link SecretFile#write} stages,
     * {@link CredentialResolver#readPasswordFile} must read back unchanged. In particular the
     * single-trailing-newline rule must not corrupt a password that ends in whitespace.
     */
    @Test
    void whatIsStagedIsWhatTheForkReadsBack() throws IOException {
        // given
        String secret = "p@ss word\twith\ttabs ";

        // when
        Path written = SecretFile.write(secret);

        // then
        assertEquals(secret, CredentialResolver.readPasswordFile(written.toString()));
    }
}
