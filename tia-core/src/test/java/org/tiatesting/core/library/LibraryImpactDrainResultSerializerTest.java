package org.tiatesting.core.library;

import org.junit.jupiter.api.*;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class LibraryImpactDrainResultSerializerTest {

    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-ser-", "");
        tempDir.delete();
        tempDir.mkdirs();
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
    void serializeAndDeserializeRoundTrip() {
        // given a drain result with two drained batches and an applied seq
        LibraryImpactDrainResult original = new LibraryImpactDrainResult();
        original.addDrainedBatch("com.example:lib", 1L);
        original.addDrainedBatch("com.example:lib", 2L);
        original.setAppliedSeq("com.example:lib", 2L);

        // when it is serialized and deserialized (the Maven plugin-to-fork transport)
        File file = new File(tempDir, "drain.ser");
        LibraryImpactDrainResultSerializer.serialize(original, file);
        LibraryImpactDrainResult deserialized = LibraryImpactDrainResultSerializer.deserialize(file.getAbsolutePath());

        // then the batch keys and applied seqs survive the round trip
        assertNotNull(deserialized);
        assertTrue(deserialized.hasDrainedBatches());
        assertEquals(2, deserialized.getDrainedBatchKeys().size());
        assertEquals(1L, deserialized.getDrainedBatchKeys().get(0).getPublishSeq());
        assertEquals(2L, deserialized.getDrainedBatchKeys().get(1).getPublishSeq());
        assertEquals(Long.valueOf(2L), deserialized.getAppliedSeqByLibrary().get("com.example:lib"));
    }

    @Test
    void deserializeReturnsNullForMissingFile() {
        assertNull(LibraryImpactDrainResultSerializer.deserialize("/nonexistent/file.ser"));
    }

    @Test
    void deserializeReturnsNullForNullPath() {
        assertNull(LibraryImpactDrainResultSerializer.deserialize(null));
    }

    @Test
    void deserializeReturnsNullForEmptyPath() {
        assertNull(LibraryImpactDrainResultSerializer.deserialize(""));
    }

    /**
     * Verify the in-memory form used by the distributed run row carries the same content as the
     * file form: a drain result serialized to a byte array and read straight back keeps its drained
     * batches, its drained forced batches and its applied sequences. This is the encoding the plan
     * writes into {@code tia_distributed_run.drain_result}, and the drain cannot be repeated, so a
     * lossy round trip here would silently discard cleanup no later stage can reconstruct.
     *
     * @throws Exception if serializing or deserializing the drain result fails
     */
    @Test
    void byteArrayRoundTripPreservesEveryDrainedBatch() throws Exception {
        // given
        LibraryImpactDrainResult original = new LibraryImpactDrainResult();
        original.addDrainedBatch("com.example:lib", 1L);
        original.addDrainedForcedBatch("com.example:other", 7L);
        original.setAppliedSeq("com.example:lib", 2L);

        // when
        byte[] bytes = LibraryImpactDrainResultSerializer.toBytes(original);
        LibraryImpactDrainResult restored = LibraryImpactDrainResultSerializer.fromBytes(bytes);

        // then
        assertEquals(original, restored);
    }

    /**
     * Verify that a null byte array - what a JDBC read returns for a run row whose {@code
     * drain_result} column is SQL NULL, the normal case when library impact analysis is not
     * configured - deserializes to null rather than throwing, so the read path needs no guard of
     * its own.
     *
     * @throws Exception if deserializing unexpectedly fails
     */
    @Test
    void fromBytesReturnsNullForANullArray() throws Exception {
        // given
        byte[] noStoredDrainResult = null;

        // when
        LibraryImpactDrainResult restored = LibraryImpactDrainResultSerializer.fromBytes(noStoredDrainResult);

        // then
        assertNull(restored);
    }

    /**
     * Verify that a zero-length byte array also deserializes to null, since a database can return
     * an empty binary value where a driver would otherwise have returned SQL NULL, and an empty
     * array is not a valid object stream.
     *
     * @throws Exception if deserializing unexpectedly fails
     */
    @Test
    void fromBytesReturnsNullForAnEmptyArray() throws Exception {
        // given
        byte[] empty = new byte[0];

        // when
        LibraryImpactDrainResult restored = LibraryImpactDrainResultSerializer.fromBytes(empty);

        // then
        assertNull(restored);
    }
}
