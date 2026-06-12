package org.tiatesting.core.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LibrariesReportGenerator} - the console listing behind the libraries
 * task / mojo. Uses a temp-directory embedded H2 store seeded through the public persist API,
 * matching the other report tests.
 */
class LibrariesReportGeneratorTest {

    private H2DataStore dataStore;
    private File tempDir;
    private LibrariesReportGenerator generator;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-libraries-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
        generator = new LibrariesReportGenerator();
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

    @Test
    void reportsNoneWhenNoLibrariesTracked() {
        // given - an empty store

        // when
        String report = generator.generateLibrariesReport(dataStore);

        // then
        assertTrue(report.contains("Tracked libraries:"));
        assertTrue(report.contains("none"));
    }

    @Test
    void listsLibraryDetailsIncludingSourceDirsAndVersions() {
        // given
        TrackedLibrary library = new TrackedLibrary("com.example:mylib", "/projects/mylib",
                "/projects/mylib/src/main/java,/projects/mylib/src/main/groovy", "1.2.0", null);
        library.setLastReleasedLibraryVersion("1.3.0");
        dataStore.persistTrackedLibrary(library);

        // when
        String report = generator.generateLibrariesReport(dataStore);

        // then
        assertTrue(report.contains("com.example:mylib"));
        assertTrue(report.contains("Project dir: /projects/mylib"));
        assertTrue(report.contains("Source dirs: /projects/mylib/src/main/java,/projects/mylib/src/main/groovy"));
        assertTrue(report.contains("Last source-project version: 1.2.0"));
        assertTrue(report.contains("Last released version (HWM): 1.3.0"));
        assertTrue(report.contains("Pending batches: 0"));
    }

    @Test
    void omitsReleasedVersionLineWhenNotTracked() {
        // given - bump-after-release policy never records a released version
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:mylib", "/projects/mylib",
                null, "1.2.0", null));

        // when
        String report = generator.generateLibrariesReport(dataStore);

        // then
        assertFalse(report.contains("Last released version"));
        assertTrue(report.contains("Source dirs: -"));
    }

    @Test
    void listsPendingBatchDetailUnderTheOwningLibrary() {
        // given - one library with two pending batches, one stamped for an unknown next version
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:mylib", "/projects/mylib",
                null, "1.2.0", null));
        PendingLibraryImpactedMethod unknownNextBatch = new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.2.0", "abcdef1234567890", new HashSet<>(Arrays.asList(10, 20)));
        unknownNextBatch.setUnknownNextVersion(true);
        dataStore.persistPendingLibraryImpactedMethods(unknownNextBatch);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.1.0", null, new HashSet<>(Arrays.asList(30))));

        // when
        String report = generator.generateLibrariesReport(dataStore);

        // then
        assertTrue(report.contains("Pending batches: 2"));
        assertTrue(report.contains("@ 1.2.0 - 2 methods pending (unknown next version) [hash: abcdef123456...]"));
        assertTrue(report.contains("@ 1.1.0 - 1 method pending"));
    }
}
