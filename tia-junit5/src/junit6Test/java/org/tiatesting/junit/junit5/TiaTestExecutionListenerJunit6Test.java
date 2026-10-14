package org.tiatesting.junit.junit5;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.LauncherExecutionRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.junit.junit5.junit6fixtures.DisabledSuite;
import org.tiatesting.junit.junit5.junit6fixtures.FailingSuite;
import org.tiatesting.junit.junit5.junit6fixtures.PassingWithNestedSuite;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * Verifies Tia's listener, compiled for Java 8 against JUnit 5, records a run the JUnit 6 launcher
 * executes: suites (including a {@code @Nested} class) are tracked, a failing suite is recorded as
 * failed, a {@code @Disabled} suite is observed, and the history row is persisted.
 */
class TiaTestExecutionListenerJunit6Test {

    private static final String PASSING = PassingWithNestedSuite.class.getName();
    private static final String NESTED = PassingWithNestedSuite.Inner.class.getName();
    private static final String FAILING = FailingSuite.class.getName();
    private static final String DISABLED = DisabledSuite.class.getName();

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBTestRunHistory", "tiaBranch",
            "tiaCommitValue", "tiaSelectedTests", "test", H2ConnectionSettings.PROP_DB_FILE_PATH
    };

    private final Map<String, String> savedProperties = new LinkedHashMap<>();

    /**
     * Save and clear the system properties this test sets, then enable the listener with history
     * on and mapping off (mapping needs a JaCoCo agent) over an embedded H2 in a temp directory.
     *
     * @param tempDir the directory the embedded H2 database is created in
     */
    @BeforeEach
    void setUp(@TempDir final Path tempDir) {
        for (String key : MANAGED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaUpdateDBMapping", "false");
        System.setProperty("tiaUpdateDBTestRunHistory", "true");
        System.setProperty("tiaBranch", "main");
        System.setProperty("tiaCommitValue", "commit-1");
        System.setProperty("tiaSelectedTests", String.join(",", PASSING, NESTED, FAILING));
        System.setProperty(H2ConnectionSettings.PROP_DB_FILE_PATH, tempDir.toAbsolutePath().toString());
    }

    /**
     * Restore the saved system properties.
     */
    @AfterEach
    void tearDown() {
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    void listenerRecordsARunExecutedByTheJunit6Launcher() {
        // given
        SharedTestRunData shared = new SharedTestRunData();
        TiaTestExecutionListener listener = new TiaTestExecutionListener(shared);
        LauncherExecutionRequest execution = request()
                .selectors(selectClass(PassingWithNestedSuite.class), selectClass(FailingSuite.class),
                        selectClass(DisabledSuite.class))
                .forExecution()
                .listeners(listener)
                .build();

        // when
        LauncherFactory.create().execute(execution);

        // then
        Set<String> tracked = shared.getTestSuiteTrackers().keySet();
        assertTrue(tracked.contains(PASSING), "enclosing suite tracked: " + tracked);
        assertTrue(tracked.contains(NESTED), "@Nested suite tracked: " + tracked);
        assertTrue(tracked.contains(FAILING), "failing suite tracked: " + tracked);
        assertEquals(Collections.singleton(FAILING), shared.getTestSuitesFailed());
        assertTrue(shared.getSuitesObserved().contains(DISABLED), "@Disabled suite observed");
        // The launcher swallows listener exceptions, so a broken persist would otherwise pass.
        try (DataStore dataStore = DataStoreFactory.fromSystemProperties("main")) {
            List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
            assertEquals(1, history.size(), "one history row persisted");
            assertEquals(1, history.get(0).getNumSuitesFailed());
        }
    }
}
