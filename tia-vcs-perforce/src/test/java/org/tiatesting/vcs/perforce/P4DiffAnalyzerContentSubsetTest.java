package org.tiatesting.vcs.perforce;

import com.perforce.p4java.core.file.FileAction;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.server.IOptionsServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the Stage 1 split of list-build vs content-load: {@link P4DiffAnalyzer#loadContentForDiffContexts}
 * fetches content for ONLY the diff contexts it is handed, keyed by each context's
 * {@code vcsFetchKey} (depot path). This is the building block the later
 * "fetch content only for tracked files" change relies on - a third changed file present in
 * the range but absent from the passed collection must never reach the {@code p4 print} argv
 * and must be left with no content.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4DiffAnalyzerContentSubsetTest {

    private static final String FOO_DEPOT = "//apps/example/src/main/java/com/example/Foo.java";
    private static final String BAR_DEPOT = "//apps/example/src/main/java/com/example/Bar.java";
    private static final String BAZ_DEPOT = "//apps/example/src/main/java/com/example/Baz.java";

    @Mock private P4Connection p4Connection;
    @Mock private IOptionsServer server;

    private P4DiffAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        when(p4Connection.getServer()).thenReturn(server);
        analyzer = new P4DiffAnalyzer();
    }

    @Test
    void loadsContentForOnlyThePassedSubset() throws P4JavaException {
        // given - three modified files exist, but only Foo and Bar are handed to the content load
        SourceFileDiffContext foo = modifiedDiff(FOO_DEPOT);
        SourceFileDiffContext bar = modifiedDiff(BAR_DEPOT);
        SourceFileDiffContext baz = modifiedDiff(BAZ_DEPOT);
        Set<SourceFileDiffContext> subset = new HashSet<>(Arrays.asList(foo, bar));

        // getDepotFiles resolves the requested @CL specs to specs carrying the bare depot path
        // (the analyzer reads getDepotPathString() to build the print argv).
        IFileSpec fooSpec = depotSpec(FOO_DEPOT);
        IFileSpec barSpec = depotSpec(BAR_DEPOT);
        when(server.getDepotFiles(any(), anyBoolean())).thenReturn(Arrays.asList(fooSpec, barSpec));
        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(streamFor(printedFiles(Arrays.asList(FOO_DEPOT, BAR_DEPOT),
                        Arrays.asList("class Foo { before }", "class Bar { before }"))))
                .thenReturn(streamFor(printedFiles(Arrays.asList(FOO_DEPOT, BAR_DEPOT),
                        Arrays.asList("class Foo { after }", "class Bar { after }"))));

        P4Context ctx = new P4Context(p4Connection, "main", "103");

        // when - submitted-range content load for the subset only
        analyzer.loadContentForDiffContexts(ctx, subset, "100", false);

        // then - both print passes carry ONLY Foo and Bar (2 entries each); Baz never appears
        ArgumentCaptor<String[]> argvCaptor = ArgumentCaptor.forClass(String[].class);
        verify(server, times(2)).execStreamCmd(eq("print"), argvCaptor.capture());
        assertEquals(new HashSet<>(Arrays.asList(FOO_DEPOT + "@100", BAR_DEPOT + "@100")),
                new HashSet<>(Arrays.asList(argvCaptor.getAllValues().get(0))),
                "before-pass argv must be exactly Foo@100 and Bar@100");
        assertEquals(new HashSet<>(Arrays.asList(FOO_DEPOT + "@103", BAR_DEPOT + "@103")),
                new HashSet<>(Arrays.asList(argvCaptor.getAllValues().get(1))),
                "after-pass argv must be exactly Foo@103 and Bar@103");

        // and - content is populated on the subset, never on the excluded file
        assertEquals("class Foo { before }\n", foo.getSourceContentOriginal());
        assertEquals("class Foo { after }\n", foo.getSourceContentNew());
        assertEquals("class Bar { before }\n", bar.getSourceContentOriginal());
        assertEquals("class Bar { after }\n", bar.getSourceContentNew());
        assertNull(baz.getSourceContentOriginal(), "excluded file must not be fetched");
        assertNull(baz.getSourceContentNew(), "excluded file must not be fetched");
    }

    @Test
    void emptySubsetMakesNoServerCalls() throws P4JavaException {
        // given - nothing to load
        P4Context ctx = new P4Context(p4Connection, "main", "103");

        // when
        analyzer.loadContentForDiffContexts(ctx, Collections.emptySet(), "100", false);

        // then
        verify(server, times(0)).getDepotFiles(any(), anyBoolean());
        verify(server, times(0)).execStreamCmd(eq("print"), any(String[].class));
    }

    /**
     * Build a MODIFY diff context whose depot path is set as the {@code vcsFetchKey} - the handle
     * the content load fetches by.
     *
     * @param depotPath the depot path / fetch key
     * @return the diff context
     */
    private static SourceFileDiffContext modifiedDiff(String depotPath) {
        SourceFileDiffContext diff = new SourceFileDiffContext("/ws/" + depotPath, null, ChangeType.MODIFY);
        diff.setVcsFetchKey(depotPath);
        return diff;
    }

    /**
     * Mock an {@link IFileSpec} carrying only the bare depot path - what {@code getDepotFiles}
     * returns and the analyzer reads to build the {@code p4 print} argv.
     *
     * @param depotPath the depot path
     * @return the stubbed spec
     */
    private static IFileSpec depotSpec(String depotPath) {
        IFileSpec spec = org.mockito.Mockito.mock(IFileSpec.class);
        when(spec.getDepotPathString()).thenReturn(depotPath);
        return spec;
    }

    /**
     * Build a {@code p4 print}-shaped blob: a {@code //path - edit change 1 (text)} header per
     * file followed by its content.
     *
     * @param depotPaths the depot paths to emit headers for
     * @param contents per-file content (same length)
     * @return the assembled blob
     */
    private static String printedFiles(List<String> depotPaths, List<String> contents) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depotPaths.size(); i++) {
            b.append(depotPaths.get(i)).append("#1 - edit change 1 (text)\n");
            b.append(contents.get(i));
            if (!contents.get(i).endsWith("\n")) {
                b.append('\n');
            }
        }
        return b.toString();
    }

    private static ByteArrayInputStream streamFor(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
