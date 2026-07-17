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
}
