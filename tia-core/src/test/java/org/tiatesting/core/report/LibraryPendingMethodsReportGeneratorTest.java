package org.tiatesting.core.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LibraryPendingMethodsReportGenerator} - the table listing behind the
 * {@code library-pending-methods} task / mojo. Uses a temp-directory embedded H2 store seeded
 * through the public persist API, matching the other report tests.
 */
class LibraryPendingMethodsReportGeneratorTest {

    private static final String LIB = "com.example:mylib";

    private H2DataStore dataStore;
    private File tempDir;
    private LibraryPendingMethodsReportGenerator generator;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-lib-pending-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
        generator = new LibraryPendingMethodsReportGenerator();
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
     * The report renders one row per pending method carrying the batch's publish seq and
     * version plus the method's tracked name and line range, resolved via the targeted
     * method-id read.
     */
    @Test
    void rendersOneRowPerPendingMethodWithTrackedDetail() {
        // given a tracked library, tracked methods 10/20 and two stamped publishes
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/mylib", null));
        seedTrackedMethods();
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0-SNAPSHOT", "H1", "c1", 1000L),
                new HashSet<>(Arrays.asList(10)));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0-SNAPSHOT", "H2", "c2", 2000L),
                new HashSet<>(Arrays.asList(10, 20)));

        // when the report is generated
        String report = generator.generateLibraryPendingMethodsReport(dataStore, LIB);

        // then rows carry seq, version, id, method name and line range
        assertTrue(report.contains("Pending impacted methods for library " + LIB));
        assertTrue(report.contains("Seq | Version"));
        assertTrue(report.contains("com/example/Service.methodA.()V"));
        assertTrue(report.contains("com/example/Service.methodB.()V"));
        assertTrue(report.contains("2-8"), "the method's tracked line range must render");
        assertTrue(report.contains("10-20"), "the second method's line range must render");
    }

    /**
     * A pending method whose id is no longer in the tracked mapping (e.g. removed since the
     * stamp) renders dashes for its name and lines rather than failing.
     */
    @Test
    void rendersDashesForMethodsNoLongerTracked() {
        // given a stamped publish whose method id has no tracked row
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/mylib", null));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0", "H1", "c1", 1000L),
                new HashSet<>(Arrays.asList(999)));

        // when the report is generated
        String report = generator.generateLibraryPendingMethodsReport(dataStore, LIB);

        // then the row renders with the id and dashed method detail
        assertTrue(report.contains("999"));
        assertTrue(report.contains(" - "), "untracked method detail must render as dashes");
    }

    /**
     * A library with nothing pending reports {@code none}; unknown and blank coordinates get
     * their guidance messages.
     */
    @Test
    void reportsNoneNotTrackedAndUsage() {
        // given one tracked library with nothing pending
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/mylib", null));

        // when the report is generated for the tracked, an unknown and a blank coordinate
        String none = generator.generateLibraryPendingMethodsReport(dataStore, LIB);
        String unknown = generator.generateLibraryPendingMethodsReport(dataStore, "com.example:nope");
        String blank = generator.generateLibraryPendingMethodsReport(dataStore, null);

        // then each input gets its message
        assertTrue(none.contains("none"));
        assertTrue(unknown.contains("Library 'com.example:nope' is not tracked."));
        assertTrue(blank.contains("A library must be specified as groupId:artifactId."));
    }

    /**
     * Seed tracked methods 10 (lines 2-8) and 20 (lines 10-20) through the core persist API.
     */
    private void seedTrackedMethods() {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("seed");
        tiaData.setLastUpdated(Instant.now());
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(10, new MethodImpactTracker("com/example/Service.methodA.()V", 2, 8));
        methods.put(20, new MethodImpactTracker("com/example/Service.methodB.()V", 10, 20));
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistSourceMethods(methods);
    }
}
