package org.tiatesting.vcs.perforce;

import com.perforce.p4java.client.IClient;
import com.perforce.p4java.core.file.FileAction;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link P4DiffAnalyzer} matches depot paths from {@code p4 files} (stored case)
 * against those from {@code p4 where} (the client view's case) by the server's case rule, and
 * that a changed file {@code p4 where} doesn't map is skipped rather than dereferenced. See the
 * "Perforce depot paths" chapter in {@code WIKI.md}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4DiffAnalyzerDepotPathCaseTest {

    private static final String STORED_FOO = "//tia/main/app/src/main/java/com/example/Foo.java";
    private static final String VIEW_FOO = "//TIA/Main/app/src/main/java/com/example/Foo.java";
    private static final String STORED_BAR = "//tia/main/app/src/main/java/com/example/Bar.java";
    private static final String VIEW_SOURCE_DIR = "//TIA/Main/app/src/main/java";
    private static final String STORED_SOURCE_DIR = "//tia/main/app/src/main/java";

    @Mock private P4Connection p4Connection;
    @Mock private IOptionsServer server;
    @Mock private IClient client;

    private P4DiffAnalyzer analyzer;
    private List<IFileSpec> sourceAndTestFilesSpecs;

    /**
     * Wire the mocked connection, server and client, and configure the view-case source dir.
     */
    @BeforeEach
    void setUp() {
        when(p4Connection.getServer()).thenReturn(server);
        when(p4Connection.getClient()).thenReturn(client);
        when(client.getName()).thenReturn("testclient");

        analyzer = new P4DiffAnalyzer();
        sourceAndTestFilesSpecs = Collections.singletonList(spec(VIEW_SOURCE_DIR, null, null, 0));
    }

    @Test
    void caseInsensitiveServer_viewInADifferentCase_findsTheChangedFile() throws P4JavaException {
        // given - the server ignores case; where answers in the view's case
        when(server.isCaseSensitive()).thenReturn(false);
        IFileSpec fooEdit = spec(STORED_FOO, null, FileAction.EDIT, 101);
        when(server.getDepotFiles(any(), eq(true))).thenReturn(Collections.singletonList(fooEdit));
        List<IFileSpec> whereResults = Collections.singletonList(spec(VIEW_FOO, "/ws/app/Foo.java", null, 0));
        when(client.where(any())).thenReturn(whereResults);

        // when
        Map<String, SourceFileDiffContext> contexts =
                analyzer.getSourceFilesImpactedFromPreviousSubmit(p4Connection, "101", "101",
                        sourceAndTestFilesSpecs, false);

        // then - keyed by the stored depot path, with the local path where resolved
        assertEquals(ChangeType.MODIFY, contexts.get(STORED_FOO).getChangeType());
        assertEquals("/ws/app/Foo.java", contexts.get(STORED_FOO).getOldFilePath());
    }

    @Test
    void caseSensitiveServer_differentCase_isADifferentPath() throws P4JavaException {
        // given - on a case-sensitive server the two spellings are different files
        when(server.isCaseSensitive()).thenReturn(true);
        IFileSpec fooEdit = spec(STORED_FOO, null, FileAction.EDIT, 101);
        when(server.getDepotFiles(any(), eq(true))).thenReturn(Collections.singletonList(fooEdit));
        when(client.where(any())).thenReturn(Collections.emptyList());

        // when
        Map<String, SourceFileDiffContext> contexts =
                analyzer.getSourceFilesImpactedFromPreviousSubmit(p4Connection, "101", "101",
                        sourceAndTestFilesSpecs, false);

        // then - not under the configured source dir
        assertTrue(contexts.isEmpty());
    }

    @Test
    void changedFileWhereDoesNotMap_isSkippedAndTheOthersKept() throws P4JavaException {
        // given - the source dir matches exactly, so only the where lookup is exercised;
        // where maps Bar but returns nothing for Foo
        when(server.isCaseSensitive()).thenReturn(false);
        sourceAndTestFilesSpecs = Collections.singletonList(spec(STORED_SOURCE_DIR, null, null, 0));
        IFileSpec fooEdit = spec(STORED_FOO, null, FileAction.EDIT, 101);
        IFileSpec barEdit = spec(STORED_BAR, null, FileAction.EDIT, 101);
        when(server.getDepotFiles(any(), eq(true))).thenReturn(Arrays.asList(fooEdit, barEdit));
        List<IFileSpec> whereResults = Collections.singletonList(spec(STORED_BAR, "/ws/app/Bar.java", null, 0));
        when(client.where(any())).thenReturn(whereResults);

        // when
        Map<String, SourceFileDiffContext> contexts =
                analyzer.getSourceFilesImpactedFromPreviousSubmit(p4Connection, "101", "101",
                        sourceAndTestFilesSpecs, false);

        // then
        assertFalse(contexts.containsKey(STORED_FOO));
        assertEquals(ChangeType.MODIFY, contexts.get(STORED_BAR).getChangeType());
    }

    @Test
    void whereEntryWithNoDepotPath_isIgnored() throws P4JavaException {
        // given - an overlapping view line comes back with no depot path next to the real mapping
        when(server.isCaseSensitive()).thenReturn(false);
        sourceAndTestFilesSpecs = Collections.singletonList(spec(STORED_SOURCE_DIR, null, null, 0));
        IFileSpec fooEdit = spec(STORED_FOO, null, FileAction.EDIT, 101);
        when(server.getDepotFiles(any(), eq(true))).thenReturn(Collections.singletonList(fooEdit));
        List<IFileSpec> whereResults = Arrays.asList(spec(null, null, null, 0),
                spec(STORED_FOO, "/ws/app/Foo.java", null, 0));
        when(client.where(any())).thenReturn(whereResults);

        // when
        Map<String, SourceFileDiffContext> contexts =
                analyzer.getSourceFilesImpactedFromPreviousSubmit(p4Connection, "101", "101",
                        sourceAndTestFilesSpecs, false);

        // then
        assertEquals("/ws/app/Foo.java", contexts.get(STORED_FOO).getOldFilePath());
    }

    @Test
    void indexOfDepotPath_followsTheCaseRule() {
        // given
        String path = STORED_FOO;

        // when
        int ignoringCase = P4DiffAnalyzer.indexOfDepotPath(path, VIEW_SOURCE_DIR, false);
        int matchingCase = P4DiffAnalyzer.indexOfDepotPath(path, VIEW_SOURCE_DIR, true);

        // then
        assertEquals(0, ignoringCase);
        assertEquals(-1, matchingCase);
    }

    /**
     * Build a Mockito-stubbed {@link IFileSpec} with just the fields the analyzer reads.
     *
     * @param depotPath the depot path returned by {@code getDepotPathString} (may be null)
     * @param localPath the local path returned by {@code getLocalPathString} (may be null)
     * @param action the {@link FileAction} returned by {@code getAction} (may be null)
     * @param changelistId the changelist returned by {@code getChangelistId}
     * @return the stubbed file spec
     */
    private static IFileSpec spec(String depotPath, String localPath, FileAction action, int changelistId) {
        IFileSpec s = mock(IFileSpec.class);
        when(s.getDepotPathString()).thenReturn(depotPath);
        when(s.getLocalPathString()).thenReturn(localPath);
        when(s.getAction()).thenReturn(action);
        when(s.getChangelistId()).thenReturn(changelistId);
        return s;
    }
}
