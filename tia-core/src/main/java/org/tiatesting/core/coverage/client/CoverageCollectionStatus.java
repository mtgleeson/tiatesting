package org.tiatesting.core.coverage.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether a test JVM's coverage collection from the JaCoCo agent has failed. A mapping run whose
 * collection failed - at startup or for any one suite - cannot say what its suites covered, so the
 * test runner listeners persist none of its results: no suite mapping, no failed set, no commit
 * advance and no history. They record only the failure itself, on the run's row, so no other JVM of
 * the run persists either. The stored mapping then stays at its current commit and the next mapping
 * run selects from that baseline again. See the "Persist flow and crash safety" chapter in
 * {@code WIKI.md}.
 *
 * <p>One instance is shared by every test plan of a test JVM, so a failure in one plan also stops
 * a later plan of the same JVM - a Surefire re-run - from persisting. Only the first failure is
 * kept and logged; once it is recorded the listeners stop collecting. Safe to use from concurrently
 * executing suites.
 */
public class CoverageCollectionStatus {

    private static final Logger log = LoggerFactory.getLogger(CoverageCollectionStatus.class);

    private final AtomicReference<String> failure = new AtomicReference<>();

    /**
     * Record that a coverage collection failed. The first failure of the JVM is kept and logged at
     * ERROR with its cause; later ones are ignored, since the outcome is already decided.
     *
     * @param during what the collection was for, completing the sentence "Coverage collection
     *               failed ...", such as {@code "for the test suite com.example.FooTest"}
     * @param cause the failure reported by the coverage client
     */
    public void recordFailure(final String during, final IOException cause) {
        String description = during + ": " + cause;
        if (failure.compareAndSet(null, description)) {
            log.error("Coverage collection from the JaCoCo agent failed {}. Tia will persist none of "
                    + "this run's results - no mapping, failed suites, commit or history - and "
                    + "collects no more coverage in this test JVM.", during, cause);
        }
    }

    /**
     * @return true once a coverage collection has failed in this JVM
     */
    public boolean hasFailed() {
        return failure.get() != null;
    }

    /**
     * @return what the first failed collection was for and its cause, or null when none has failed
     */
    public String getFailure() {
        return failure.get();
    }
}
