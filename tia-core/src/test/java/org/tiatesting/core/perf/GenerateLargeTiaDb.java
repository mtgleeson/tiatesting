package org.tiatesting.core.perf;

import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Synthetic-data generator for a large Tia H2 database, intended to drive performance
 * profiling of {@code select-tests} and other read-path operations.
 *
 * <p>Shape defaults match a real-world reference project:
 * <ul>
 *     <li>1,000 test suites</li>
 *     <li>50,000 distinct source methods</li>
 *     <li>~936 source-class rows per test suite (≈936K total)</li>
 *     <li>~6 method-edges per source-class row (≈5.6M total)</li>
 * </ul>
 *
 * <p>Invocation via Gradle:
 * <pre>
 *   ./gradlew :tia-core:generateLargeTiaDb \
 *       -PoutDb=/tmp/tia-perf \
 *       -PtestSuites=1000 \
 *       -PsourceMethods=50000 \
 *       -PavgClassesPerSuite=936 \
 *       -PavgMethodsPerClass=6 \
 *       -Pseed=42 \
 *       -Pbranch=main
 * </pre>
 *
 * <p>The generator opens a raw H2 JDBC connection (no Tia connection-pool indirection)
 * and uses batched {@link PreparedStatement}s with autocommit off to populate the tables
 * directly. Schema creation is delegated to {@link H2DataStore} so the layout always
 * matches what Tia produces in normal operation.
 */
public final class GenerateLargeTiaDb {

    private GenerateLargeTiaDb() {
    }

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        System.out.println("GenerateLargeTiaDb starting with " + parsed);

