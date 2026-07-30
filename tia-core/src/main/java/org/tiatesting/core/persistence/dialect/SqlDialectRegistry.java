package org.tiatesting.core.persistence.dialect;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the {@link SqlDialect} Tia should use for a given JDBC URL, or an explicit override id.
 * Supports H2 and Postgres (see the pluggable-datastore WIKI chapter); a further future dialect is
 * added by extending {@link #SUPPORTED_IDS} and the two lookup branches below, so both the
 * override path and the URL-sniffing path stay in sync with what is actually supported.
 */
public final class SqlDialectRegistry {

    /**
     * The stable ids this registry can resolve, in the order they should be listed in error
     * messages. Single source of truth for "what dialects does Tia support" - keep it in sync
     * with the lookups in {@link #forUrl(String, String)}.
     */
    private static final List<String> SUPPORTED_IDS = Collections.unmodifiableList(Arrays.asList("h2", "postgres"));

    /**
     * The fully-qualified JDBC driver class each non-H2 dialect id needs on the classpath.
     * H2's driver is bundled with Tia so it is deliberately absent here; every other dialect is a
     * separate two-classpath dependency (test-scope + Tia plugin, see the pluggable-datastore WIKI
     * chapter) that a caller may have forgotten to add. Kept next to {@link #SUPPORTED_IDS} so a
     * future dialect (e.g. MySQL) adds one line to each.
     */
    private static final Map<String, String> DRIVER_CLASS_NAMES;

    static {
        Map<String, String> driverClassNames = new LinkedHashMap<>();
        driverClassNames.put("postgres", "org.postgresql.Driver");
        DRIVER_CLASS_NAMES = Collections.unmodifiableMap(driverClassNames);
    }

    private SqlDialectRegistry() {
    }

    /**
     * Look up the fully-qualified JDBC driver class name a non-H2 dialect needs on the classpath.
     *
     * @param dialectId the dialect id, e.g. {@code "postgres"}
     * @return the driver class name, or {@code null} if {@code dialectId} has no registered driver
     *         (e.g. {@code "h2"}, whose driver is bundled with Tia)
     */
    public static String driverClassName(final String dialectId) {
        return DRIVER_CLASS_NAMES.get(dialectId);
    }

    /**
     * Resolve the {@link SqlDialect} to use, preferring an explicit override id over sniffing the
     * JDBC URL's scheme.
     *
     * @param jdbcUrl        the configured JDBC URL, or {@code null}/blank for embedded-mode H2
     * @param dialectOverride an explicit dialect id (e.g. {@code "h2"}), or {@code null}/blank to
     *                        infer the dialect from {@code jdbcUrl}
     * @return the resolved {@link SqlDialect}
     * @throws IllegalArgumentException if {@code dialectOverride} names an unknown id, or if
     *         {@code jdbcUrl} does not match a supported dialect's URL scheme
     */
    public static SqlDialect forUrl(final String jdbcUrl, final String dialectOverride) {
        if (dialectOverride != null && !dialectOverride.trim().isEmpty()) {
            return forId(dialectOverride.trim());
        }
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty() || jdbcUrl.trim().startsWith("jdbc:h2")) {
            return new H2Dialect();
        }
        if (jdbcUrl.trim().startsWith("jdbc:postgresql")) {
            return new PostgresDialect();
        }
        throw new IllegalArgumentException("Unsupported JDBC URL '" + jdbcUrl
                + "'. Supported dialects: " + SUPPORTED_IDS);
    }

    /**
     * Look up a dialect by its explicit id.
     *
     * @param id the dialect id, e.g. {@code "h2"}
     * @return the matching {@link SqlDialect}
     * @throws IllegalArgumentException if {@code id} does not match a supported dialect
     */
    private static SqlDialect forId(final String id) {
        if ("h2".equalsIgnoreCase(id)) {
            return new H2Dialect();
        }
        if ("postgres".equalsIgnoreCase(id)) {
            return new PostgresDialect();
        }
        throw new IllegalArgumentException("Unsupported Tia dialect '" + id
                + "'. Supported dialects: " + SUPPORTED_IDS);
    }
}
