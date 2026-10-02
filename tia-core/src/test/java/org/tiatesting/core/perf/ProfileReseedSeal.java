package org.tiatesting.core.perf;

import org.tiatesting.core.model.CoreStatsIncrement;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.SealedRunData;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

/**
 * Manual profiler for the re-seed clear-out inside {@code JdbcDataStore.persistSealedRunData}.
 * Run it against a database produced by {@code generateLargeTiaDb} - see the "Profiling
 * select-tests against a synthetic large DB" chapter in {@code WIKI.md}. It is destructive: the
 * re-seed deletes the suites it marks unobserved, so regenerate the database before each run.
 *
 * <p>Method: flag {@code observedPercent}% of the suites unsealed, modelling the suites a re-seed
 * run rewrote, then time one re-seed seal (method catalogue rewrite plus the clear-out). For
 * comparison it then times an ordinary seal of the same catalogue, which pays the catalogue
 * rewrite without the clear-out. The difference is the clear-out's cost.
 *
 * <p>Invocation via Gradle:
 * <pre>
 *   ./gradlew :tia-core:generateLargeTiaDb -PoutDb=/tmp/tia-perf
 *   ./gradlew :tia-core:profileReseedSeal -PoutDb=/tmp/tia-perf -PobservedPercent=50
 * </pre>
 * Not part of the automated test suite (no assertions); invoke via {@code main}.
 */
public final class ProfileReseedSeal {

    private ProfileReseedSeal() {
    }

    /**
     * Entry point: flag the observed suites, then time a re-seed seal and an ordinary seal and
     * print the row counts before and after.
     *
     * @param args key=value pairs - see {@link Args} for the supported keys
     * @throws Exception on any IO/DB failure
     */
    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        System.out.println("ProfileReseedSeal starting with " + parsed);

        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(parsed.outDb));
        try (Connection connection = branchConnection(provider, parsed);
             Statement statement = connection.createStatement()) {
            int flagged = statement.executeUpdate("UPDATE tia_test_suite SET unsealed = (MOD(id, 100) < "
                    + parsed.observedPercent + ")");
            System.out.println("  flagged " + flagged + " suite row(s); before: " + counts(statement));
        }

        JdbcDataStore dataStore = new JdbcDataStore(new H2Dialect(), provider,
                BranchSchema.schemaName(parsed.branch, null));
        Map<Integer, MethodImpactTracker> methods = dataStore.getMethodsTracked();

        long reseedMs = timeSeal(dataStore, methods, true);
        System.out.println("  re-seed seal: " + reseedMs + " ms");
        long ordinaryMs = timeSeal(dataStore, methods, false);
        System.out.println("  ordinary seal: " + ordinaryMs + " ms");
        try (Connection connection = branchConnection(provider, parsed);
             Statement statement = connection.createStatement()) {
            System.out.println("  after: " + counts(statement));
        }
        dataStore.close();
    }

    /**
     * Time one seal of the given catalogue under a fresh commit value.
     *
     * @param dataStore the store to seal
     * @param methods the method catalogue to write
     * @param reseed whether the seal re-seeds
     * @return the elapsed time in ms
     */
    private static long timeSeal(JdbcDataStore dataStore, Map<Integer, MethodImpactTracker> methods,
                                 boolean reseed) {
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue("profile-" + System.nanoTime());
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());
        long start = System.nanoTime();
        dataStore.persistSealedRunData(new SealedRunData(tiaData, methods, Collections.emptyList(),
                Collections.emptyList(), new ArrayList<>(), CoreStatsIncrement.none(), reseed));
        return (System.nanoTime() - start) / 1_000_000;
    }

    /**
     * Open a connection through Tia's own provider, so it uses the same URL settings as the store,
     * with the branch schema selected.
     *
     * @param provider the connection provider the store also uses
     * @param args the parsed arguments
     * @return the connection
     * @throws SQLException if the connection fails
     */
    private static Connection branchConnection(H2ConnectionProvider provider, Args args) throws SQLException {
        Connection connection = provider.get();
        try (Statement statement = connection.createStatement()) {
            statement.execute(new H2Dialect().selectSchemaSql(BranchSchema.schemaName(args.branch, null)));
        }
        return connection;
    }

    /**
     * Count the mapping tables' rows.
     *
     * @param statement a statement on the branch schema
     * @return the counts, formatted for printing
     * @throws SQLException if a count fails
     */
    private static String counts(Statement statement) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (String table : new String[]{"tia_test_suite", "tia_source_class", "tia_source_class_method",
                "tia_source_method"}) {
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                sb.append(table).append('=').append(rs.getLong(1)).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private static final class Args {
        String outDb = "/tmp/tia-perf";
        String branch = "main";
        int observedPercent = 50;

        /**
         * Parse key=value harness arguments.
         *
         * @param argv the raw program arguments
         * @return the populated args holder
         */
        static Args parse(String[] argv) {
            Args a = new Args();
            for (String raw : argv) {
                int eq = raw.indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException("Expected key=value, got: " + raw);
                }
                String key = raw.substring(0, eq);
                String value = raw.substring(eq + 1);
                switch (key) {
                    case "outDb": a.outDb = value; break;
                    case "branch": a.branch = value; break;
                    case "observedPercent": a.observedPercent = Integer.parseInt(value); break;
                    default: throw new IllegalArgumentException("Unknown arg: " + key);
                }
            }
            return a;
        }

        @Override public String toString() {
            return "Args{outDb=" + outDb + ", branch=" + branch + ", observedPercent=" + observedPercent + "}";
        }
    }
}
