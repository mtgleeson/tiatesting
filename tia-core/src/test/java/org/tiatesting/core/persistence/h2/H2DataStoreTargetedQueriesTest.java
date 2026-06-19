package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the targeted select-tests queries in {@link H2DataStore}:
 * {@code getMethodsTrackedForFiles} (the changed-files-to-tracked-methods lookup: changed files to candidate methods) and
 * {@code getTestSuitesForMethods} (the methods-to-covering-suites lookup: impacted methods to covering suites).
 * Uses a temp-directory embedded H2 database per test, seeded through the public persist API.
 */
class H2DataStoreTargetedQueriesTest {

    private static final String FOO_FILE = "com/example/Foo.java";
    private static final String BAR_FILE = "com/example/Bar.java";
    private static final String BAZ_FILE = "com/example/Baz.java";

    private H2DataStore dataStore;
    private H2ConnectionSettings settings;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        settings = H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test");
        dataStore = new H2DataStore(settings);
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * Seed the DB with a small mapping:
     * method 1 (Foo.java 10-20), method 2 (Foo.java 30-40), method 3 (Bar.java 5-15),
     * method 4 (Baz.java 1-10). SuiteOne covers Foo[1,2] + Bar[3]; SuiteTwo covers Foo[1];
     * SuiteThree covers Baz[4]. Method 1 is covered by two suites so the changed-files-to-tracked-methods
     * dedup and the methods-to-covering-suites multi-suite resolution are both exercised.
     */
    private void seedMapping() {
        // First contact bootstraps the schema (same pattern as the other H2 datastore tests).
        dataStore.getTiaData(true);

        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(1, new MethodImpactTracker("com/example/Foo.methodA.()V", 10, 20));
        methods.put(2, new MethodImpactTracker("com/example/Foo.methodB.()V", 30, 40));
        methods.put(3, new MethodImpactTracker("com/example/Bar.methodC.()V", 5, 15));
        methods.put(4, new MethodImpactTracker("com/example/Baz.methodD.()V", 1, 10));
        dataStore.persistSourceMethods(methods);

        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put("SuiteOne", buildSuite("SuiteOne",
                new ClassImpactTracker(FOO_FILE, Arrays.asList(1, 2)),
                new ClassImpactTracker(BAR_FILE, Collections.singletonList(3))));
        suites.put("SuiteTwo", buildSuite("SuiteTwo",
                new ClassImpactTracker(FOO_FILE, Collections.singletonList(1))));
        suites.put("SuiteThree", buildSuite("SuiteThree",
                new ClassImpactTracker(BAZ_FILE, Collections.singletonList(4))));
        dataStore.persistTestSuites(suites);
    }

    /**
     * Build a test-suite tracker with the given name and covered classes.
     *
     * @param name the suite name
     * @param classes the source classes (and their method ids) the suite covers
     * @return the populated tracker
     */
    private TestSuiteTracker buildSuite(String name, ClassImpactTracker... classes) {
        TestSuiteTracker suite = new TestSuiteTracker(name);
        suite.setClassesImpacted(Arrays.asList(classes));
        return suite;
    }

    @Test
    void filesToMethodsLookupReturnsTrackedMethodsWithLineRangesForRequestedFile() {
        // given
        seedMapping();

        // when
        Map<String, Map<Integer, MethodImpactTracker>> result =
                dataStore.getMethodsTrackedForFiles(Collections.singleton(FOO_FILE));

        // then
        assertEquals(Collections.singleton(FOO_FILE), result.keySet());
        Map<Integer, MethodImpactTracker> fooMethods = result.get(FOO_FILE);
        assertEquals(new HashSet<>(Arrays.asList(1, 2)), fooMethods.keySet());
        assertEquals("com/example/Foo.methodA.()V", fooMethods.get(1).getMethodName());
        assertEquals(10, fooMethods.get(1).getLineNumberStart());
        assertEquals(20, fooMethods.get(1).getLineNumberEnd());
    }

    @Test
    void filesToMethodsLookupDeduplicatesMethodsCoveredByMultipleSuites() {
        // given - method 1 in Foo.java is covered by both SuiteOne and SuiteTwo
        seedMapping();

        // when
        Map<String, Map<Integer, MethodImpactTracker>> result =
                dataStore.getMethodsTrackedForFiles(Collections.singleton(FOO_FILE));

        // then - one entry per method, not one per covering suite
        assertEquals(2, result.get(FOO_FILE).size());
    }

