package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TrackedLibrary;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 1 coverage for the publish-ledger schema and CRUD ({@code tia_library_publish}, the
 * ledger columns added to {@code tia_library} and the pending table). Verifies sequence
 * assignment, round trips, the hash-then-version lookup precedence with highest-seq wins, and
 * the cascade delete from {@code tia_library}. See {@code DESIGN-publish-time-stamping.md}.
 */
class H2DataStoreLibraryPublishTest {

    private static final String LIB = "com.example:lib";

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-ledger-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
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
     * The data store assigns the per-library sequence as max+1 on each persist and sets it on the
     * given object, and sequences are independent between libraries.
     */
    @Test
    void persistAssignsMonotonicSequencePerLibrary() {
        // given two tracked libraries
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null, null, null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:other", "/projects/other", null, null, null));

        // when publishes are persisted for both
        LibraryPublish first = new LibraryPublish(LIB, "1.0.0-SNAPSHOT", "H1", "c1", 1000L);
        LibraryPublish second = new LibraryPublish(LIB, "1.0.0-SNAPSHOT", "H2", "c2", 2000L);
        LibraryPublish otherFirst = new LibraryPublish("com.example:other", "2.0.0", "H9", "c3", 3000L);
        long seq1 = dataStore.persistLibraryPublish(first, Collections.emptySet());
        long seq2 = dataStore.persistLibraryPublish(second, Collections.emptySet());
        long otherSeq = dataStore.persistLibraryPublish(otherFirst, Collections.emptySet());

        // then sequences are monotonic per library and set on the objects, independent across libraries
        assertEquals(1L, seq1);
        assertEquals(2L, seq2);
        assertEquals(1L, otherSeq);
        assertEquals(1L, first.getPublishSeq());
        assertEquals(2L, second.getPublishSeq());
    }

