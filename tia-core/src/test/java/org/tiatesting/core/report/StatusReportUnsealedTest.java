package org.tiatesting.core.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the status report surfaces the count of test suites currently flagged
 * {@code unsealed} - suites whose mapping rows were written by a run that never sealed its
 * commit value, and which will therefore be force-run on the next select-tests call. Modeled
 * on {@code StatusReportBranchTest} in this package for its data store setup, and on
 * {@code TestSelectorUnsealedSuitesTest} for how to seed an unsealed suite via the real persist
 * path rather than poking the flag directly.
 */
class StatusReportUnsealedTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test", null));
        dataStore.getTiaData(true); // force schema creation
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
     * Seed the DB with tracked suites that each have one impacted method, so the mapping
     * row-write path is exercised and {@link JdbcDataStore#persistTestSuites} flags each suite
     * unsealed as a side effect of writing its edges - the same mechanism a real run relies on,
     * rather than setting the flag directly.
     *
     * @param suiteNames the test suite names to register as tracked and leave flagged unsealed
     */
    private void seedUnsealedSuites(String... suiteNames) {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("abc123");
        tiaData.setLastUpdated(Instant.now());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>(dataStore.getMethodsTracked());
        Map<String, TestSuiteTracker> testSuites = new HashMap<>(dataStore.getTestSuitesTracked());

        int methodId = 1;
        for (String suiteName : suiteNames) {
            methods.put(methodId, new MethodImpactTracker(suiteName + ".someMethod", 1, 10));

            TestSuiteTracker tracker = new TestSuiteTracker(suiteName);
            tracker.setClassesImpacted(Collections.singletonList(
                    new ClassImpactTracker("com/example/" + suiteName + "Source.java",
                            new HashSet<>(Collections.singletonList(methodId)))));
            testSuites.put(suiteName, tracker);
            methodId++;
        }

        tiaData.setTestSuitesTracked(testSuites);
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methods);
    }

    @Test
    void statusReportsUnsealedSuiteCountWhenAPreviousRunDidNotComplete() {
        // given - two suites flagged unsealed
        seedUnsealedSuites("com.example.OneSpec", "com.example.TwoSpec");

        // when
        String report = new StatusReportGenerator().generateSummaryReport(dataStore);

        // then
        assertTrue(report.contains("Unsealed test suites: 2"),
                "the status report must surface unsealed suites. Report was:\n" + report);
    }

    @Test
    void statusReportOmitsUnsealedLineWhenNothingIsUnsealed() {
        // given - no suites tracked at all, so nothing can be flagged unsealed
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("abc123");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);

        // when
        String report = new StatusReportGenerator().generateSummaryReport(dataStore);

        // then
        assertFalse(report.contains("Unsealed test suites:"),
                "no unsealed line should be printed when the count is zero. Report was:\n" + report);
    }
}