    @Test
    void filesToMethodsLookupReturnsEntriesForEachRequestedTrackedFile() {
        // given
        seedMapping();

        // when
        Map<String, Map<Integer, MethodImpactTracker>> result =
                dataStore.getMethodsTrackedForFiles(new HashSet<>(Arrays.asList(FOO_FILE, BAR_FILE)));

        // then
        assertEquals(new HashSet<>(Arrays.asList(FOO_FILE, BAR_FILE)), result.keySet());
        assertEquals(Collections.singleton(3), result.get(BAR_FILE).keySet());
    }

    @Test
    void filesToMethodsLookupOmitsUntrackedFilesAndHandlesEmptyInput() {
        // given
        seedMapping();

        // when
        Map<String, Map<Integer, MethodImpactTracker>> untracked =
                dataStore.getMethodsTrackedForFiles(Collections.singleton("com/example/Unknown.java"));
        Map<String, Map<Integer, MethodImpactTracker>> empty =
                dataStore.getMethodsTrackedForFiles(Collections.emptySet());
        Map<String, Map<Integer, MethodImpactTracker>> nullInput =
                dataStore.getMethodsTrackedForFiles(null);

        // then
        assertTrue(untracked.isEmpty());
        assertTrue(empty.isEmpty());
        assertTrue(nullInput.isEmpty());
    }

    @Test
    void methodsToSuitesLookupResolvesAllCoveringSuites() {
        // given
        seedMapping();

        // when
        Map<Integer, Set<String>> result =
                dataStore.getTestSuitesForMethods(new HashSet<>(Arrays.asList(1, 3, 4)));

        // then
        assertEquals(new HashSet<>(Arrays.asList("SuiteOne", "SuiteTwo")), result.get(1));
        assertEquals(Collections.singleton("SuiteOne"), result.get(3));
        assertEquals(Collections.singleton("SuiteThree"), result.get(4));
    }

    @Test
    void methodsToSuitesLookupOmitsUnknownMethodIdsAndHandlesEmptyInput() {
        // given
        seedMapping();

        // when
        Map<Integer, Set<String>> unknownId =
                dataStore.getTestSuitesForMethods(Collections.singleton(999));
        Map<Integer, Set<String>> empty = dataStore.getTestSuitesForMethods(Collections.emptySet());
        Map<Integer, Set<String>> nullInput = dataStore.getTestSuitesForMethods(null);

        // then
        assertTrue(unknownId.isEmpty());
        assertTrue(empty.isEmpty());
        assertTrue(nullInput.isEmpty());
    }

    @Test
    void methodsToSuitesLookupChunksInputsLargerThanInClauseLimit() {
        // given - 1500 requested ids forces two IN-clause chunks; only the seeded ids match
        seedMapping();
        Set<Integer> ids = new HashSet<>();
        for (int i = 1; i <= 1500; i++) {
            ids.add(i);
        }

        // when
        Map<Integer, Set<String>> result = dataStore.getTestSuitesForMethods(ids);

        // then
        assertEquals(new HashSet<>(Arrays.asList(1, 2, 3, 4)), result.keySet());
    }

    @Test
    void targetedQueriesBootstrapSchemaOnFreshDatabase() {
        // given - a brand new datastore that has never loaded or persisted anything

        // when - targeted queries are the first ever contact with the DB
        Map<String, Map<Integer, MethodImpactTracker>> filesToMethods =
                dataStore.getMethodsTrackedForFiles(Collections.singleton(FOO_FILE));
        Map<Integer, Set<String>> methodsToSuites =
                dataStore.getTestSuitesForMethods(Collections.singleton(1));

        // then - no exception and empty results; the schema was created on first contact
        assertTrue(filesToMethods.isEmpty());
        assertTrue(methodsToSuites.isEmpty());
    }

    @Test
    void schemaBootstrapCreatesTargetedQueryIndexes() throws Exception {
        // given
        seedMapping();

        // when - query H2's metadata for the two indexes backing the targeted queries
        Set<String> indexNames = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(dataStore.getJdbcUrl(),
                settings.getUsername(), settings.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME IN (?, ?)")) {
            statement.setString(1, "IDX_SOURCE_CLASS_FILENAME");
            statement.setString(2, "IDX_SOURCE_CLASS_METHOD_METHOD_ID");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    indexNames.add(resultSet.getString("INDEX_NAME"));
                }
            }
        }

        // then
        assertTrue(indexNames.contains("IDX_SOURCE_CLASS_FILENAME"),
                "expected the changed-files-to-tracked-methods filename index to exist");
        assertTrue(indexNames.contains("IDX_SOURCE_CLASS_METHOD_METHOD_ID"),
                "expected the methods-to-covering-suites method-id index to exist");
        assertFalse(indexNames.isEmpty());
    }
}
