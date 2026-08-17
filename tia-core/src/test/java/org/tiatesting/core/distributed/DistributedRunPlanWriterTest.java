package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DistributedRunPlanWriter}, covering the three properties the {@code
 * dist-plan} goal's file write depends on: creating a missing build directory, writing an
 * explicit UTF-8 encoding rather than the platform default, and surfacing a failure to the caller
 * instead of swallowing it.
 */
class DistributedRunPlanWriterTest {

    @TempDir
    Path tempDir;

    /**
     * Verify that {@link DistributedRunPlanWriter#write} creates the build directory - including
     * any missing parent directories - when it does not already exist, then writes the plan file
     * into it. This is the case a standalone {@code dist-plan} invocation hits when {@code
     * target/} has not been created yet by an earlier build phase.
     *
     * @throws IOException never expected here; propagating it fails the test with the real cause
     *                      rather than masking it
     */
    @Test
    void createsMissingBuildDirectoryBeforeWriting() throws IOException {
        // given a build directory that does not exist yet, including a missing parent
        Path buildDir = tempDir.resolve("target").resolve("tia");

        // when the plan is written
        Path written = DistributedRunPlanWriter.write(buildDir.toString(), "{}");

        // then the directory and the file both exist, with the published file name
        assertTrue(Files.isDirectory(buildDir));
        assertTrue(Files.exists(written));
        assertEquals(DistributedRunPlanWriter.FILE_NAME, written.getFileName().toString());
    }

    /**
     * Verify that the file's bytes on disk are the exact UTF-8 encoding of the supplied JSON,
     * including a non-ASCII character, rather than whatever the JVM's platform default charset
     * would have produced - the case a branch or commit name with a non-ASCII character on a
     * Windows runner (non-UTF-8 platform default) would otherwise corrupt.
     *
     * @throws IOException never expected here; propagating it fails the test with the real cause
     *                      rather than masking it
     */
    @Test
    void writesContentAsExplicitUtf8() throws IOException {
        // given a JSON document containing a non-ASCII character
        String json = "{\"branch\": \"feature/café\"}";

        // when the plan is written
        Path written = DistributedRunPlanWriter.write(tempDir.toString(), json);

        // then the bytes on disk are the UTF-8 encoding of the original string, not the platform default
        byte[] bytes = Files.readAllBytes(written);
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), bytes);
        assertEquals(json, new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Verify that {@link DistributedRunPlanWriter#write} throws rather than continuing silently
     * when the build directory cannot be created - here, because a regular file already occupies
     * that path. A pipeline that cannot find the plan file has no group count to fan out on, so
     * this failure must reach the caller and fail the build rather than be swallowed.
     */
    @Test
    void throwsWhenBuildDirectoryCannotBeCreated() throws IOException {
        // given a regular file already sitting where the build directory needs to be created
        Path blocked = tempDir.resolve("blocked");
        Files.write(blocked, "not a directory".getBytes(StandardCharsets.UTF_8));

        // when writing the plan under that blocked path
        // then the write throws instead of silently continuing
        assertThrows(IOException.class, () -> DistributedRunPlanWriter.write(blocked.toString(), "{}"));
    }
}
