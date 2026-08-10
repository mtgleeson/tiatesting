package org.tiatesting.core.distributed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Writes the distributed run plan JSON document to {@code <tiaBuildDir>/tia-run-plan.json}. Lives
 * in {@code tia-core} - rather than the Maven plugin module it was first written for - so both the
 * Maven {@code tia-dist-plan} goal and the Gradle {@code tia-dist-plan} task call this one
 * implementation instead of each writing the file themselves; the three properties that matter for
 * this write - parent-directory creation, an explicit UTF-8 charset, and surfacing any failure to
 * the caller rather than swallowing it - are exercised directly by a unit test rather than through
 * either build tool's plugin invocation.
 */
public final class DistributedRunPlanWriter {

    /** The file name a CI pipeline looks for under {@code tiaBuildDir} to learn the group count. */
    public static final String FILE_NAME = "tia-run-plan.json";

    private DistributedRunPlanWriter() {
    }

    /**
     * Write {@code json} to {@code <buildDir>/tia-run-plan.json}, creating {@code buildDir} - and
     * any missing parent directories - first. This matters because {@code tiaBuildDir} defaults
     * inside {@code target/}, which may not exist yet when this goal runs standalone (outside a
     * full Maven lifecycle that would otherwise have created it); without creating it here, a
     * pipeline that cannot find the file has no group count to fan out on and the distributed run
     * stops before any runner starts.
     *
     * <p>The file is written with an explicit UTF-8 {@link StandardCharsets#UTF_8} charset rather
     * than the JVM's platform default, so a branch or commit name containing a non-ASCII character
     * produces the same, valid-JSON bytes on every runner regardless of the host platform's default
     * encoding (for example a Windows runner whose platform default is not UTF-8).
     *
     * @param buildDir the directory the plan file is written under; created if it does not exist
     * @param json the JSON document to write, verbatim
     * @return the path of the file written
     * @throws IOException if {@code buildDir} cannot be created or the file cannot be written; the
     *                      caller must fail the build on this rather than continue, since a plan
     *                      left persisted only in the database with no file on disk leaves the
     *                      pipeline with no way to learn the group count to fan out on
     */
    public static Path write(final String buildDir, final String json) throws IOException {
        Path dir = Paths.get(buildDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE_NAME);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