        File dir = new File(parsed.outDb);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create output directory " + dir);
        }

        // Schema creation is triggered the first time getTiaData() is invoked on a missing DB
        // (readTiaDataFromDB checks tia_core's existence and creates all tables if absent).
        long t0 = System.currentTimeMillis();
        H2DataStore bootstrap = new H2DataStore(parsed.outDb, parsed.branch);
        bootstrap.getTiaData(true);
        System.out.println("Schema created in " + (System.currentTimeMillis() - t0) + " ms");

        try (Connection connection = openConnection(parsed.outDb, parsed.branch)) {
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("TRUNCATE TABLE tia_source_class_method");
                st.executeUpdate("TRUNCATE TABLE tia_source_class");
                st.executeUpdate("DELETE FROM tia_test_suite");
                st.executeUpdate("TRUNCATE TABLE tia_source_method");
                st.executeUpdate("DELETE FROM tia_core");
            }
            connection.commit();

            insertCoreRow(connection, parsed);
            int[] sourceMethodIds = insertSourceMethods(connection, parsed);
            insertTestSuitesAndMappings(connection, parsed, sourceMethodIds);
        }

        System.out.println("Total time: " + (System.currentTimeMillis() - t0) + " ms");
        System.out.println("DB written to " + parsed.outDb + "/tiadb-" + parsed.branch + ".mv.db");
    }

    private static Connection openConnection(String outDb, String branch) throws Exception {
        Class.forName("org.h2.Driver");
        // Mirror H2DataStore.buildJdbcUrl: <dir>/tiadb-<branch>, with the same page/cache tuning.
        long cacheSizeKB = Runtime.getRuntime().maxMemory() / 1024 / 2;
        long pageSizeByte = 1024 * 4 * 100;
        String url = "jdbc:h2:" + outDb + "/tiadb-" + branch
                + ";PAGE_SIZE=" + pageSizeByte + ";CACHE_SIZE=" + cacheSizeKB;
        return DriverManager.getConnection(url, "sa", "1234");
    }

    private static void insertCoreRow(Connection connection, Args args) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tia_core (commit_value, last_updated, num_runs, avg_run_time, num_success_runs, num_fail_runs)" +
                        " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "synthetic-commit-" + args.seed);
            ps.setObject(2, Instant.now());
            ps.setLong(3, 100);
            ps.setLong(4, 5_000);
            ps.setLong(5, 95);
            ps.setLong(6, 5);
            ps.executeUpdate();
        }
        connection.commit();
        System.out.println("tia_core seeded");
    }

    /**
     * Insert {@link Args#sourceMethods} rows into {@code tia_source_method} with realistic-shaped
     * names. The id is the {@code String.hashCode()} of the method name (matches Tia's
     * {@code MethodImpactTracker.hashCode()}). Collisions are detected and re-rolled.
     *
     * @return the dense array of generated method ids, used by the edge inserter.
     */
    private static int[] insertSourceMethods(Connection connection, Args args) throws Exception {
        long t0 = System.currentTimeMillis();
        Random rnd = new Random(args.seed);

        Set<Integer> usedIds = new HashSet<>(args.sourceMethods * 2);
        int[] ids = new int[args.sourceMethods];

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tia_source_method (id, method_name, line_number_start, line_number_end) VALUES (?, ?, ?, ?)")) {
            int batched = 0;
            for (int i = 0; i < args.sourceMethods; i++) {
                String name;
                int id;
                int attempt = 0;
                do {
                    int suffix = (attempt == 0) ? i : (args.sourceMethods + rnd.nextInt(Integer.MAX_VALUE - args.sourceMethods));
                    name = methodName(suffix);
                    id = name.hashCode();
                    attempt++;
                } while (!usedIds.add(id));
                ids[i] = id;

                int start = 1 + rnd.nextInt(800);
                int end = start + 2 + rnd.nextInt(60);

                ps.setInt(1, id);
                ps.setString(2, name);
                ps.setInt(3, start);
                ps.setInt(4, end);
                ps.addBatch();
                batched++;

                if (batched >= 5_000) {
                    ps.executeBatch();
                    connection.commit();
                    batched = 0;
                }
            }
            if (batched > 0) {
                ps.executeBatch();
                connection.commit();
            }
        }

        System.out.println("tia_source_method: " + args.sourceMethods + " rows in "
                + (System.currentTimeMillis() - t0) + " ms");
        return ids;
    }

    /** Deep-package prefix used by every synthetic name to mimic realistic enterprise code. */
    private static final String DEEP_PKG_PREFIX =
            "com/acme/synthetic/enterprise/division/department/teamone/productname/moduleone/";

    /**
     * Realistic JVM-style internal name for the synthetic source method. Stays well below the
     * 2000-char column limit and well above 100 chars on average so a 50K-row table contributes
     * a meaningful share of the on-disk size (matches real-world Java method signatures).
     */
    private static String methodName(int idx) {
        int pkg = idx % 64;
        int cls = (idx / 64) % 4096;
        int mthd = idx;
        return DEEP_PKG_PREFIX + "subpkg" + pkg + "/AbstractDefault"
                + classNameFlavour(cls) + "ServiceImpl" + cls + ".handle"
                + verbFlavour(mthd) + "Request" + mthd + ".("
                + "Ljava/lang/String;"
                + "Ljava/util/Map;"
                + "L" + DEEP_PKG_PREFIX + "context/UserSessionContext;"
                + "L" + DEEP_PKG_PREFIX + "context/RequestMetadata;"
                + "L" + DEEP_PKG_PREFIX + "api/result/OperationResult;"
                + "Ljava/util/concurrent/CompletableFuture;"
                + ")L" + DEEP_PKG_PREFIX + "domain/response/ServiceResponse;";
    }

    /**
     * Realistic source-file path for the {@code tia_source_class.source_filename} column.
     * The 940K-row table is the largest string contributor to DB size; keeping each row near
     * 180 chars roughly matches what real enterprise codebases look like (deep packages,
     * long class names with prefixes/suffixes).
     */
    private static String classFilename(int classIdx) {
        int pkg = classIdx % 64;
        return DEEP_PKG_PREFIX + "subpkg" + pkg + "/handler/AbstractDefault"
                + classNameFlavour(classIdx) + "ServiceConfigurationParserImpl"
                + classIdx + ".java";
    }

    /** Test-suite class name. Stored once per row in tia_test_suite (1K rows) — minor size impact, but kept consistent in style. */
    private static String testSuiteName(int suiteIdx) {
        int pkg = suiteIdx % 32;
        return DEEP_PKG_PREFIX.replace('/', '.') + "tests.subpkg" + pkg
                + ".AbstractIntegration" + classNameFlavour(suiteIdx)
                + "ServiceImplTest" + suiteIdx + "$NestedScenarioTest";
    }

    private static final String[] CLASS_FLAVOURS = {
            "User", "Session", "Account", "Order", "Payment", "Notification",
            "Catalog", "Inventory", "Shipment", "Fulfillment", "Subscription",
            "Authorization", "Configuration", "Document", "Workflow"
    };

    private static String classNameFlavour(int idx) {
        return CLASS_FLAVOURS[Math.floorMod(idx, CLASS_FLAVOURS.length)];
    }

    private static final String[] VERB_FLAVOURS = {
            "Process", "Validate", "Transform", "Persist", "Resolve", "Compute",
            "Aggregate", "Reconcile", "Dispatch", "Synchronize"
    };

    private static String verbFlavour(int idx) {
        return VERB_FLAVOURS[Math.floorMod(idx, VERB_FLAVOURS.length)];
    }

    /**
     * Insert test suites, their {@code tia_source_class} rows, and the
     * {@code tia_source_class_method} edges. Coverage size per suite is varied around
     * {@link Args#avgClassesPerSuite} (uniform ±50%) so the profile sees a mix of light/heavy
     * suites rather than identical fan-out everywhere.
     */
    private static void insertTestSuitesAndMappings(Connection connection, Args args, int[] sourceMethodIds) throws Exception {
        long t0 = System.currentTimeMillis();
        Random rnd = new Random(args.seed + 1);

        long totalClassRows = 0;
        long totalEdgeRows = 0;

        try (PreparedStatement insertSuite = connection.prepareStatement(
                "INSERT INTO tia_test_suite (name, num_runs, avg_run_time, num_success_runs, num_fail_runs) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
             PreparedStatement insertClass = connection.prepareStatement(
                     "INSERT INTO tia_source_class (tia_test_suite_id, source_filename) VALUES (?, ?)",
                     Statement.RETURN_GENERATED_KEYS);
             PreparedStatement insertEdge = connection.prepareStatement(
                     "INSERT INTO tia_source_class_method (tia_source_class_id, tia_source_method_id) VALUES (?, ?)")) {

            for (int s = 0; s < args.testSuites; s++) {
                String suiteName = testSuiteName(s);
                insertSuite.setString(1, suiteName);
                insertSuite.setLong(2, 50 + rnd.nextInt(200));
                insertSuite.setLong(3, 100 + rnd.nextInt(2_000));
                insertSuite.setLong(4, 45 + rnd.nextInt(180));
                insertSuite.setLong(5, rnd.nextInt(5));
                insertSuite.executeUpdate();
                long suiteId = generatedKey(insertSuite);

                int classesForSuite = jitter(args.avgClassesPerSuite, rnd);
                int batchedEdges = 0;

                for (int c = 0; c < classesForSuite; c++) {
                    int classIdx = rnd.nextInt(args.avgClassesPerSuite * 8);
                    String classFilename = classFilename(classIdx);
                    insertClass.setLong(1, suiteId);
                    insertClass.setString(2, classFilename);
                    insertClass.executeUpdate();
                    long classId = generatedKey(insertClass);
                    totalClassRows++;

                    int methodsForClass = jitter(args.avgMethodsPerClass, rnd);
                    Set<Integer> usedMethodIds = new HashSet<>(methodsForClass * 2);
                    for (int m = 0; m < methodsForClass; m++) {
                        int methodId;
                        int attempts = 0;
                        do {
                            methodId = sourceMethodIds[rnd.nextInt(sourceMethodIds.length)];
                            attempts++;
                        } while (!usedMethodIds.add(methodId) && attempts < 10);

                        insertEdge.setLong(1, classId);
                        insertEdge.setInt(2, methodId);
                        insertEdge.addBatch();
                        batchedEdges++;
                        totalEdgeRows++;

                        if (batchedEdges >= 10_000) {
                            insertEdge.executeBatch();
                            connection.commit();
                            batchedEdges = 0;
                        }
                    }
                }

                if (batchedEdges > 0) {
                    insertEdge.executeBatch();
                    connection.commit();
                }

                if ((s + 1) % 100 == 0 || s == args.testSuites - 1) {
                    System.out.printf("  suites=%d/%d  classRows=%d  edgeRows=%d  elapsed=%dms%n",
                            s + 1, args.testSuites, totalClassRows, totalEdgeRows,
                            System.currentTimeMillis() - t0);
                }
            }
        }

        System.out.println("tia_source_class: " + totalClassRows + " rows");
        System.out.println("tia_source_class_method: " + totalEdgeRows + " rows");
        System.out.println("Mappings inserted in " + (System.currentTimeMillis() - t0) + " ms");
    }

    private static long generatedKey(PreparedStatement ps) throws Exception {
        try (java.sql.ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new IllegalStateException("Expected a generated key from " + ps);
            }
            return keys.getLong(1);
        }
    }

    /** Uniform ±50% jitter around an average integer; floor of 1. */
    private static int jitter(int avg, Random rnd) {
        int half = Math.max(1, avg / 2);
        return Math.max(1, avg - half + rnd.nextInt(2 * half + 1));
    }

    private static final class Args {
        String outDb = "/tmp/tia-perf";
        String branch = "main";
        int testSuites = 1_000;
        int sourceMethods = 50_000;
        int avgClassesPerSuite = 936;
        int avgMethodsPerClass = 6;
        long seed = 42L;

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
                    case "out": a.outDb = absoluteOf(value); break;
                    case "branch": a.branch = value; break;
                    case "testSuites": a.testSuites = Integer.parseInt(value); break;
                    case "sourceMethods": a.sourceMethods = Integer.parseInt(value); break;
                    case "avgClassesPerSuite": a.avgClassesPerSuite = Integer.parseInt(value); break;
                    case "avgMethodsPerClass": a.avgMethodsPerClass = Integer.parseInt(value); break;
                    case "seed": a.seed = Long.parseLong(value); break;
                    default: throw new IllegalArgumentException("Unknown arg: " + key);
                }
            }
            return a;
        }

        private static String absoluteOf(String path) {
            File f = new File(path);
            return f.isAbsolute() ? f.getAbsolutePath() : new File(System.getProperty("user.dir"), path).getAbsolutePath();
        }

        @Override public String toString() {
            return "Args{outDb=" + outDb + ", branch=" + branch + ", testSuites=" + testSuites
                    + ", sourceMethods=" + sourceMethods + ", avgClassesPerSuite=" + avgClassesPerSuite
                    + ", avgMethodsPerClass=" + avgMethodsPerClass + ", seed=" + seed + "}";
        }
    }
}
