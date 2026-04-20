package org.tiatesting.core.library;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionComparisonTest {

    @Test
    void equalVersions() {
        assertEquals(0, PendingLibraryImpactedMethodsDrainer.compareVersions("1.0.0", "1.0.0"));
    }

    @Test
    void higherMajorVersion() {
        assertTrue(PendingLibraryImpactedMethodsDrainer.compareVersions("2.0.0", "1.0.0") > 0);
    }

    @Test
    void lowerMajorVersion() {
        assertTrue(PendingLibraryImpactedMethodsDrainer.compareVersions("1.0.0", "2.0.0") < 0);
    }

    @Test
    void higherMinorVersion() {
        assertTrue(PendingLibraryImpactedMethodsDrainer.compareVersions("1.2.0", "1.1.0") > 0);
    }

    @Test
    void higherPatchVersion() {
        assertTrue(PendingLibraryImpactedMethodsDrainer.compareVersions("1.0.2", "1.0.1") > 0);
    }

    @Test
    void differentLengthVersions() {
        assertTrue(PendingLibraryImpactedMethodsDrainer.compareVersions("1.1", "1.0.0") > 0);
    }

    @Test
    void shorterVersionTreatedAsZeroPadded() {
        assertEquals(0, PendingLibraryImpactedMethodsDrainer.compareVersions("1.0", "1.0.0"));
    }

    @Test
    void numericSegmentsComparedNumerically() {
        assertTrue(PendingLibraryImpactedMethodsDrainer.compareVersions("1.10.0", "1.9.0") > 0);
    }

    @Test
    void hyphenSeparatedQualifiers() {
        assertTrue(PendingLibraryImpactedMethodsDrainer.compareVersions("1.0.1", "1.0.0") > 0);
    }

    @Test
    void singleSegmentVersions() {
        assertTrue(PendingLibraryImpactedMethodsDrainer.compareVersions("2", "1") > 0);
    }
}