    /**
     * A publish persisted with impacted method ids writes the ledger row and the stamp rows
     * together: the stamp carries the assigned sequence, the published version as its stamp
     * version and the publish's jar hash.
     */
    @Test
    void persistWithImpactedMethodsWritesStampCarryingAssignedSequence() {
        // given a tracked library
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null, null, null));

        // when a publish is persisted with impacted methods
        long seq = dataStore.persistLibraryPublish(
                new LibraryPublish(LIB, "1.0.0-SNAPSHOT", "H1", "c1", 1000L),
                new HashSet<>(Arrays.asList(10, 20)));

        // then the stamp batch exists with the assigned seq, published version and jar hash
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods(LIB);
        assertEquals(1, pending.size());
        assertEquals(Long.valueOf(seq), pending.get(0).getPublishSeq());
        assertEquals("1.0.0-SNAPSHOT", pending.get(0).getStampVersion());
        assertEquals("H1", pending.get(0).getStampJarHash());
        assertEquals(new HashSet<>(Arrays.asList(10, 20)), pending.get(0).getSourceMethodIds());
    }

    /**
     * A persisted ledger row round-trips all its fields and the per-library read returns rows
     * ordered by sequence ascending.
     */
    @Test
    void readLibraryPublishesRoundTripsFieldsInSequenceOrder() {
        // given a tracked library with two persisted publishes
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null, null, null));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0-SNAPSHOT", "H1", "commitA", 1111L), Collections.emptySet());
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0", null, "commitB", 2222L), Collections.emptySet());

        // when the ledger is read back
        List<LibraryPublish> ledger = dataStore.readLibraryPublishes(LIB);

        // then both rows come back in sequence order with all fields intact (null hash included)
        assertEquals(2, ledger.size());
        assertEquals(1L, ledger.get(0).getPublishSeq());
        assertEquals("1.0.0-SNAPSHOT", ledger.get(0).getPublishedVersion());
        assertEquals("H1", ledger.get(0).getJarHash());
        assertEquals("commitA", ledger.get(0).getCommitValue());
        assertEquals(1111L, ledger.get(0).getPublishedAt());
        assertEquals(2L, ledger.get(1).getPublishSeq());
        assertEquals("1.0.0", ledger.get(1).getPublishedVersion());
        assertNull(ledger.get(1).getJarHash());
    }

    /**
     * Lookup precedence: a jar-hash match wins over a version match even when the version would
     * match a higher-sequence row - the hash identifies the exact build the consumer resolved.
     */
    @Test
    void lookupPrefersJarHashMatchOverVersionMatch() {
        // given two publishes sharing a version but with distinct hashes
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null, null, null));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0", "H1", "c1", 1000L), Collections.emptySet());
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0", "H2", "c2", 2000L), Collections.emptySet());

        // when looking up with the older build's hash and the shared version
        LibraryPublish found = dataStore.lookupLibraryPublish(LIB, "H1", "1.0.0");

        // then the hash match (seq 1) wins over the version match (which would give seq 2)
        assertNotNull(found);
        assertEquals(1L, found.getPublishSeq());
        assertEquals("H1", found.getJarHash());
    }

    /**
     * When no ledger row matches the resolved hash, the lookup falls back to an exact
     * published-version match.
     */
    @Test
    void lookupFallsBackToVersionWhenHashUnknown() {
        // given a release publish
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null, null, null));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0", "H1", "c1", 1000L), Collections.emptySet());

        // when looking up with an unknown hash but a matching version
        LibraryPublish found = dataStore.lookupLibraryPublish(LIB, "H-unknown", "1.0.0");

        // then the version fallback resolves the row
        assertNotNull(found);
        assertEquals(1L, found.getPublishSeq());
    }

    /**
     * Duplicate matches take the highest sequence: contents are identical or cumulative, so the
     * higher sequence drains a superset - the safe direction.
     */
    @Test
    void lookupReturnsHighestSequenceOnDuplicateMatches() {
        // given the same jar hash published at two sequences (identical artifact republished)
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null, null, null));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0-SNAPSHOT", "H-same", "c1", 1000L), Collections.emptySet());
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0-SNAPSHOT", "H-other", "c2", 2000L), Collections.emptySet());
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0-SNAPSHOT", "H-same", "c3", 3000L), Collections.emptySet());

        // when looking up that hash
        LibraryPublish found = dataStore.lookupLibraryPublish(LIB, "H-same", null);

        // then the highest matching sequence is returned
        assertNotNull(found);
        assertEquals(3L, found.getPublishSeq());
    }

    /**
     * A lookup with no matching hash, no matching version (or nulls for both) returns null - the
     * "resolved build unknown to the ledger" signal the drain holds on.
     */
    @Test
    void lookupReturnsNullWhenNothingMatches() {
        // given one publish
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null, null, null));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0", "H1", "c1", 1000L), Collections.emptySet());

        // when looking up unknown identities and null identities
        LibraryPublish unknown = dataStore.lookupLibraryPublish(LIB, "H-x", "9.9.9");
        LibraryPublish nulls = dataStore.lookupLibraryPublish(LIB, null, null);

        // then both lookups return null
        assertNull(unknown);
        assertNull(nulls);
    }

    /**
     * Deleting a tracked library cascades to its ledger rows, mirroring the existing cascade for
     * pending stamps.
     */
    @Test
    void deleteTrackedLibraryCascadesToLedger() {
        // given a tracked library with a ledger row
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null, null, null));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0", "H1", "c1", 1000L), Collections.emptySet());
        assertEquals(1, dataStore.readLibraryPublishes(LIB).size());

        // when the library is deleted
        dataStore.deleteTrackedLibrary(LIB);

        // then its ledger rows are gone
        assertTrue(dataStore.readLibraryPublishes(LIB).isEmpty());
    }

    /**
     * The two new {@code tia_library} columns round-trip through persist/read, including a null
     * {@code lastAppliedSeq} staying null (distinct from zero).
     */
    @Test
    void trackedLibraryLedgerFieldsRoundTrip() {
        // given a tracked library carrying the new ledger fields, and one leaving them unset
        TrackedLibrary withFields = new TrackedLibrary(LIB, "/projects/lib", null, "1.0.0", null);
        withFields.setMappingBaselineCommit("baseline-commit-abc");
        withFields.setLastAppliedSeq(7L);
        dataStore.persistTrackedLibrary(withFields);
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:bare", "/projects/bare", null, null, null));

        // when the libraries are read back
        Map<String, TrackedLibrary> tracked = dataStore.readTrackedLibraries();

        // then the fields round-trip, and unset fields come back null
        assertEquals("baseline-commit-abc", tracked.get(LIB).getMappingBaselineCommit());
        assertEquals(Long.valueOf(7L), tracked.get(LIB).getLastAppliedSeq());
        assertNull(tracked.get("com.example:bare").getMappingBaselineCommit());
        assertNull(tracked.get("com.example:bare").getLastAppliedSeq());
    }

    /**
     * A pending stamp's {@code publishSeq} round-trips through persist/read, and a stamp without
     * one (e.g. an app-side recorder stamp with no published identity) comes back null.
     */
    @Test
    void pendingStampPublishSeqRoundTrips() {
        // given a tracked library and two pending stamps, one with a publish seq and one without
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null, null, null));
        PendingLibraryImpactedMethod withSeq = new PendingLibraryImpactedMethod(
                LIB, "1.0.0", null, new HashSet<>(Arrays.asList(10)));
        withSeq.setPublishSeq(3L);
        dataStore.persistPendingLibraryImpactedMethods(withSeq);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                LIB, "2.0.0", null, new HashSet<>(Arrays.asList(20))));

        // when the pending stamps are read back
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods(LIB);

        // then the publish seq round-trips and the unset one is null
        assertEquals(2, pending.size());
        for (PendingLibraryImpactedMethod batch : pending) {
            if ("1.0.0".equals(batch.getStampVersion())) {
                assertEquals(Long.valueOf(3L), batch.getPublishSeq());
            } else {
                assertNull(batch.getPublishSeq());
            }
        }
    }
}
