package org.tiatesting.core.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What one test JVM ran for one run, for one attempt kind - its first attempt, or its reruns of
 * failed tests. One row of {@code tia_test_run_jvm}. The figures are the JVM's running totals, so a
 * JVM restates its row on every persist rather than adding to it. A run's history rows, and a
 * distributed group's figures, are derived from its contributions; see the "Test run history"
 * chapter in {@code WIKI.md}.
 */
public final class TestRunContribution {

    private final String runId;
    private final String jvmId;
    private final boolean rerun;
    private final Integer groupNumber;
    private final int suitesRan;
    private final Set<String> suitesFailed;
    private final Set<String> rerunSuites;
    private final long testTimeMs;
    private final long suitesTimeMs;
    private final long firstStartMs;
    private final long lastEndMs;

    /**
     * Construct a contribution.
     *
     * @param runId the run the JVM contributed to: the single-host test run id or the distributed run id
     * @param jvmId the contributing test JVM's id, generated once per JVM
     * @param rerun true for the JVM's rerun totals, false for its first-attempt totals
     * @param groupNumber the distributed group the JVM ran, or null on a single-host run
     * @param suitesRan the distinct suites the JVM finished in plans of this kind
     * @param suitesFailed the suites whose latest outcome among the JVM's plans of this kind failed
     * @param rerunSuites on a rerun row, the suites the JVM re-ran; empty on a first-attempt row
     * @param testTimeMs the summed duration of the JVM's plans of this kind
     * @param suitesTimeMs on a first-attempt row, the summed measured run time of the suites the JVM
     *                     timed (zero when no suite was timed); zero on a rerun row
     * @param firstStartMs the earliest plan start of this kind in the JVM, epoch millis
     * @param lastEndMs the latest plan end of this kind in the JVM, epoch millis
     */
    public TestRunContribution(String runId, String jvmId, boolean rerun, Integer groupNumber,
                               int suitesRan, Set<String> suitesFailed, Set<String> rerunSuites,
                               long testTimeMs, long suitesTimeMs, long firstStartMs, long lastEndMs) {
        this.runId = runId;
        this.jvmId = jvmId;
        this.rerun = rerun;
        this.groupNumber = groupNumber;
        this.suitesRan = suitesRan;
        this.suitesFailed = Collections.unmodifiableSet(new LinkedHashSet<>(suitesFailed));
        this.rerunSuites = Collections.unmodifiableSet(new LinkedHashSet<>(rerunSuites));
        this.testTimeMs = testTimeMs;
        this.suitesTimeMs = suitesTimeMs;
        this.firstStartMs = firstStartMs;
        this.lastEndMs = lastEndMs;
    }

    /** @return the run the JVM contributed to */
    public String getRunId() { return runId; }

    /** @return the contributing test JVM's id */
    public String getJvmId() { return jvmId; }

    /** @return true for rerun totals, false for first-attempt totals */
    public boolean isRerun() { return rerun; }

    /** @return the distributed group the JVM ran, or null on a single-host run */
    public Integer getGroupNumber() { return groupNumber; }

    /** @return the distinct suites the JVM finished in plans of this kind */
    public int getSuitesRan() { return suitesRan; }

    /** @return the suites whose latest outcome among the JVM's plans of this kind failed */
    public Set<String> getSuitesFailed() { return suitesFailed; }

    /** @return on a rerun row the suites the JVM re-ran, otherwise empty */
    public Set<String> getRerunSuites() { return rerunSuites; }

    /** @return the summed duration of the JVM's plans of this kind */
    public long getTestTimeMs() { return testTimeMs; }

    /** @return the summed measured run time of the suites the JVM timed, on a first-attempt row */
    public long getSuitesTimeMs() { return suitesTimeMs; }

    /** @return the earliest plan start of this kind in the JVM */
    public long getFirstStartMs() { return firstStartMs; }

    /** @return the latest plan end of this kind in the JVM */
    public long getLastEndMs() { return lastEndMs; }
}
