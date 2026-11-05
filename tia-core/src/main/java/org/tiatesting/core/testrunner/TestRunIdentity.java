package org.tiatesting.core.testrunner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The id and start time of one run, assigned by the build-tool step that selects the tests and
 * handed to every test JVM of the run with the rest of the selection: through the fork properties
 * file on Maven, as test-task system properties on Gradle. Every JVM of the run - a parallel fork,
 * a Surefire per-class plan, a Gradle test-retry JVM - contributes under this one id. See the "Test
 * run history" chapter in {@code WIKI.md}.
 */
public final class TestRunIdentity {

    /** System property carrying the run id to the test JVM. */
    public static final String PROP_TEST_RUN_ID = "tiaTestRunId";

    /** System property carrying the run's start time, epoch millis, to the test JVM. */
    public static final String PROP_TEST_RUN_START_MS = "tiaTestRunStartMs";

    private final String runId;
    private final long startMs;

    /**
     * Construct an identity.
     *
     * @param runId the run id; must not be blank
     * @param startMs the run's start time, epoch millis
     */
    public TestRunIdentity(final String runId, final long startMs) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("A test run id must not be blank");
        }
        this.runId = runId.trim();
        this.startMs = startMs;
    }

    /**
     * Assign a new run: a random id and the given start time.
     *
     * @param nowMs the start time, epoch millis
     * @return the new identity
     */
    public static TestRunIdentity newRun(final long nowMs) {
        return new TestRunIdentity(UUID.randomUUID().toString(), nowMs);
    }

    /**
     * Read the identity the build tool published into this test JVM. It travels with the selection
     * itself, so an enabled test JVM always has it; a missing one means the build-tool wiring is
     * broken, which is reported rather than replaced by a per-plan id.
     *
     * @return the identity
     * @throws IllegalStateException if either property is missing or the start time is not a number
     */
    public static TestRunIdentity fromSystemProperties() {
        String runId = System.getProperty(PROP_TEST_RUN_ID);
        String startMs = System.getProperty(PROP_TEST_RUN_START_MS);
        if (runId == null || runId.trim().isEmpty() || startMs == null || startMs.trim().isEmpty()) {
            throw new IllegalStateException(PROP_TEST_RUN_ID + " / " + PROP_TEST_RUN_START_MS
                    + " were not set in this test JVM. Tia's build-tool plugin publishes them with the "
                    + "test selection, so an absent value means the Tia agent or the test-task wiring "
                    + "did not run for this JVM.");
        }
        try {
            return new TestRunIdentity(runId, Long.parseLong(startMs.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException(PROP_TEST_RUN_START_MS + " is not a number: " + startMs, e);
        }
    }

    /**
     * Render the identity as the properties the build tool hands to the test JVM.
     *
     * @return the two properties, in a stable order
     */
    public Map<String, String> toProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PROP_TEST_RUN_ID, runId);
        properties.put(PROP_TEST_RUN_START_MS, String.valueOf(startMs));
        return properties;
    }

    /** @return the run id */
    public String getRunId() { return runId; }

    /** @return the run's start time, epoch millis */
    public long getStartMs() { return startMs; }
}
