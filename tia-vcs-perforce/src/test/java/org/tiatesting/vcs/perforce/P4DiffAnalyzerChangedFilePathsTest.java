package org.tiatesting.vcs.perforce;

import com.perforce.p4java.client.IClient;
import com.perforce.p4java.core.file.IExtendedFileSpec;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.option.server.GetExtendedFilesOptions;
import com.perforce.p4java.server.IOptionsServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link P4DiffAnalyzer#getChangedFilePaths} - the unfiltered changed-paths feed
 * for static test selection rules. Verifies the client-view-based normalization: depot paths
 * resolve to local paths via {@code p4 where} and are relativized against the client
 * workspace root, which makes stream import/overlay-path files (depot path outside the
 * stream) visible, and works for classic non-stream clients.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4DiffAnalyzerChangedFilePathsTest {

    private static final String WORKSPACE_ROOT = "/ws/root";
    private static final String IN_STREAM_DEPOT = "//apps/example/myproject/src/main/resources/db/V001.sql";
    private static final String IMPORTED_DEPOT = "//shared/sql-lib/schema/V002.sql";

    @Mock private P4Connection p4Connection;
    @Mock private IOptionsServer server;
    @Mock private IClient client;

    private P4DiffAnalyzer analyzer;
    private P4Context p4Context;

    @BeforeEach
    void setUp() {
        when(p4Connection.getServer()).thenReturn(server);
        when(p4Connection.getClient()).thenReturn(client);
        when(client.getName()).thenReturn("testclient");
        when(client.getRoot()).thenReturn(WORKSPACE_ROOT);

        analyzer = new P4DiffAnalyzer();
        p4Context = new P4Context(p4Connection, "main", "103");
    }

    @Test
    void localMode_importPathFileIsVisibleViaClientViewResolution() throws P4JavaException {
        // given - one opened file in the stream and one mapped in via a stream import path;
        // both have local paths under the workspace root
        mockOpenedFiles(IN_STREAM_DEPOT, IMPORTED_DEPOT);
        mockWhere(
                whereSpec(IN_STREAM_DEPOT, WORKSPACE_ROOT + "/myproject/src/main/resources/db/V001.sql"),
                whereSpec(IMPORTED_DEPOT, WORKSPACE_ROOT + "/sql-lib/schema/V002.sql"));

        // when
        Set<String> changedPaths = analyzer.getChangedFilePaths(p4Context, "100", true);

        // then - both files are returned, workspace-root-relative
        assertEquals(new HashSet<>(Arrays.asList(
                        "myproject/src/main/resources/db/V001.sql",
                        "sql-lib/schema/V002.sql")),
                changedPaths);
    }

    @Test
    void localMode_windowsLocalPathsAndRootAreNormalizedToForwardSlashes() throws P4JavaException {
        // given - a Windows-style client root and where-resolved local path
        when(client.getRoot()).thenReturn("C:\\p4\\ws");
        mockOpenedFiles(IN_STREAM_DEPOT);
        mockWhere(whereSpec(IN_STREAM_DEPOT, "C:\\p4\\ws\\myproject\\src\\main\\resources\\db\\V001.sql"));

        // when
        Set<String> changedPaths = analyzer.getChangedFilePaths(p4Context, "100", true);

        // then
        assertEquals(Collections.singleton("myproject/src/main/resources/db/V001.sql"), changedPaths);
    }

    @Test
    void localMode_unmappedAndOutsideRootEntriesAreSkipped() throws P4JavaException {
        // given - one resolvable file, one where-entry without a local mapping, and one
        // whose local path is outside the workspace root
        mockOpenedFiles(IN_STREAM_DEPOT, IMPORTED_DEPOT, "//other/depot/Unrelated.sql");
        mockWhere(
                whereSpec(IN_STREAM_DEPOT, WORKSPACE_ROOT + "/myproject/src/main/resources/db/V001.sql"),
                whereSpec(IMPORTED_DEPOT, null),
                whereSpec("//other/depot/Unrelated.sql", "/elsewhere/Unrelated.sql"));

        // when
        Set<String> changedPaths = analyzer.getChangedFilePaths(p4Context, "100", true);

        // then - only the resolvable, in-root file survives
        assertEquals(Collections.singleton("myproject/src/main/resources/db/V001.sql"), changedPaths);
    }

    @Test
    void localMode_nullDepotPathsAreExcludedFromTheWhereLookup() throws P4JavaException {
        // given - an opened-files result containing an entry with no depot path (e.g. an
        // info/error spec), plus a real file
        IExtendedFileSpec noDepotPath = mock(IExtendedFileSpec.class);
        when(noDepotPath.getDepotPathString()).thenReturn(null);
        IExtendedFileSpec real = openedSpec(IN_STREAM_DEPOT);
        when(server.getExtendedFiles(any(), any(GetExtendedFilesOptions.class)))
                .thenReturn(Arrays.asList(noDepotPath, real));
        mockWhere(whereSpec(IN_STREAM_DEPOT, WORKSPACE_ROOT + "/myproject/src/main/resources/db/V001.sql"));

        // when
        Set<String> changedPaths = analyzer.getChangedFilePaths(p4Context, "100", true);

        // then - the null-path entry never reaches the where call
        ArgumentCaptor<List<IFileSpec>> whereArg = captorOfFileSpecList();
        verify(client).where(whereArg.capture());
        List<String> requestedPaths = whereArg.getValue().stream()
                .map(IFileSpec::getOriginalPathString)
                .collect(Collectors.toList());
        assertEquals(Collections.singletonList(IN_STREAM_DEPOT), requestedPaths);
        assertEquals(Collections.singleton("myproject/src/main/resources/db/V001.sql"), changedPaths);
    }

    @Test
    void rangeMode_queriesTheClientViewNotTheStream() throws P4JavaException {
        // given - a range query result for one in-stream file (the spec mock must be built
        // before the when() call - creating a mock inside thenReturn() trips Mockito's
        // unfinished-stubbing detection)
        IFileSpec changedInRange = rangeSpec(IN_STREAM_DEPOT);
        when(server.getDepotFiles(any(), eq(true)))
                .thenReturn(Collections.singletonList(changedInRange));
        mockWhere(whereSpec(IN_STREAM_DEPOT, WORKSPACE_ROOT + "/myproject/src/main/resources/db/V001.sql"));

        // when
        Set<String> changedPaths = analyzer.getChangedFilePaths(p4Context, "100", false);

        // then - the file-spec path uses client syntax over the full view (imports included),
        // with the range starting one CL after the stored base
        ArgumentCaptor<List<IFileSpec>> depotFilesArg = captorOfFileSpecList();
        verify(server).getDepotFiles(depotFilesArg.capture(), eq(true));
        String queriedPath = depotFilesArg.getValue().get(0).getAnnotatedPreferredPathString();
        assertTrue(queriedPath.startsWith("//testclient/..."),
                "expected a client-syntax view query, got: " + queriedPath);
        assertTrue(queriedPath.contains("101") && queriedPath.contains("103"),
                "expected the CL range 101..103 in: " + queriedPath);
        assertEquals(Collections.singleton("myproject/src/main/resources/db/V001.sql"), changedPaths);
    }

    @Test
    void rangeMode_baseAtHeadReturnsEmptyWithoutServerCalls() throws P4JavaException {
        // given - the stored CL equals the head CL

        // when
        Set<String> changedPaths = analyzer.getChangedFilePaths(p4Context, "103", false);

        // then
        assertTrue(changedPaths.isEmpty());
        verify(server, never()).getDepotFiles(any(), eq(true));
        verify(client, never()).where(any());
    }

    /**
     * Stub the opened-files (fstat) query to return extended file specs for the given depot paths.
     *
     * @param depotPaths the depot paths of the opened files
     * @throws P4JavaException never - mock setup only
     */
    private void mockOpenedFiles(String... depotPaths) throws P4JavaException {
        List<IExtendedFileSpec> specs = Arrays.stream(depotPaths)
                .map(this::openedSpec)
                .collect(Collectors.toList());
        when(server.getExtendedFiles(any(), any(GetExtendedFilesOptions.class))).thenReturn(specs);
    }

    /**
     * Stub the {@code p4 where} call to return the given resolution specs.
     *
     * @param whereSpecs the where-result specs
     * @throws P4JavaException never - mock setup only
     */
    private void mockWhere(IFileSpec... whereSpecs) throws P4JavaException {
        when(client.where(any())).thenReturn(Arrays.asList(whereSpecs));
    }

    /**
     * Build a stubbed opened-file (fstat) spec carrying only a depot path.
     *
     * @param depotPath the depot path
     * @return the stubbed extended file spec
     */
    private IExtendedFileSpec openedSpec(String depotPath) {
        IExtendedFileSpec spec = mock(IExtendedFileSpec.class);
        when(spec.getDepotPathString()).thenReturn(depotPath);
        return spec;
    }

    /**
     * Build a stubbed range-query result spec carrying only a depot path.
     *
     * @param depotPath the depot path
     * @return the stubbed file spec
     */
    private IFileSpec rangeSpec(String depotPath) {
        IFileSpec spec = mock(IFileSpec.class);
        when(spec.getDepotPathString()).thenReturn(depotPath);
        return spec;
    }

    /**
     * Build a stubbed {@code p4 where} resolution spec mapping a depot path to a local path.
     *
     * @param depotPath the depot path
     * @param localPath the resolved local workspace path, or null for an unmapped entry
     * @return the stubbed file spec
     */
    private IFileSpec whereSpec(String depotPath, String localPath) {
        IFileSpec spec = mock(IFileSpec.class);
        when(spec.getDepotPathString()).thenReturn(depotPath);
        when(spec.getLocalPathString()).thenReturn(localPath);
        return spec;
    }

    /**
     * Typed captor for {@code List<IFileSpec>} arguments, isolated so the unchecked cast
     * warning lives in one place.
     *
     * @return the captor
     */
    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<IFileSpec>> captorOfFileSpecList() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
