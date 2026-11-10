package org.tiatesting.core.coverage.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link CoverageCollectionStatus} starts clean, reports a recorded failure and keeps
 * only the first one.
 */
class CoverageCollectionStatusTest {

    @Test
    void aNewStatusHasNotFailed() {
        // given
        CoverageCollectionStatus status = new CoverageCollectionStatus();

        // when
        boolean failed = status.hasFailed();

        // then
        assertFalse(failed);
        assertNull(status.getFailure());
    }

    @Test
    void aRecordedFailureIsReportedWithItsCause() {
        // given
        CoverageCollectionStatus status = new CoverageCollectionStatus();

        // when
        status.recordFailure("for the test suite com.example.FooTest", new IOException("Connection refused"));

        // then
        assertTrue(status.hasFailed());
        assertEquals("for the test suite com.example.FooTest: java.io.IOException: Connection refused",
                status.getFailure());
    }

    @Test
    void onlyTheFirstFailureIsKept() {
        // given
        CoverageCollectionStatus status = new CoverageCollectionStatus();
        status.recordFailure("at startup", new IOException("first"));

        // when
        status.recordFailure("for the test suite com.example.FooTest", new IOException("second"));

        // then
        assertEquals("at startup: java.io.IOException: first", status.getFailure());
    }
}
