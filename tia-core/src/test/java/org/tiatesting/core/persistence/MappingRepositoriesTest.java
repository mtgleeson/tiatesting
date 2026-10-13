package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link MappingWriter} and {@link MappingReader} directly, without the
 * {@link JdbcDataStore} facade: what the writer stores, the reader's targeted select-tests
 * queries read back.
 */
class MappingRepositoriesTest {

    @Test
    void targetedReadsResolveFilesToMethodsAndMethodsToSuites(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given a writer that has stored two methods and one suite covering both
            MappingWriter writer = new MappingWriter(db.dialect, db.connections, db.schemaManager);
            MappingReader reader = new MappingReader(db.dialect, db.connections, db.schemaManager);
            Map<Integer, MethodImpactTracker> methods = new HashMap<>();
            methods.put(1, new MethodImpactTracker("com.Foo.a()V", 10, 12));
            methods.put(2, new MethodImpactTracker("com.Foo.b()V", 14, 16));
            writer.persistSourceMethods(methods);
            writer.persistTestSuites(Collections.singletonMap("FooTest",
                    suite("FooTest", new ClassImpactTracker("com/Foo.java", Arrays.asList(1, 2)))));

            // when the reader runs the two targeted select-tests queries
            Map<String, Map<Integer, MethodImpactTracker>> byFile =
                    reader.getMethodsTrackedForFiles(Collections.singleton("com/Foo.java"));
            Map<Integer, Set<String>> suitesByMethod =
                    reader.getTestSuitesForMethods(new HashSet<>(Arrays.asList(1, 2)));

            // then the file resolves to both methods and each method to the covering suite
            assertEquals(methods.keySet(), byFile.get("com/Foo.java").keySet());
            assertEquals(Collections.singleton("FooTest"), suitesByMethod.get(1));
            assertEquals(Collections.singleton("FooTest"), suitesByMethod.get(2));
            assertEquals(1, reader.getNumTestSuites());
            assertEquals(2, reader.getNumSourceMethods());
        }
    }

    @Test
    void deleteTestSuitesRemovesTheSuiteAndItsFailedEntry(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given a stored suite that is also in the failed set
            MappingWriter writer = new MappingWriter(db.dialect, db.connections, db.schemaManager);
            MappingReader reader = new MappingReader(db.dialect, db.connections, db.schemaManager);
            writer.persistTestSuites(Collections.singletonMap("FooTest", suite("FooTest")));
            writer.persistTestSuitesFailed(Collections.emptySet(), Collections.singleton("FooTest"));

            // when the suite is deleted
            writer.deleteTestSuites(Collections.singleton("FooTest"));

            // then neither its row nor its failed entry remains
            assertTrue(reader.getTestSuitesTracked().isEmpty());
            assertTrue(reader.getTestSuitesFailed().isEmpty());
        }
    }

    @Test
    void readTestSuiteCoverageReturnsOnlyTheNamedSuites(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given two stored suites with coverage
            MappingWriter writer = new MappingWriter(db.dialect, db.connections, db.schemaManager);
            MappingReader reader = new MappingReader(db.dialect, db.connections, db.schemaManager);
            Map<String, TestSuiteTracker> suites = new HashMap<>();
            suites.put("ATest", suite("ATest", new ClassImpactTracker("com/A.java", Collections.singletonList(1))));
            suites.put("BTest", suite("BTest", new ClassImpactTracker("com/B.java", Collections.singletonList(2))));
            writer.persistTestSuites(suites);

            // when one suite's coverage is read
            Map<String, List<ClassImpactTracker>> coverage = reader.readTestSuiteCoverage(Collections.singleton("ATest"));

            // then only that suite comes back, with its class
            assertEquals(Collections.singleton("ATest"), coverage.keySet());
            assertEquals("com/A.java", coverage.get("ATest").get(0).getSourceFilename());
        }
    }

    /**
     * Build a suite tracker carrying the given class coverage.
     *
     * @param name the suite name
     * @param classes the classes the suite covers
     * @return the tracker
     */
    private static TestSuiteTracker suite(String name, ClassImpactTracker... classes) {
        TestSuiteTracker tracker = new TestSuiteTracker(name);
        tracker.setClassesImpacted(Arrays.asList(classes));
        return tracker;
    }
}
