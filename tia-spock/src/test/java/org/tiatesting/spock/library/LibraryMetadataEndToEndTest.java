package org.tiatesting.spock.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that the Gradle/Spock test-JVM path can drive
 * {@link TestSelector#selectTestsToIgnore} with a {@link LibraryImpactAnalysisConfig} built from
 * the {@code tiaLibrariesMetadata} system-property contract — and that reconcile + stamp + drain
 * happen against the real H2 store on a primary build (updateDBMapping=true), producing the same
 * effects the Maven path produces today.
 */
class LibraryMetadataEndToEndTest {

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-spock-e2e-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    @Test
    void primaryBuildReconcilesAndStampsViaSystemPropertyContract() {
        // Encode the library metadata exactly as the Gradle plugin would forward it.
        PreResolvedLibraryMetadataReader.Entry entry = new PreResolvedLibraryMetadataReader.Entry(
                "com.example:lib", "/abs/path/to/lib", "1.0.0",
                Collections.singletonList("/abs/path/to/lib/src/main/java"),
                "0.9.0", "/abs/jar/lib-0.9.0.jar");
        String encoded = LibraryMetadataSystemProperties.formatEntries(Collections.singletonList(entry));
        LibraryImpactAnalysisConfig config = LibraryMetadataSystemProperties.fromValues(
                encoded, "/abs/source-project", "BUMP_AFTER_RELEASE");
        assertNotNull(config);

        // Seed an existing commit value so selectTestsToIgnore proceeds past the initial-run guard.
        seedStoredCommit("abc123");

        TestSelector testSelector = new TestSelector(dataStore);
        testSelector.selectTestsToIgnore(emptyDiffsVcsReader(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), false, config, true);

        // Reconciler must have inserted the library row using the resolved baseline.
        assertTrue(dataStore.readTrackedLibraries().containsKey("com.example:lib"),
                "Reconciler must insert tia_library row from system-property metadata.");
        assertEquals("0.9.0",
                dataStore.readTrackedLibraries().get("com.example:lib").getLastSourceProjectVersion(),
                "Baseline version must come from the pre-resolved resolvedVersion field.");
    }

    @Test
    void nonPrimaryBuildReadsConfigButWritesNothing() {
        PreResolvedLibraryMetadataReader.Entry entry = new PreResolvedLibraryMetadataReader.Entry(
                "com.example:lib", "/abs/path/to/lib", "1.0.0",
                Collections.singletonList("/abs/path/to/lib/src/main/java"),
                "0.9.0", "/abs/jar/lib-0.9.0.jar");
        String encoded = LibraryMetadataSystemProperties.formatEntries(Collections.singletonList(entry));
        LibraryImpactAnalysisConfig config = LibraryMetadataSystemProperties.fromValues(encoded, null, null);

        seedStoredCommit("abc123");

        TestSelector testSelector = new TestSelector(dataStore);
        testSelector.selectTestsToIgnore(emptyDiffsVcsReader(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), false, config, false);

        assertTrue(dataStore.readTrackedLibraries().isEmpty(),
                "Non-primary build (updateDBMapping=false) must not insert tia_library rows.");
    }

    private void seedStoredCommit(String commit) {
        org.tiatesting.core.model.TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue(commit);
        tiaData.setLastUpdated(Instant.now());
        Map<String, org.tiatesting.core.model.TestSuiteTracker> testSuites = new HashMap<>();
        Map<Integer, org.tiatesting.core.model.MethodImpactTracker> methods = new HashMap<>();
        tiaData.setTestSuitesTracked(testSuites);
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methods);
    }

    private VCSReader emptyDiffsVcsReader() {
        return new StubVCSReader();
    }

    private static class StubVCSReader implements VCSReader {
        @Override public String getBranchName() { return "test"; }
        @Override public String getHeadCommit() { return "head"; }
        @Override public Set<SourceFileDiffContext> buildDiffFilesContext(String baseChangeNum,
                                                                           List<String> sourceFilesDirs,
                                                                           List<String> testFilesDirs,
                                                                           boolean checkLocalChanges) {
            return new HashSet<>();
        }
        @Override public void close() { }
    }
}
