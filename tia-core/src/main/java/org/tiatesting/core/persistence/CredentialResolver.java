package org.tiatesting.core.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.function.Function;

/**
 * Resolves the database username and password from the channels a build can supply them through,
 * for every SQL dialect rather than only H2.
 *
 * <p>Kept out of {@code H2ConnectionSettings} so that {@link DataStoreFactory#fromConfig} can
 * resolve once, before it branches on the dialect. The fallback previously lived inside the H2
 * branch, which meant a Postgres or generic-JDBC build never reached it: those dialects silently
 * ignored the environment variables and were forced to put the password in checked-in build
 * config. See the credential-resolution section of the "H2 connection modes" chapter in
 * {@code WIKI.md}.
 */
public final class CredentialResolver {

    /** Environment variable consulted for the database username when none is configured. */
    public static final String ENV_DB_USER = "TIA_DB_USER";

    /** Environment variable consulted for the database password when none is configured. */
    public static final String ENV_DB_PASSWORD = "TIA_DB_PASSWORD";

    /**
     * System property naming the file a forked test JVM should read the database password from.
     *
     * <p>The single source of truth for this name: the build-tool plugins write it and the fork
     * reads it back, and both must agree on the literal or the fork silently falls back to an empty
     * password. A path is not a secret, so unlike the password itself this key is safe in
     * {@code fork.properties} and harmless in the Surefire report XML.
     */
    public static final String PROP_DB_PASSWORD_FILE = "tiaDBPasswordFile";

    private CredentialResolver() {
    }

    /**
     * Resolve the password, distinguishing "not configured" from "configured as empty". Only
     * {@code null} means not configured: any non-null value, including an empty string, is used
     * verbatim and is never trimmed, since leading or trailing whitespace can be significant in a
     * password. That lets a build pin an empty password and bypass the environment fallback.
     *
     * @param configured the configured password, or {@code null} when not configured
     * @param env        lookup from environment variable name to value, e.g. {@code System::getenv}
     * @return the configured password verbatim if non-null, else the
     *         {@value #ENV_DB_PASSWORD} value if it is non-blank, else an empty password
     */
    public static String resolvePassword(final String configured, final Function<String, String> env) {
        if (configured != null) {
            return configured;
        }
        String envValue = env.apply(ENV_DB_PASSWORD);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }
        return "";
    }

    /**
     * Resolve the username by precedence: the configured value if non-blank, then
     * {@value #ENV_DB_USER} if non-blank, then {@code defaultUser}. Unlike the password, a blank
     * username is meaningless to every supported database and so counts as not configured.
     *
     * @param configured  the configured username, or {@code null}/blank when not configured
     * @param defaultUser the value to fall back to, or {@code null} for no default. H2 passes its
     *                    {@code tia} convention; other dialects pass {@code null} rather than
     *                    inventing a username the vendor never agreed to
     * @param env         lookup from environment variable name to value, e.g. {@code System::getenv}
     * @return the resolved username, which is {@code null} only when {@code defaultUser} is
     */
    public static String resolveUser(final String configured, final String defaultUser,
                                     final Function<String, String> env) {
        if (configured != null && !configured.trim().isEmpty()) {
            return configured;
        }
        String envValue = env.apply(ENV_DB_USER);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }
        return defaultUser;
    }

    /**
     * Read a password from a file the user owns, such as a Docker or Kubernetes mounted secret.
     *
     * <p>Tia never writes this file, so it is the one channel where a configured password reaches
     * the forked test JVM without Tia staging a copy of the secret itself: only the path is
     * forwarded. See the credentials chapter in {@code WIKI.md}.
     *
     * <p>Exactly one trailing newline is stripped, along with a carriage return preceding it,
     * because {@code echo secret > pw.txt} appends one and sending the newline on to the database
     * is the usual failure of this pattern. Nothing else is trimmed, preserving the rule that
     * whitespace inside a password is significant. An empty file means an explicitly empty
     * password, matching what an empty configured value means.
     *
     * @param path filesystem path of the password file
     * @return the password read from the file, with at most one trailing line ending removed
     * @throws IllegalStateException if the path is malformed, or the file does not exist or cannot
     *                               be read. Named in the message, because a password file that
     *                               silently resolved to an empty password would surface only as an
     *                               opaque authentication failure much later
     */
    public static String readPasswordFile(final String path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Paths.get(path));
        } catch (IOException | InvalidPathException e) {
            throw new IllegalStateException("Tia could not read the database password file at '"
                    + path + "'. Check the path is correct and readable by the build user.", e);
        }
        return stripSingleTrailingLineEnding(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Remove at most one trailing line ending, handling both {@code \n} and {@code \r\n}. Only
     * one is removed so that a password genuinely ending in a newline survives a file that carries
     * two.
     *
     * @param contents the file contents as read
     * @return the contents with a single trailing line ending removed, if there was one
     */
    private static String stripSingleTrailingLineEnding(final String contents) {
        if (!contents.endsWith("\n")) {
            return contents;
        }
        String withoutNewline = contents.substring(0, contents.length() - 1);
        if (withoutNewline.endsWith("\r")) {
            return withoutNewline.substring(0, withoutNewline.length() - 1);
        }
        return withoutNewline;
    }
}
