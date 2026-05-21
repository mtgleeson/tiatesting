package org.tiatesting.vcs.perforce;

import com.perforce.p4java.client.IClient;
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
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link P4DiffAnalyzer} fetches per-revision file content with a single
 * batched {@code execStreamCmd("print", argv)} round-trip per version. The argv is built
 * with explicit {@code //depot/path@<CL>} strings so the server resolves the per-file
 * revision at that changelist (the same way {@code getDepotFiles} originally resolved it).
 * This is the equivalent of running {@code p4 print //path1@1234 //path2@1234} from the
 * command line and avoids p4java's {@code IFileSpec -> wire args} conversion that silently
 * drops the rev for batched calls.
 *
 * <p>The {@code @CL} annotation is used instead of {@code #rev} because the file specs
 * returned by {@code getDepotFiles(...@CL, false)} don't reliably populate a revision-number
 * field for all file-action types. Newly-added files at their first revision in particular
 * can have {@code getEndRevision() == -1}, which produces a bogus {@code #-1} suffix that
 * {@code p4 print} silently drops from the batched response.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4DiffAnalyzerBatchedContentTest {

    private static final String STREAM = "//apps/example";
    private static final String SOURCE_DIR_DEPOT = "//apps/example/src/main/java";
    private static final String FOO_DEPOT = "//apps/example/src/main/java/com/example/Foo.java";
    private static final String BAR_DEPOT = "//apps/example/src/main/java/com/example/Bar.java";

    @Mock private P4Connection p4Connection;
    @Mock private IOptionsServer server;
    @Mock private IClient client;

    private P4DiffAnalyzer analyzer;
    private List<IFileSpec> sourceAndTestFilesSpecs;

    @BeforeEach
    void setUp() {
        when(p4Connection.getServer()).thenReturn(server);
        when(p4Connection.getClient()).thenReturn(client);
        when(client.getStream()).thenReturn(STREAM);

        analyzer = new P4DiffAnalyzer();
        sourceAndTestFilesSpecs = Collections.singletonList(spec(SOURCE_DIR_DEPOT, null, null, 0, 0));
    }

    /**
     * For N modified files, the content fetch should fire exactly one
     * {@code execStreamCmd("print", argv)} call per version - so 2 total for the typical
     * "old + new" analysis - regardless of N. Verifies that the per-file
     * {@code IFileSpec.getContents} loop is no longer used.
     */
    @Test
    void contentFetch_isOneBatchedCallPerVersion() throws P4JavaException {
        // given - two modified files in the range, both with a tracked rev
        IFileSpec fooBefore = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 101, 3);
        IFileSpec barBefore = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102, 5);
        IFileSpec fooAfter = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 101, 4);
        IFileSpec barAfter = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102, 6);

        // Stage 1's range query, then before-version metadata, then after-version metadata
        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Arrays.asList(fooBefore, barBefore))
                .thenReturn(Arrays.asList(fooBefore, barBefore))
                .thenReturn(Arrays.asList(fooAfter, barAfter));

        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0, 0);
        IFileSpec barWhere = spec(BAR_DEPOT, "/ws/Bar.java", null, 0, 0);
        when(client.where(any()))
                .thenReturn(sourceAndTestFilesSpecs)
                .thenReturn(Arrays.asList(fooWhere, barWhere));

        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(streamFor(printedFiles(
                        Arrays.asList(FOO_DEPOT, BAR_DEPOT),
                        Arrays.asList(3, 5),
                        Arrays.asList("class Foo {}", "class Bar {}"))))
                .thenReturn(streamFor(printedFiles(
                        Arrays.asList(FOO_DEPOT, BAR_DEPOT),
                        Arrays.asList(4, 6),
                        Arrays.asList("class Foo { /*new*/ }", "class Bar { /*new*/ }"))));

        P4Context ctx = new P4Context(p4Connection, "main", "103");

        // when
        analyzer.buildDiffFilesContext(ctx, "100", Collections.singletonList(SOURCE_DIR_DEPOT), false);

        // then - exactly two batched calls (one per version), never the per-file getContents
        verify(server, times(2)).execStreamCmd(eq("print"), any(String[].class));
        verify(fooBefore, never()).getContents(anyBoolean());
        verify(barBefore, never()).getContents(anyBoolean());
        verify(fooAfter, never()).getContents(anyBoolean());
        verify(barAfter, never()).getContents(anyBoolean());
    }

    /**
     * Each {@code argv} entry must carry {@code @<CL>} so the server resolves the per-file
     * revision at that changelist. Without this, p4java's higher-level {@code getFileContents}
     * API silently defaults to head and the "before" / "after" passes return identical
     * content.
     */
    @Test
    void argv_usesAtCLAnnotation() throws P4JavaException {
        // given - one modified file at a tracked rev
        IFileSpec foo = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 101, 7);

        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Collections.singletonList(foo))
                .thenReturn(Collections.singletonList(foo))
                .thenReturn(Collections.singletonList(foo));

        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0, 0);
        when(client.where(any()))
                .thenReturn(sourceAndTestFilesSpecs)
                .thenReturn(Collections.singletonList(fooWhere));

        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(streamFor(printedFiles(
                        Collections.singletonList(FOO_DEPOT),
                        Collections.singletonList(7),
                        Collections.singletonList("class Foo { v7 }"))))
                .thenReturn(streamFor(printedFiles(
                        Collections.singletonList(FOO_DEPOT),
                        Collections.singletonList(7),
                        Collections.singletonList("class Foo { v7 new }"))));

        P4Context ctx = new P4Context(p4Connection, "main", "103");

        // when
        analyzer.buildDiffFilesContext(ctx, "100", Collections.singletonList(SOURCE_DIR_DEPOT), false);

        // then - both passes' argvs use the @CL annotation, first against baseCl=100, second against clTo=103
        ArgumentCaptor<String[]> argvCaptor = ArgumentCaptor.forClass(String[].class);
        verify(server, times(2)).execStreamCmd(eq("print"), argvCaptor.capture());
        List<String[]> argvs = argvCaptor.getAllValues();
        assertEquals(1, argvs.get(0).length);
        assertEquals(FOO_DEPOT + "@100", argvs.get(0)[0],
                "first batched print (forOriginal=true) must target baseCl");
        assertEquals(1, argvs.get(1).length);
        assertEquals(FOO_DEPOT + "@103", argvs.get(1)[0],
                "second batched print (forOriginal=false) must target clTo");
    }

    /**
     * Regression test for newly-added files mixed in with existing edits. Before the
     * {@code @CL} fix, the argv was built as
     * {@code depotPath + "#" + fileSpec.getEndRevision()}; for an ADDed file at its first
     * revision p4java's {@code getDepotFiles} can leave {@code getEndRevision()} as
     * {@code -1}, producing a bogus {@code //path#-1} argv that {@code p4 print} silently
     * drops from the batched response (the failing file's content never makes it back, the
     * other files in the batch succeed, and the analyzer throws "no content for depot path"
     * for that one file). Switching to {@code @CL} sidesteps this entirely - Perforce
     * resolves the per-file revision on the server side, so an ADDed file at rev 1 is just
     * as routable as an EDIT at rev 23.
     */
    @Test
    void argv_usesAtCLNotHashRev_soNewFilesInBatchStillResolve() throws P4JavaException {
        // given - an existing edited file at rev 7 alongside a newly-added file whose spec
        // has endRevision=-1 (the pathological case that was producing the failure)
        IFileSpec barEdit = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102, 7);
        IFileSpec fooNew = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.ADD, 103, -1);
        IFileSpec barEditNewer = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102, 8);

        when(server.getDepotFiles(any(), anyBoolean()))
                // Stage 1: range query returns both files (one ADD, one EDIT)
                .thenReturn(Arrays.asList(barEdit, fooNew))
                // forOriginal=true: only the EDIT file is in the before-pass argv (ADD excluded)
                .thenReturn(Collections.singletonList(barEdit))
                // forOriginal=false: both files in the after-pass
                .thenReturn(Arrays.asList(barEditNewer, fooNew));

        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0, 0);
        IFileSpec barWhere = spec(BAR_DEPOT, "/ws/Bar.java", null, 0, 0);
        when(client.where(any()))
                .thenReturn(sourceAndTestFilesSpecs)
                .thenReturn(Arrays.asList(fooWhere, barWhere));

        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(streamFor(printedFiles(
                        Collections.singletonList(BAR_DEPOT),
                        Collections.singletonList(7),
                        Collections.singletonList("class Bar { v7 }"))))
                .thenReturn(streamFor(printedFiles(
                        Arrays.asList(BAR_DEPOT, FOO_DEPOT),
                        Arrays.asList(8, 1),
                        Arrays.asList("class Bar { v8 }", "class Foo { new file }"))));

        P4Context ctx = new P4Context(p4Connection, "main", "103");

        // when
        analyzer.buildDiffFilesContext(ctx, "100", Collections.singletonList(SOURCE_DIR_DEPOT), false);

        // then - the forOriginal=false argv carries @103 for BOTH files (not #-1 for the ADD)
        ArgumentCaptor<String[]> argvCaptor = ArgumentCaptor.forClass(String[].class);
        verify(server, times(2)).execStreamCmd(eq("print"), argvCaptor.capture());
        String[] afterArgv = argvCaptor.getAllValues().get(1);
        assertEquals(2, afterArgv.length);
        // sort positions are deterministic by source-file-dir iteration, but exact order can
        // vary; assert the set of values rather than positions.
        Set<String> argvSet = new HashSet<>(Arrays.asList(afterArgv));
        assertTrue(argvSet.contains(FOO_DEPOT + "@103"),
                "argv must contain ADDed file's @CL form, not #-1. argv was: " + argvSet);
        assertTrue(argvSet.contains(BAR_DEPOT + "@103"),
                "argv must contain edited file's @CL form. argv was: " + argvSet);
    }

    /**
     * Per-file content (sliced verbatim between header line positions in the byte buffer)
     * is attributed back to the right {@link SourceFileDiffContext} and preserves CRLF line
     * endings exactly as p4 emitted them.
     */
    @Test
    void parsedContent_isMappedToTheCorrectDiffContext() throws P4JavaException {
        // given - Foo edited at CL 101, Bar edited at CL 102
        IFileSpec fooBefore = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 101, 3);
        IFileSpec barBefore = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102, 5);
        IFileSpec fooAfter = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 101, 4);
        IFileSpec barAfter = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102, 6);

        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Arrays.asList(fooBefore, barBefore))
                .thenReturn(Arrays.asList(fooBefore, barBefore))
                .thenReturn(Arrays.asList(fooAfter, barAfter));

        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0, 0);
        IFileSpec barWhere = spec(BAR_DEPOT, "/ws/Bar.java", null, 0, 0);
        when(client.where(any()))
                .thenReturn(sourceAndTestFilesSpecs)
                .thenReturn(Arrays.asList(fooWhere, barWhere));

        // Realistic CRLF content - the parser slices verbatim, so the diff context sees the
        // same byte shape as the serial fetch would have produced.
        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(streamFor(printedFiles(
                        Arrays.asList(FOO_DEPOT, BAR_DEPOT),
                        Arrays.asList(3, 5),
                        Arrays.asList("class Foo {}\r\n// before\r\n", "class Bar {}\r\n// before\r\n"))))
                .thenReturn(streamFor(printedFiles(
                        Arrays.asList(FOO_DEPOT, BAR_DEPOT),
                        Arrays.asList(4, 6),
                        Arrays.asList("class Foo {}\r\n// after\r\n", "class Bar {}\r\n// after\r\n"))));

        P4Context ctx = new P4Context(p4Connection, "main", "103");

        // when
        Set<SourceFileDiffContext> contexts =
                analyzer.buildDiffFilesContext(ctx, "100", Collections.singletonList(SOURCE_DIR_DEPOT), false);

        // then
        Map<String, SourceFileDiffContext> byPath = new HashMap<>();
        for (SourceFileDiffContext c : contexts) {
            byPath.put(c.getOldFilePath() != null ? c.getOldFilePath() : c.getNewFilePath(), c);
        }
        assertEquals(2, byPath.size(), "expected one diff context per file. Got: " + byPath.keySet());

        SourceFileDiffContext foo = byPath.get("/ws/Foo.java");
        assertNotNull(foo, "Foo diff context missing");
        assertEquals(ChangeType.MODIFY, foo.getChangeType());
        assertEquals("class Foo {}\r\n// before\r\n", foo.getSourceContentOriginal());
        assertEquals("class Foo {}\r\n// after\r\n", foo.getSourceContentNew());

        SourceFileDiffContext bar = byPath.get("/ws/Bar.java");
        assertNotNull(bar, "Bar diff context missing");
        assertEquals(ChangeType.MODIFY, bar.getChangeType());
        assertEquals("class Bar {}\r\n// before\r\n", bar.getSourceContentOriginal());
        assertEquals("class Bar {}\r\n// after\r\n", bar.getSourceContentNew());
    }

    /**
     * Specs returned by {@code getDepotFiles} with a null depot-path string (file missing
     * at this revision - typical for merge-of-delete cases) are filtered out before the
     * batched print call, so the {@code execStreamCmd} argv only carries valid specs.
     */
    @Test
    void specsWithNullDepotPath_areFilteredOutBeforeBatchCall() throws P4JavaException {
        // given - one valid file, one "missing" spec (null depot path)
        IFileSpec foo = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 101, 3);
        IFileSpec missing = spec(null, null, null, 999, 0);

        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Collections.singletonList(foo))
                .thenReturn(Arrays.asList(foo, missing))
                .thenReturn(Arrays.asList(foo, missing));

        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0, 0);
        when(client.where(any()))
                .thenReturn(sourceAndTestFilesSpecs)
                .thenReturn(Collections.singletonList(fooWhere));

        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(streamFor(printedFiles(
                        Collections.singletonList(FOO_DEPOT),
                        Collections.singletonList(3),
                        Collections.singletonList("class Foo { v3 }"))))
                .thenReturn(streamFor(printedFiles(
                        Collections.singletonList(FOO_DEPOT),
                        Collections.singletonList(3),
                        Collections.singletonList("class Foo { v3 new }"))));

        P4Context ctx = new P4Context(p4Connection, "main", "103");

        // when
        Set<SourceFileDiffContext> contexts =
                analyzer.buildDiffFilesContext(ctx, "100", Collections.singletonList(SOURCE_DIR_DEPOT), false);

        // then - one context, the missing spec didn't propagate
        assertEquals(1, contexts.size());
        SourceFileDiffContext fooCtx = contexts.iterator().next();
        // printedFiles() appends a trailing "\n" if the content doesn't end with one - so
        // the slicer correctly preserves that as part of the file content.
        assertEquals("class Foo { v3 }\n", fooCtx.getSourceContentOriginal());
        assertEquals("class Foo { v3 new }\n", fooCtx.getSourceContentNew());
    }

    /**
     * If a file Tia asked for in the batched call is missing from the parsed stream (no
     * header for it at all), the analyzer throws rather than leaving the matching
     * {@link SourceFileDiffContext} with null content - silent mis-selection is worse than
     * a loud failure here.
     */
    @Test
    void contentMissingFromParsedStream_throwsVCSAnalyzerException() throws P4JavaException {
        // given - two files in the diff but the batched stream only carries content for one
        IFileSpec foo = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 101, 3);
        IFileSpec bar = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102, 5);

        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Arrays.asList(foo, bar))
                .thenReturn(Arrays.asList(foo, bar));

        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0, 0);
        IFileSpec barWhere = spec(BAR_DEPOT, "/ws/Bar.java", null, 0, 0);
        when(client.where(any()))
                .thenReturn(sourceAndTestFilesSpecs)
                .thenReturn(Arrays.asList(fooWhere, barWhere));

        // Stream only contains Foo - no header line for Bar
        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(streamFor(printedFiles(
                        Collections.singletonList(FOO_DEPOT),
                        Collections.singletonList(3),
                        Collections.singletonList("class Foo {}"))));

        P4Context ctx = new P4Context(p4Connection, "main", "103");

        // when / then
        VCSAnalyzerException thrown = assertThrows(VCSAnalyzerException.class,
                () -> analyzer.buildDiffFilesContext(ctx, "100", Collections.singletonList(SOURCE_DIR_DEPOT), false));
        assertTrue(thrown.getMessage().contains(BAR_DEPOT),
                "exception should name the missing depot path. Got: " + thrown.getMessage());
    }

    /**
     * Regression test: a prior file in the batch whose content does NOT end with a trailing
     * newline must not hide the next file's header from the parser.
     *
     * <p>The bug: when file A's content has no trailing {@code \n}, file B's header line is
     * physically adjacent to A's content tail in the stream bytes:
     * {@code <A's content>//pathB#1 - add change 1234 (text)\n}. A line-by-line scanner that
     * tokenizes on {@code \n} sees that whole span as a single "line" starting with A's
     * content (not {@code //}), fails the header-shape check, and silently drops B's content.
     * The fix is to find each expected path's header via direct substring search with a
     * boundary check (position 0 or preceded by {@code \n}), not via line tokenization.
     *
     * <p>Source files in the wild commonly lack a trailing newline (especially Java files
     * with editor settings that don't enforce one), so this is a real-world batch shape that
     * has to round-trip cleanly.
     */
    @Test
    void priorFileWithoutTrailingNewline_doesNotHideNextFilesHeader() throws P4JavaException {
        // given - two files; Foo's content deliberately does NOT end with '\n', and Bar's
        // header sits directly against Foo's content tail in the stream bytes
        IFileSpec fooBefore = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 101, 3);
        IFileSpec barBefore = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102, 5);
        IFileSpec fooAfter = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 101, 4);
        IFileSpec barAfter = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102, 6);

        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Arrays.asList(fooBefore, barBefore))
                .thenReturn(Arrays.asList(fooBefore, barBefore))
                .thenReturn(Arrays.asList(fooAfter, barAfter));

        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0, 0);
        IFileSpec barWhere = spec(BAR_DEPOT, "/ws/Bar.java", null, 0, 0);
        when(client.where(any()))
                .thenReturn(sourceAndTestFilesSpecs)
                .thenReturn(Arrays.asList(fooWhere, barWhere));

        // Build the stream manually so we can omit the trailing newline on Foo's content.
        // Shape:
        //   //...Foo#3 - edit change 1 (text)\n
        //   class Foo { v3 }                       <-- NO trailing \n
        //   //...Bar#5 - edit change 1 (text)\n
        //   class Bar { v5 }\n
        String beforeStream =
                FOO_DEPOT + "#3 - edit change 1 (text)\n" +
                "class Foo { v3 }" +
                BAR_DEPOT + "#5 - edit change 1 (text)\n" +
                "class Bar { v5 }\n";
        String afterStream =
                FOO_DEPOT + "#4 - edit change 1 (text)\n" +
                "class Foo { v4 }" +
                BAR_DEPOT + "#6 - edit change 1 (text)\n" +
                "class Bar { v6 }\n";

        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(streamFor(beforeStream))
                .thenReturn(streamFor(afterStream));

        P4Context ctx = new P4Context(p4Connection, "main", "103");

        // when
        Set<SourceFileDiffContext> contexts =
                analyzer.buildDiffFilesContext(ctx, "100", Collections.singletonList(SOURCE_DIR_DEPOT), false);

        // then - both files have content attributed correctly despite Foo's missing trailing \n
        Map<String, SourceFileDiffContext> byPath = new HashMap<>();
        for (SourceFileDiffContext c : contexts) {
            byPath.put(c.getOldFilePath() != null ? c.getOldFilePath() : c.getNewFilePath(), c);
        }
        SourceFileDiffContext foo = byPath.get("/ws/Foo.java");
        SourceFileDiffContext bar = byPath.get("/ws/Bar.java");
        assertNotNull(foo, "Foo's diff context must be present");
        assertNotNull(bar, "Bar's diff context must be present");
        assertEquals("class Foo { v3 }", foo.getSourceContentOriginal(),
                "Foo's before content must be sliced correctly even without a trailing newline");
        assertEquals("class Foo { v4 }", foo.getSourceContentNew(),
                "Foo's after content must be sliced correctly even without a trailing newline");
        assertEquals("class Bar { v5 }\n", bar.getSourceContentOriginal(),
                "Bar's before content must not be lost when prior file lacked a trailing newline");
        assertEquals("class Bar { v6 }\n", bar.getSourceContentNew(),
                "Bar's after content must not be lost when prior file lacked a trailing newline");
    }

    /**
     * A null stream from {@code execStreamCmd} means p4java returned nothing for the batch -
     * after upstream filtering every spec was expected to resolve, so a null stream is an
     * anomaly. The analyzer throws rather than silently mis-selecting tests.
     */
    @Test
    void nullStreamFromBatchedFetch_throwsVCSAnalyzerException() throws P4JavaException {
        // given - one file but execStreamCmd returns null
        IFileSpec foo = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 101, 3);
        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Collections.singletonList(foo))
                .thenReturn(Collections.singletonList(foo));
        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0, 0);
        when(client.where(any()))
                .thenReturn(sourceAndTestFilesSpecs)
                .thenReturn(Collections.singletonList(fooWhere));
        when(server.execStreamCmd(eq("print"), any(String[].class))).thenReturn(null);

        P4Context ctx = new P4Context(p4Connection, "main", "103");

        // when / then
        VCSAnalyzerException thrown = assertThrows(VCSAnalyzerException.class,
                () -> analyzer.buildDiffFilesContext(ctx, "100", Collections.singletonList(SOURCE_DIR_DEPOT), false));
        assertTrue(thrown.getMessage().contains("null stream"),
                "exception should mention the null-stream cause. Got: " + thrown.getMessage());
    }

    /**
     * Build a {@code p4 print}-shaped textual blob for the given depot paths, revisions and
     * contents. Each file gets a {@code //depot/path#<rev> - edit change 1 (text)} header
     * followed by its content; matches the wire format that {@code execStreamCmd("print",...)}
     * returns when headers are emitted.
     *
     * @param depotPaths the depot paths to emit headers for
     * @param revs the revision number for each depot path's header (same length as {@code depotPaths})
     * @param contents the per-file content (same length as {@code depotPaths})
     * @return the assembled multi-file print blob
     */
    private static String printedFiles(List<String> depotPaths, List<Integer> revs, List<String> contents) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depotPaths.size(); i++) {
            b.append(depotPaths.get(i)).append("#").append(revs.get(i)).append(" - edit change 1 (text)\n");
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

    /**
     * Build a Mockito-stubbed {@link IFileSpec} populated with the fields the analyzer
     * reads ({@code depotPathString}, {@code localPathString}, {@code originalPathString},
     * {@code action}, {@code changelistId}, {@code endRevision}). Unstubbed methods return
     * Mockito defaults (null / 0).
     */
    private static IFileSpec spec(String depotPath, String localPath, FileAction action,
                                  int changelistId, int endRevision) {
        IFileSpec s = org.mockito.Mockito.mock(IFileSpec.class);
        when(s.getDepotPathString()).thenReturn(depotPath);
        when(s.getLocalPathString()).thenReturn(localPath);
        when(s.getOriginalPathString()).thenReturn(depotPath);
        when(s.getAction()).thenReturn(action);
        when(s.getChangelistId()).thenReturn(changelistId);
        when(s.getEndRevision()).thenReturn(endRevision);
        return s;
    }
}
