package org.tiatesting.core.perf;

import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual profiler for the seed mapping persist. Builds a synthetic in-memory mapping of the rough
 * shape of a large project, then times a full {@code persistTestSuites} against an empty embedded
 * H2 DB. Run before and after a change to quantify the persist speedup. Not part of the automated
 * test suite (no assertions); invoke via {@code main}.
 *
 * <p>Defaults: 5000 suites x ~196 classes/suite x ~6 methods/class -> ~980K classes / ~5.9M edges.
 * Distinct source methods are drawn from a bounded pool (capped at {@value #METHOD_POOL_CAP}) and
 * shared across classes, matching real projects where the edge count far exceeds the method count -
 * this keeps memory bounded. Run with a roomy heap for the default size (e.g. {@code -Xmx4g}).
 */
public final class ProfileSeedPersist {

    /** Upper bound on the number of distinct {@code tia_source_method} rows the harness generates. */
    private static final int METHOD_POOL_CAP = 200_000;

    private ProfileSeedPersist() { }

    /**
     * Build the synthetic mapping, persist it once against a fresh DB, and print the elapsed time.
     *
     * @param args optional: [numSuites] [classesPerSuite] [methodsPerClass] [serverJdbcUrl].
     *             When a server URL (e.g. {@code jdbc:h2:tcp://localhost:9119/./profdb}) is given,
     *             the persist runs against that H2 server instead of a local embedded file - the
     *             round-trip cost that dominates a real seed only shows up in server mode.
     * @throws Exception on any IO/DB failure
     */
    public static void main(String[] args) throws Exception {
        int numSuites = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int classesPerSuite = args.length > 1 ? Integer.parseInt(args[1]) : 196;
        int methodsPerClass = args.length > 2 ? Integer.parseInt(args[2]) : 6;
        String serverUrl = args.length > 3 ? args[3] : null;

        File dir = serverUrl == null ? File.createTempFile("tia-seed-persist-", "") : null;
        if (dir != null) { dir.delete(); dir.mkdirs(); }
        H2ConnectionSettings settings = serverUrl != null
                ? H2ConnectionSettings.server(serverUrl, "sa", "", "perf")
                : H2ConnectionSettings.embedded(dir.getAbsolutePath(), "perf");
        H2DataStore dataStore = new H2DataStore(settings);
        dataStore.getTiaData(true); // bootstrap schema

        // Bounded pool of distinct methods (shared across classes), so the edge count can be huge
        // without one MethodImpactTracker per edge. Each class gets a window of methodsPerClass
        // consecutive ids (mod poolSize), which is distinct within the class.
        long totalEdges = (long) numSuites * classesPerSuite * methodsPerClass;
        int poolSize = (int) Math.max(methodsPerClass, Math.min(METHOD_POOL_CAP, totalEdges));
        Map<Integer, MethodImpactTracker> methods = new HashMap<>(poolSize * 2);
        for (int id = 1; id <= poolSize; id++) {
            methods.put(id, new MethodImpactTracker("com/example/Cls" + id + ".m.()V", 1, 5));
        }

        Map<String, TestSuiteTracker> suites = new HashMap<>(numSuites * 2);
        long methodCursor = 0;
        for (int s = 0; s < numSuites; s++) {
            TestSuiteTracker suite = new TestSuiteTracker("com.example.Suite" + s + "Test");
            List<ClassImpactTracker> classes = new ArrayList<>(classesPerSuite);
            for (int c = 0; c < classesPerSuite; c++) {
                MethodIdSet ids = new MethodIdSet();
                for (int m = 0; m < methodsPerClass; m++) {
                    ids.appendForBulkBuild((int) (methodCursor++ % poolSize) + 1);
                }
                ids.finishBulkBuild();
                classes.add(new ClassImpactTracker("com/example/Cls" + s + "_" + c + ".java", ids));
            }
            suite.setClassesImpacted(classes);
            suites.put(suite.getName(), suite);
        }
        dataStore.persistSourceMethods(methods);

        System.out.println("Persisting " + numSuites + " suites (" + (long) numSuites * classesPerSuite
                + " classes, " + (long) numSuites * classesPerSuite * methodsPerClass + " edges)...");
        long start = System.currentTimeMillis();
        dataStore.persistTestSuites(suites);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("persistTestSuites took " + (elapsed / 1000.0) + "s");

        dataStore.close();
        if (dir != null) {
            for (File f : dir.listFiles()) { f.delete(); }
            dir.delete();
        }
    }
}
