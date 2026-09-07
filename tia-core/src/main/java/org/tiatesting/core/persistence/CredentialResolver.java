package org.tiatesting.core.persistence;

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
}
