package org.tiatesting.vcs.perforce;

import com.perforce.p4java.client.IClient;
import com.perforce.p4java.core.file.FileAction;
import com.perforce.p4java.core.file.FileSpecBuilder;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.server.IOptionsServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link P4DiffAnalyzer#getSourceFilesImpactedFromPreviousSubmit} resolves a
 * changelist range with a single {@code p4 files} round-trip instead of the older per-CL
 * {@code p4 describe} loop, AND that the range query uses {@code allRevs=true} so files
 * changed in multiple CLs in the range come back one entry per (file, CL) - required for
 * the action-override semantics in {@code buildDiffContext} (ADD-then-EDIT-in-range
 * collapses to ADD).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4DiffAnalyzerChangelistRangeTest {

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
    void setUp() throws P4JavaException {
        when(p4Connection.getServer()).thenReturn(server);
        when(p4Connection.getClient()).thenReturn(client);
        when(client.getStream()).thenReturn(STREAM);

        analyzer = new P4DiffAnalyzer();

        IFileSpec srcDirSpec = spec(SOURCE_DIR_DEPOT, null, null, 0);
        sourceAndTestFilesSpecs = Collections.singletonList(srcDirSpec);
    }

    /**
     * When there are N changelists in the range, the analyzer should make exactly one
     * {@code getDepotFiles} call for the range and zero {@code getChangelist} calls. The
     * old per-CL flow made one {@code getChangelist} round-trip per CL.
     */
    @Test
    void singleRoundTrip_forChangelistRange() throws P4JavaException {
        // given - three CLs in the range, four file changes across them
        IFileSpec fooAddedAt101 = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.ADD, 101);
        IFileSpec barEditedAt102 = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102);
        IFileSpec fooEditedAt103 = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 103);
        when(server.getDepotFiles(any(), eq(true)))
                .thenReturn(Arrays.asList(fooAddedAt101, barEditedAt102, fooEditedAt103));

        // mock the `where` call inside buildDiffContextsForFileSpecs - returns local-path
        // resolution for each depot path the analyzer asks about.
        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0);
        IFileSpec barWhere = spec(BAR_DEPOT, "/ws/Bar.java", null, 0);
        when(client.where(any())).thenReturn(Arrays.asList(fooWhere, barWhere));

        // when
        Map<String, SourceFileDiffContext> contexts =
                analyzer.getSourceFilesImpactedFromPreviousSubmit(p4Connection, "100", "103", sourceAndTestFilesSpecs);

        // then - exactly one range query, no per-CL fetches
        verify(server, times(1)).getDepotFiles(any(), eq(true));
        verify(server, never()).getChangelist(anyInt());

        // and - two distinct files end up in the diff contexts
        assertEquals(2, contexts.size(), "expected Foo and Bar in the diff contexts");
        assertTrue(contexts.containsKey(FOO_DEPOT));
        assertTrue(contexts.containsKey(BAR_DEPOT));
    }

    /**
     * When the same file is changed in multiple changelists within the range, the analyzer
     * must apply the action-override semantics from {@code buildDiffContext} (e.g. add then
     * edit collapses to add, edit then delete collapses to delete). The new range-based
     * flow returns all per-CL revisions in the range; {@code sortFileChanges} orders them
     * by CL so the override pass sees them oldest-first.
     */
    @Test
    void multipleRevisionsInRange_collapseToFinalChangeType() throws P4JavaException {
        // given - Foo added at CL 101 then edited at CL 103; Bar edited at CL 102 then deleted at CL 103
        IFileSpec fooAddedAt101 = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.ADD, 101);
        IFileSpec barEditedAt102 = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.EDIT, 102);
        IFileSpec fooEditedAt103 = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 103);
        IFileSpec barDeletedAt103 = spec(BAR_DEPOT, "/ws/Bar.java", FileAction.DELETE, 103);
        when(server.getDepotFiles(any(), eq(true)))
                .thenReturn(Arrays.asList(fooAddedAt101, barEditedAt102, fooEditedAt103, barDeletedAt103));

        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0);
        IFileSpec barWhere = spec(BAR_DEPOT, "/ws/Bar.java", null, 0);
        when(client.where(any())).thenReturn(Arrays.asList(fooWhere, barWhere));

        // when
        Map<String, SourceFileDiffContext> contexts =
                analyzer.getSourceFilesImpactedFromPreviousSubmit(p4Connection, "100", "103", sourceAndTestFilesSpecs);

        // then - Foo collapses from (add then edit) to ADD, Bar collapses from (edit then delete) to DELETE
        SourceFileDiffContext foo = contexts.get(FOO_DEPOT);
        assertNotNull(foo, "Foo should be in the contexts");
        assertEquals(ChangeType.ADD, foo.getChangeType(),
                "add followed by edit in the same range should collapse to add");

        SourceFileDiffContext bar = contexts.get(BAR_DEPOT);
        assertNotNull(bar, "Bar should be in the contexts");
        assertEquals(ChangeType.DELETE, bar.getChangeType(),
                "edit followed by delete in the same range should collapse to delete");
    }

    /**
     * An empty range query result returns an empty diff-context map and does not throw — the
     * downstream {@code getChangesSinceLastRunCL} short-circuits the content fetch when the
     * map is empty.
     */
    @Test
    void emptyRange_returnsEmptyMap() throws P4JavaException {
        // given
        when(server.getDepotFiles(any(), eq(true))).thenReturn(new ArrayList<>());

        // when
        Map<String, SourceFileDiffContext> contexts =
                analyzer.getSourceFilesImpactedFromPreviousSubmit(p4Connection, "100", "100", sourceAndTestFilesSpecs);

        // then
        assertTrue(contexts.isEmpty());
        verify(server, never()).getChangelist(anyInt());
    }

    /**
     * Regression test for the {@code allRevs=true} requirement. With {@code allRevs=false},
     * the range query returns only the highest revision per file - so a file added in one CL
     * and edited in a later CL within the range comes back with action=EDIT alone, gets
     * classified as MODIFY, and the forOriginal=true content fetch then tries to read the
     * file at baseCl (where it didn't exist), producing "No file found in P4" log noise.
     *
     * <p>With {@code allRevs=true}, the same file comes back once per CL it was changed in,
     * sortFileChanges orders them oldest-first, and the action-override logic in
     * buildDiffContext collapses ADD-then-EDIT to ADD - so the forOriginal=true filter
     * correctly skips it.
     */
    @Test
    void rangeQuery_setsAllRevsTrue_soAddThenEditCollapsesToAdd() throws P4JavaException {
        // given - Foo added at CL 101 AND edited at CL 103. With allRevs=true Perforce
        // returns both entries; with allRevs=false it would only return the CL 103 EDIT.
        IFileSpec fooAddedAt101 = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.ADD, 101);
        IFileSpec fooEditedAt103 = spec(FOO_DEPOT, "/ws/Foo.java", FileAction.EDIT, 103);
        when(server.getDepotFiles(any(), eq(true)))
                .thenReturn(Arrays.asList(fooAddedAt101, fooEditedAt103));

        IFileSpec fooWhere = spec(FOO_DEPOT, "/ws/Foo.java", null, 0);
        when(client.where(any())).thenReturn(Collections.singletonList(fooWhere));

        // when
        Map<String, SourceFileDiffContext> contexts =
                analyzer.getSourceFilesImpactedFromPreviousSubmit(p4Connection, "100", "103", sourceAndTestFilesSpecs);

        // then - the range query was made with allRevs=true (NOT false). A revert of the
        // production fix would fail this verify call.
        verify(server, times(1)).getDepotFiles(any(), eq(true));
        verify(server, never()).getDepotFiles(any(), eq(false));

        // and - the resulting diff context correctly classifies Foo as ADD (not MODIFY)
        SourceFileDiffContext foo = contexts.get(FOO_DEPOT);
        assertNotNull(foo, "Foo should be in the contexts");
        assertEquals(ChangeType.ADD, foo.getChangeType(),
                "ADD-then-EDIT in the range must collapse to ADD, not stay as MODIFY");
    }

    /**
     * Build a Mockito-stubbed {@link IFileSpec} with just the fields the analyzer reads. p4java's
     * IFileSpec has dozens of methods; we stub only the ones touched by {@code buildDiffContextsForFileSpecs}
     * + {@code sortFileChanges} and rely on Mockito defaults (null / 0) for the rest.
     *
     * @param depotPath   the depot-path string returned by {@code getDepotPathString}
     * @param localPath   the local-path string returned by {@code getLocalPathString} (may be null)
     * @param action      the {@link FileAction} returned by {@code getAction} (may be null)
     * @param changelistId the int returned by {@code getChangelistId}
     * @return the stubbed file spec
     */
    private static IFileSpec spec(String depotPath, String localPath, FileAction action, int changelistId) {
        IFileSpec s = org.mockito.Mockito.mock(IFileSpec.class);
        when(s.getDepotPathString()).thenReturn(depotPath);
        when(s.getLocalPathString()).thenReturn(localPath);
        when(s.getOriginalPathString()).thenReturn(depotPath);
        when(s.getAction()).thenReturn(action);
        when(s.getChangelistId()).thenReturn(changelistId);
        return s;
    }
}
