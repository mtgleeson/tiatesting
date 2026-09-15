package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the HTML report writers encode text as UTF-8 regardless of the JVM's platform default
 * charset, matching the {@code <meta charset="UTF-8">} every page declares. Guards against the
 * regression where a plain {@code FileWriter} wrote non-ASCII characters (library names, paths,
 * punctuation) as platform-default bytes, which browsers then decoded as UTF-8 and rendered as
 * the replacement character U+FFFD.
 */
class HtmlReportUtf8CharsetTest {

    /** The Unicode replacement character a mis-decoded byte sequence produces. */
    private static final char REPLACEMENT = '\uFFFD';

    /**
     * Verify the shared {@link HtmlLayout#newReportWriter(String)} helper pins its charset to
     * UTF-8: a non-ASCII string round-trips when read back as UTF-8, and deliberately does NOT
     * round-trip when read back as ISO-8859-1. The negative assertion proves the on-disk bytes
     * are the UTF-8 multi-byte form (so the charset is pinned, not incidentally matching the
     * platform default); the positive assertion fails outright if the helper had used a
     * platform default that is not UTF-8.
     *
     * @param tempDir a JUnit-provided temporary directory for the output file
     * @throws IOException if the temp file cannot be written or read
     */
    @Test
    void newReportWriterEncodesAsUtf8NotPlatformDefault(@TempDir Path tempDir) throws IOException {
        // given
        // "cafe" with U+00E9 encodes as two bytes 0xC3 0xA9 in UTF-8 but one byte 0xE9 in
        // ISO-8859-1, so the two decodings diverge and expose the on-disk charset. Kept as a
        // Unicode escape so this source file stays pure ASCII regardless of javac's encoding.
        String nonAscii = "caf\u00e9";
        Path outFile = tempDir.resolve("charset.txt");

        // when
        try (Writer writer = HtmlLayout.newReportWriter(outFile.toString())) {
            writer.write(nonAscii);
        }
        String readAsUtf8 = new String(Files.readAllBytes(outFile), StandardCharsets.UTF_8);
        String readAsIso = new String(Files.readAllBytes(outFile), StandardCharsets.ISO_8859_1);

        // then
        assertEquals(nonAscii, readAsUtf8, "UTF-8 round-trip should preserve the non-ASCII character");
        assertNotEquals(nonAscii, readAsIso,
                "Reading as ISO-8859-1 must differ, proving the bytes are UTF-8 (charset pinned, not incidental)");
    }

    /**
     * Render the library report with a tracked library whose project directory contains a
     * non-ASCII character, then read the generated HTML back decoding as UTF-8 and assert the
     * character survives intact with no U+FFFD replacement character. This exercises the full
     * report-writing path end to end, not just the writer helper in isolation.
     *
     * @param tempDir a JUnit-provided temporary directory serving as the report output root
     * @throws IOException if the generated report file cannot be read back
     */
    @Test
    void libraryReportPreservesNonAsciiCharacters(@TempDir Path tempDir) throws IOException {
        // given
        String nonAsciiDir = "/abs/path/caf\u00e9-lib";
        TiaData tiaData = new TiaData();
        TrackedLibrary lib = new TrackedLibrary();
        lib.setGroupArtifact("com.example:libA");
        lib.setProjectDir(nonAsciiDir);
        lib.setSourceDirsCsv("/abs/path/caf\u00e9-lib/src/main/java");
        lib.setLastAppliedSeq(1L);
        Map<String, TrackedLibrary> libs = new LinkedHashMap<>();
        libs.put(lib.getGroupArtifact(), lib);
        tiaData.setLibrariesTracked(libs);

        // when
        new HtmlLibraryReport("utf8-branch", tempDir.toFile()).generateReport(tiaData);
        File report = new File(tempDir.toFile(),
                "html/utf8-branch/libraries/" + HtmlLibraryReport.TIA_LIBRARIES_HTML);
        String html = new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);

        // then
        assertTrue(html.contains(nonAsciiDir),
                "Non-ASCII project directory should round-trip intact when the file is UTF-8 encoded");
        assertFalse(html.indexOf(REPLACEMENT) >= 0,
                "Report should contain no U+FFFD replacement character");
    }
}
