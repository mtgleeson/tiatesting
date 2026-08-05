package org.tiatesting.core.perf;

import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Manual profiler for the {@code tia_source_method} clear-out cost inside
 * {@code JdbcDataStore.persistSourceMethods}. On H2, that clear-out runs as {@code DELETE FROM}
 * rather than {@code TRUNCATE TABLE}: H2 2.2.224 implements {@code TRUNCATE TABLE} as DDL that
 * implicitly commits, so it is not undone by a later {@code rollback()} - unsuitable for a
 * clear-then-repopulate that must stay atomic with the rest of its transaction.
 * {@code DELETE FROM} genuinely participates in the transaction but is more expensive than
 * {@code TRUNCATE} at scale, since it is logged row-by-row rather than executed as a single DDL
 * operation. This profiler is how that cost was quantified - see the "Persist flow and crash
 * safety" chapter in {@code WIKI.md} for the correctness reasoning and the measured figures this
 * profiler reproduces.
 *
 * <p>Method: build a synthetic {@code Map<Integer, MethodImpactTracker>} of {@code rows} methods,
 * call {@code persistSourceMethods} once (untimed) against an empty embedded H2 DB to populate
 * the table, then call it again {@code repetitions} times, timing each call. Every timed call
 * clears the {@code rows} rows written by the previous call and re-inserts {@code rows} rows, so
 * each one measures the same steady-state "table already has rows in it" case that a real seal
 * hits on every run after the first.
 *
 * <p>Invocation via Gradle:
 * <pre>
 *   ./gradlew :tia-core:profileMethodCatalogueClear -Prows=200000 -Prepetitions=5
 * </pre>
 * Not part of the automated test suite (no assertions); invoke via {@code main}.
 */
public final class ProfileMethodCatalogueClear {

    private ProfileMethodCatalogueClear() {
    }

    /**
     * Entry point: parse the key=value args, populate the table once, then time
     * {@code repetitions} repeat calls of the clear-and-repopulate write and print each.
     *
     * @param args key=value pairs - see {@link Args} for the supported keys
     * @throws Exception on any IO/DB failure
     */
    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        System.out.println("ProfileMethodCatalogueClear starting with " + parsed);

        File dir = File.createTempFile("tia-method-clear-", "");
        dir.delete();
        dir.mkdirs();
        JdbcDataStore dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.getAbsolutePath())),
                BranchSchema.schemaName(parsed.branch));
        dataStore.getTiaData(true); // bootstrap schema

        Map<Integer, MethodImpactTracker> methods = buildSyntheticMethods(parsed.rows);

        // Untimed: populates the table so every timed call below clears an already-populated
        // table, not an empty one - the steady-state case a real seal hits after the first run.
        dataStore.persistSourceMethods(methods);

        for (int i = 1; i <= parsed.repetitions; i++) {
            long start = System.nanoTime();
            dataStore.persistSourceMethods(methods);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("  repetition %d/%d: %d ms (clear %d rows, insert %d rows)%n",
                    i, parsed.repetitions, elapsedMs, parsed.rows, parsed.rows);
        }

        dataStore.close();
        for (File f : dir.listFiles()) { f.delete(); }
        dir.delete();
    }

    /**
     * Build a synthetic method catalogue of the requested size, one distinct method per id.
     *
     * @param rows the number of synthetic methods to generate
     * @return a map of method id to a synthetic {@link MethodImpactTracker}, sized {@code rows}
     */
    private static Map<Integer, MethodImpactTracker> buildSyntheticMethods(int rows) {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>(rows * 2);
        for (int id = 1; id <= rows; id++) {
            methods.put(id, new MethodImpactTracker("com/example/Cls" + id + ".m.()V", 1, 5));
        }
        return methods;
    }

    private static final class Args {
        int rows = 200_000;
        int repetitions = 5;
        String branch = "perf";

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
                    case "rows": a.rows = Integer.parseInt(value); break;
                    case "repetitions": a.repetitions = Integer.parseInt(value); break;
                    case "branch": a.branch = value; break;
                    default: throw new IllegalArgumentException("Unknown arg: " + key);
                }
            }
            return a;
        }

        @Override public String toString() {
            return "Args{rows=" + rows + ", repetitions=" + repetitions + ", branch=" + branch + "}";
        }
    }
}
