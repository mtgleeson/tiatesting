package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the batched mapping-insert path in {@link H2DataStore}: classes and edges round-trip
 * correctly, {@code tia_source_class} ids are assigned application-side from {@code MAX(id)+1}, the
 * identity sequence is reseated afterward, and a re-persist of one suite updates only that suite.
 */
class H2DataStoreBatchedPersistTest {

    private H2DataStore dataStore;
    private H2ConnectionSettings settings;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-batched-", "");
        tempDir.delete();
        tempDir.mkdirs();
        settings = H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test");
        dataStore = new H2DataStore(settings);
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) { f.delete(); }
            tempDir.delete();
        }
    }

    /** Build a tracker for one suite covering the given (filename -> method ids) classes. */
    private TestSuiteTracker suite(String name, Map<String, int[]> classes) {
        TestSuiteTracker t = new TestSuiteTracker(name);
        List<ClassImpactTracker> impacted = new ArrayList<>();
        for (Map.Entry<String, int[]> e : classes.entrySet()) {
            MethodIdSet ids = new MethodIdSet();
            for (int id : e.getValue()) { ids.appendForBulkBuild(id); }
            ids.finishBulkBuild();
            impacted.add(new ClassImpactTracker(e.getKey(), ids));
        }
        t.setClassesImpacted(impacted);
        return t;
    }

    private void seedMethods(int... ids) {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        for (int id : ids) { methods.put(id, new MethodImpactTracker("com/example/C" + id + ".m.()V", 1, 5)); }
        dataStore.persistSourceMethods(methods);
    }

    @Test
    void persistsClassesAndEdges_andRoundTripsThroughFullLoad() {
        // given
        seedMethods(1, 2, 3);
        Map<String, int[]> classes = new HashMap<>();
        classes.put("com/example/Foo.java", new int[]{1, 2});
        classes.put("com/example/Bar.java", new int[]{3});
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put("com.example.FooTest", suite("com.example.FooTest", classes));

        // when
        dataStore.persistTestSuites(suites);
        TiaData loaded = dataStore.getTiaData(true);

        // then - the suite's two tracked classes and their method ids are read back
        TestSuiteTracker round = loaded.getTestSuitesTracked().get("com.example.FooTest");
        assertEquals(2, round.getClassesImpacted().size());
        int totalEdges = round.getClassesImpacted().stream().mapToInt(c -> c.getMethodsImpacted().size()).sum();
        assertEquals(3, totalEdges);
    }

    @Test
    void assignsContiguousIdsAndReseatsIdentity() throws Exception {
        // given
        seedMethods(1, 2);
        Map<String, int[]> classes = new HashMap<>();
        classes.put("com/example/Foo.java", new int[]{1});
        classes.put("com/example/Bar.java", new int[]{2});
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put("com.example.FooTest", suite("com.example.FooTest", classes));

        // when
        dataStore.persistTestSuites(suites);

        // then - ids are 1..2, and a subsequent auto-increment insert gets id 3 (identity reseated)
        try (Connection c = DriverManager.getConnection(dataStore.getJdbcUrl(),
                settings.getUsername(), settings.getPassword());
             Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT MIN(id), MAX(id), COUNT(*) FROM tia_source_class");
            rs.next();
            assertEquals(1, rs.getLong(1));
            assertEquals(2, rs.getLong(2));
            assertEquals(2, rs.getLong(3));
            st.executeUpdate("INSERT INTO tia_source_class (tia_test_suite_id, source_filename) "
                    + "VALUES (999, 'x')", Statement.RETURN_GENERATED_KEYS);
            ResultSet keys = st.getGeneratedKeys();
            keys.next();
            assertTrue(keys.getLong(1) >= 3, "identity must be reseated past the app-assigned ids");
        }
    }

    @Test
    void rePersistOneSuite_updatesOnlyThatSuite() {
        // given - two suites persisted
        seedMethods(1, 2, 3, 4);
        Map<String, TestSuiteTracker> first = new HashMap<>();
        first.put("com.example.ATest", suite("com.example.ATest", singleClass("com/example/A.java", 1, 2)));
        first.put("com.example.BTest", suite("com.example.BTest", singleClass("com/example/B.java", 3)));
        dataStore.persistTestSuites(first);

        // when - re-persist only ATest with a different class/methods
        Map<String, TestSuiteTracker> update = new HashMap<>();
        update.put("com.example.ATest", suite("com.example.ATest", singleClass("com/example/A2.java", 4)));
        dataStore.persistTestSuites(update);

        // then - ATest now maps A2.java(4); BTest still maps B.java(3)
        TiaData loaded = dataStore.getTiaData(true);
        TestSuiteTracker a = loaded.getTestSuitesTracked().get("com.example.ATest");
        assertEquals(1, a.getClassesImpacted().size());
        assertEquals("com/example/A2.java", a.getClassesImpacted().get(0).getSourceFilename());
        TestSuiteTracker b = loaded.getTestSuitesTracked().get("com.example.BTest");
        assertEquals("com/example/B.java", b.getClassesImpacted().get(0).getSourceFilename());
    }

    @Test
    void persistsSuiteLargerThanChunk_crossesFullChunkAndRemainder() {
        // given - a suite with 1200 classes (> the 1000-row insert chunk), each covering one method,
        // so both the class and edge multi-row inserts span a full chunk plus a 200-row remainder
        int[] methodIds = new int[1000];
        for (int i = 0; i < 1000; i++) { methodIds[i] = i + 1; }
        seedMethods(methodIds);
        Map<String, int[]> classes = new HashMap<>();
        for (int i = 0; i < 1200; i++) { classes.put("com/example/C" + i + ".java", new int[]{ (i % 1000) + 1 }); }
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put("com.example.BigTest", suite("com.example.BigTest", classes));

        // when
        dataStore.persistTestSuites(suites);
        TiaData loaded = dataStore.getTiaData(true);

        // then - all 1200 classes and 1200 edges round-trip across the chunk boundary
        TestSuiteTracker round = loaded.getTestSuitesTracked().get("com.example.BigTest");
        assertEquals(1200, round.getClassesImpacted().size());
        int totalEdges = round.getClassesImpacted().stream().mapToInt(c -> c.getMethodsImpacted().size()).sum();
        assertEquals(1200, totalEdges);
    }

    private Map<String, int[]> singleClass(String file, int... ids) {
        Map<String, int[]> m = new HashMap<>();
        m.put(file, ids);
        return m;
    }
}
