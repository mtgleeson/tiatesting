package org.tiatesting.core.persistence;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Stages a resolved database password on disk so a forked test JVM can read it, for the one
 * transport that has no alternative.
 *
 * <p>Maven cannot set the forked test JVM's environment - it cannot inject Surefire's
 * {@code <environmentVariables>}, because Maven does not re-read plugin configuration mutated at
 * runtime - and every other channel to the fork becomes a system property, which
 * {@code ForkSystemProperties.applyToSystemProperties} republishes and Surefire then dumps into
 * {@code target/surefire-reports/TEST-*.xml}, the artifact CI publishes. Staging the value in a
 * file and forwarding only its path is what keeps the secret out of that report.
 *
 * <p>Three properties matter and each defeats a specific exposure. The file is owner-only, so
 * another local user cannot read it. It is created outside the build directory, so a CI job that
 * archives {@code target/} never captures it. And it is deleted when the build JVM exits, which is
 * possible here because the Maven JVM outlives every Surefire fork - so the secret's lifetime
 * really is the length of the build.
 *
 * <p>Only reached when the password was configured directly, through the build config parameter or
 * a {@code settings.xml} server entry. A build that supplies the password through
 * {@code tiaDBPasswordFile} or {@value CredentialResolver#ENV_DB_PASSWORD} never calls this and has
 * nothing staged on its behalf. See the credentials chapter in {@code WIKI.md}.
 */
public final class SecretFile {

    /** Prefix of the staged file's name, chosen so a leftover file is recognisable and sweepable. */
    private static final String FILE_PREFIX = "tia-db-password";

    private static final String FILE_SUFFIX = ".tmp";

    private SecretFile() {
    }

    /**
     * Write a secret to a new owner-only temporary file, registered for deletion when this JVM
     * exits. The caller forwards the returned path, never the secret itself.
     *
     * @param secret the value to stage, written verbatim with no trailing newline so it reads back
     *               byte-identical through
     *               {@link CredentialResolver#readPasswordFile(String)}
     * @return the path of the file written, to be forwarded to the forked test JVM
     * @throws IOException if the file cannot be created or written
     */
    public static Path write(final String secret) throws IOException {
        Path file = createOwnerOnlyFile();
        file.toFile().deleteOnExit();
        Files.write(file, secret.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * Create the empty temporary file with owner-only permissions.
     *
     * <p>On POSIX the permissions are applied as a creation attribute rather than set afterwards,
     * so the file is never briefly readable by anyone else. Windows has no POSIX view, so it falls
     * back to clearing access for everyone and restoring it for the owner, which leaves a very
     * short window but is the best the platform offers through {@link File}.
     *
     * @return the newly created empty file
     * @throws IOException if the file cannot be created
     */
    private static Path createOwnerOnlyFile() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            FileAttribute<Set<PosixFilePermission>> ownerOnly =
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            return Files.createTempFile(FILE_PREFIX, FILE_SUFFIX, ownerOnly);
        }
        Path file = Files.createTempFile(FILE_PREFIX, FILE_SUFFIX);
        File asFile = file.toFile();
        asFile.setReadable(false, false);
        asFile.setReadable(true, true);
        asFile.setWritable(false, false);
        asFile.setWritable(true, true);
        return file;
    }
}
