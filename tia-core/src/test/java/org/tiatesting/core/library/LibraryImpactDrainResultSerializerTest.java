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
        LibraryImpactDrainResult original = new LibraryImpactDrainResult();
        original.addDrainedBatch("com.example:lib", "1.0.0");
        original.addDrainedBatch("com.example:lib", "1.1.0");
        original.setObservedLibraryState("com.example:lib", "1.1.0", "somehash");

        File file = new File(tempDir, "drain.ser");
        LibraryImpactDrainResultSerializer.serialize(original, file);

        LibraryImpactDrainResult deserialized = LibraryImpactDrainResultSerializer.deserialize(file.getAbsolutePath());

        assertNotNull(deserialized);
        assertTrue(deserialized.hasDrainedBatches());
        assertEquals(2, deserialized.getDrainedBatchKeys().size());
        assertEquals("1.0.0", deserialized.getDrainedBatchKeys().get(0).getStampVersion());
        assertEquals("1.1.0", deserialized.getDrainedBatchKeys().get(1).getStampVersion());

        LibraryImpactDrainResult.ObservedLibraryState state =
                deserialized.getObservedLibraryStates().get("com.example:lib");
        assertNotNull(state);
        assertEquals("1.1.0", state.getResolvedVersion());
        assertEquals("somehash", state.getResolvedJarHash());
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
