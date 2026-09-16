package org.tiatesting.vcs.perforce;

import com.perforce.p4java.client.IClient;
import com.perforce.p4java.core.file.FileAction;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.server.IOptionsServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the NullPointerException thrown from {@code isFileInSourceOrTestDir}
 * when {@code p4 where} resolves a configured source/test (or tracked library) directory to a
 * spec with no depot path.
 *
 * <p>Distinct cases produce a null (or unusable) depot mapping:
 * <ul>
 *   <li><b>Overlapping client-view mappings.</b> A broad view line re-sourced by a later, more
 *       specific line means {@code p4 where} returns the overridden line flagged as unmapped
 *       (leading {@code -} in the CLI) alongside the real winning mapping. p4java represents the
 *       overridden entry with a null depot path. The directory is genuinely mapped and synced, so
 *       the winning entry must be kept and no warning should be logged.</li>
 *   <li><b>Fully unmapped directory.</b> A directory excluded from the client view resolves to
 *       only null-depot entries, so it is dropped and a warning is logged (its changes can't be
 *       tracked).</li>
 *   <li><b>Mapped but not present locally.</b> A directory that is mapped in the client view but
 *       not present in the local workspace (a restricted library the machine has lost access to,
 *       or a project with no {@code src/main/java}) is dropped and a warning is logged, because
 *       its content can't be read to diff.</li>
 * </ul>
 * A trailing path separator on a queried directory is also trimmed before {@code p4 where} runs,
 * because it otherwise makes p4java's path parsing fail. None of these cases may throw, and a
 * valid changed source file must still be selected.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4DiffAnalyzerSourceDirSpecFilterTest {

    private static final String SOURCE_DIR_DEPOT = "//depot/projects/example/src/main/java";
    private static final String NOT_SYNCED_DIR_DEPOT = "//depot/restricted/secret/src/main/java";
    private static final String UNMAPPED_DIR_LOCAL = "/ws/restricted/secret/src/main/java";
    private static final String FOO_DEPOT = "//depot/projects/example/src/main/java/com/example/Foo.java";
    private static final String FOO_LOCAL = "/ws/example/src/main/java/com/example/Foo.java";

    @TempDir
    File workspaceDir;

    /** A local path that exists on disk (synced). Set from {@link #workspaceDir} in setUp. */
    private String sourceDirLocal;

    /** A local path that does NOT exist on disk (mapped in the view but not synced). */
    private String notSyncedDirLocal;

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

        sourceDirLocal = workspaceDir.getAbsolutePath();
        notSyncedDirLocal = new File(workspaceDir, "not-synced-lib").getAbsolutePath();

        analyzer = new P4DiffAnalyzer();
        p4Context = new P4Context(p4Connection, "main", "103");
    }

    /**
     * A source dir covered by overlapping client-view lines resolves via {@code p4 where} to an
     * overridden entry with a null depot path plus the winning entry. The winning entry must be
     * kept so the changed file under it is still selected, and the null override entry must not
     * cause a NullPointerException.
     *
     * @throws P4JavaException never - mock setup only
     */
    @Test
    void overlappingViewOverride_keepsWinningMappingAndSelectsFile() throws P4JavaException {
        // given - the source dir resolves to two where entries for the SAME local path: an
        // overridden (null depot) entry and the winning mapping. The local path exists on disk.
        IFileSpec overriddenEntry = whereSpec(null, sourceDirLocal);
        IFileSpec winningEntry = whereSpec(SOURCE_DIR_DEPOT, sourceDirLocal);
        IFileSpec fooWhere = whereSpec(FOO_DEPOT, FOO_LOCAL);

        // first `where` is for the source/test dirs, second is for the changed file paths
        when(client.where(any()))
                .thenReturn(Arrays.asList(overriddenEntry, winningEntry))
                .thenReturn(Collections.singletonList(fooWhere));

        IFileSpec fooEdited = rangeSpec(FOO_DEPOT, FileAction.EDIT, 101);
        when(server.getDepotFiles(any(), eq(true))).thenReturn(Collections.singletonList(fooEdited));

        // when - range mode (checkLocalChanges=false), from stored CL 100 to head CL 103
        Set<SourceFileDiffContext> contexts = assertDoesNotThrow(() ->
                analyzer.getDiffFiles(p4Context, "100", Collections.singletonList(SOURCE_DIR_DEPOT), false));

        // then - the winning mapping survives and the changed file is selected
        assertEquals(Collections.singletonList(FOO_DEPOT), trackedPaths(contexts));
    }

    /**
     * A directory excluded from the client view resolves to only null-depot entries. It must be
     * dropped without throwing, and a changed file under a different, validly mapped directory
     * must still be selected.
     *
     * @throws P4JavaException never - mock setup only
     */
    @Test
    void fullyUnmappedDir_isFilteredAndDoesNotBreakSelection() throws P4JavaException {
        // given - a valid source dir and a fully-unmapped dir that p4 where can't resolve at all
        // (all path fields null, only a status message), as happens for a path not under the
        // client root or not in the client view.
        IFileSpec validSrcDir = whereSpec(SOURCE_DIR_DEPOT, sourceDirLocal);
        IFileSpec unmappedDir = errorSpec("Path '" + UNMAPPED_DIR_LOCAL + "' is not under client's root '/ws'.");
        IFileSpec fooWhere = whereSpec(FOO_DEPOT, FOO_LOCAL);

        when(client.where(any()))
                .thenReturn(Arrays.asList(validSrcDir, unmappedDir))
                .thenReturn(Collections.singletonList(fooWhere));

        IFileSpec fooEdited = rangeSpec(FOO_DEPOT, FileAction.EDIT, 101);
        when(server.getDepotFiles(any(), eq(true))).thenReturn(Collections.singletonList(fooEdited));

        List<String> sourceAndTestFiles = Arrays.asList(SOURCE_DIR_DEPOT, UNMAPPED_DIR_LOCAL);

        // when
        Set<SourceFileDiffContext> contexts = assertDoesNotThrow(() ->
                analyzer.getDiffFiles(p4Context, "100", sourceAndTestFiles, false));

        // then - the unmapped dir is filtered out and the valid changed file is still selected
        assertEquals(Collections.singletonList(FOO_DEPOT), trackedPaths(contexts));
    }

    /**
     * A directory that IS mapped in the client view (valid depot path) but is not synced into the
     * local workspace (its resolved local path doesn't exist on disk) must be dropped without
     * throwing, and a changed file under a different, synced directory must still be selected.
     * This is the restricted-library-with-no-access case the user hit.
     *
     * @throws P4JavaException never - mock setup only
     */
    @Test
    void mappedButNotSyncedDir_isFilteredAndDoesNotBreakSelection() throws P4JavaException {
        // given - a synced source dir and a mapped-but-not-synced dir (valid depot, local path
        // that doesn't exist on disk).
        IFileSpec validSrcDir = whereSpec(SOURCE_DIR_DEPOT, sourceDirLocal);
        IFileSpec notSyncedDir = whereSpec(NOT_SYNCED_DIR_DEPOT, notSyncedDirLocal);
        IFileSpec fooWhere = whereSpec(FOO_DEPOT, FOO_LOCAL);

        when(client.where(any()))
                .thenReturn(Arrays.asList(validSrcDir, notSyncedDir))
                .thenReturn(Collections.singletonList(fooWhere));

        IFileSpec fooEdited = rangeSpec(FOO_DEPOT, FileAction.EDIT, 101);
        when(server.getDepotFiles(any(), eq(true))).thenReturn(Collections.singletonList(fooEdited));

        List<String> sourceAndTestFiles = Arrays.asList(SOURCE_DIR_DEPOT, notSyncedDirLocal);

        // when
        Set<SourceFileDiffContext> contexts = assertDoesNotThrow(() ->
                analyzer.getDiffFiles(p4Context, "100", sourceAndTestFiles, false));

        // then - the not-synced dir is filtered out and the synced changed file is still selected
        assertEquals(Collections.singletonList(FOO_DEPOT), trackedPaths(contexts));
    }

    /**
     * A trailing path separator on a queried directory (as tracked-library dirs carry from their
     * configured coordinates, e.g. {@code .../fcm/ml/}) must be trimmed before the path is handed
     * to {@code p4 where}, otherwise p4java's path parsing fails with "Null directory not allowed".
     *
     * @throws P4JavaException never - mock setup only
     */
    @Test
    void trailingSlashesAreTrimmedBeforeWhere() throws P4JavaException {
        // given - a source dir configured with a trailing slash
        IFileSpec validSrcDir = whereSpec(SOURCE_DIR_DEPOT, sourceDirLocal);
        IFileSpec fooWhere = whereSpec(FOO_DEPOT, FOO_LOCAL);

        when(client.where(any()))
                .thenReturn(Collections.singletonList(validSrcDir))
                .thenReturn(Collections.singletonList(fooWhere));

        IFileSpec fooEdited = rangeSpec(FOO_DEPOT, FileAction.EDIT, 101);
        when(server.getDepotFiles(any(), eq(true))).thenReturn(Collections.singletonList(fooEdited));

        // when - the configured dir ends with a slash
        assertDoesNotThrow(() ->
                analyzer.getDiffFiles(p4Context, "100", Collections.singletonList(SOURCE_DIR_DEPOT + "/"), false));

        // then - the path passed to the first where() call has no trailing separator
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IFileSpec>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.atLeastOnce()).where(captor.capture());
        List<IFileSpec> firstWhereInput = captor.getAllValues().get(0);
        assertFalse(firstWhereInput.isEmpty(), "The sanitized directory should still be queried.");
        String queried = firstWhereInput.get(0).getOriginalPathString();
        assertFalse(queried.endsWith("/"), "Trailing slash must be trimmed before p4 where: " + queried);
        assertTrue(queried.endsWith("src/main/java"), "The directory itself must be preserved: " + queried);
    }

    /**
     * whereResultKey prefers the local path when present so the winning mapping and its overridden
     * sibling entry group under the same directory.
     */
    @Test
    void whereResultKey_prefersLocalPath() {
        // given
        IFileSpec spec = whereSpec(SOURCE_DIR_DEPOT, sourceDirLocal);

        // when / then
        assertEquals(sourceDirLocal, P4DiffAnalyzer.whereResultKey(spec));
    }

    /**
     * whereResultKey falls back to the p4 status message when every path field is null (an
     * unresolved directory), so the resulting WARN names the offending path instead of {@code <unknown>}.
     */
    @Test
    void whereResultKey_fallsBackToStatusMessageWhenAllPathsNull() {
        // given - an error/info spec with no paths, only a status message naming the dir
        String message = "//depot/some/lib/... - file(s) not in client view.";
        IFileSpec spec = errorSpec(message);

        // when / then
        assertEquals(message, P4DiffAnalyzer.whereResultKey(spec));
    }

    /**
     * whereResultKey never returns null (it must be safe as a grouping classifier), returning a
     * placeholder only when nothing at all is available.
     */
    @Test
    void whereResultKey_returnsPlaceholderWhenNothingAvailable() {
        // given - a spec with no paths and no status message
        IFileSpec spec = errorSpec(null);

        // when / then
        assertEquals("<unknown>", P4DiffAnalyzer.whereResultKey(spec));
    }

    /**
     * Extract the tracked depot paths (VCS fetch keys) from the resulting diff contexts.
     *
     * @param contexts the diff contexts returned by the analyzer
     * @return the tracked depot paths
     */
    private static List<String> trackedPaths(Set<SourceFileDiffContext> contexts) {
        return contexts.stream()
                .map(SourceFileDiffContext::getVcsFetchKey)
                .collect(Collectors.toList());
    }

    /**
     * Build a stubbed {@code p4 where} error/info spec: every path field is null and only a
     * status message is set, as p4java returns for a directory it cannot resolve.
     *
     * @param statusMessage the p4 status message (may be null)
     * @return the stubbed file spec
     */
    private IFileSpec errorSpec(String statusMessage) {
        IFileSpec spec = mock(IFileSpec.class);
        when(spec.getDepotPathString()).thenReturn(null);
        when(spec.getLocalPathString()).thenReturn(null);
        when(spec.getClientPathString()).thenReturn(null);
        when(spec.getOriginalPathString()).thenReturn(null);
        when(spec.getStatusMessage()).thenReturn(statusMessage);
        return spec;
    }

    /**
     * Build a stubbed {@code p4 where} resolution spec carrying a depot path and a local path.
     *
     * @param depotPath the depot path, or null for an overridden/excluded entry
     * @param localPath the resolved local workspace path (used to group entries by directory)
     * @return the stubbed file spec
     */
    private IFileSpec whereSpec(String depotPath, String localPath) {
        IFileSpec spec = mock(IFileSpec.class);
        when(spec.getDepotPathString()).thenReturn(depotPath);
        when(spec.getLocalPathString()).thenReturn(localPath);
        when(spec.getOriginalPathString()).thenReturn(depotPath);
        return spec;
    }

    /**
     * Build a stubbed range-query result spec for a changed file.
     *
     * @param depotPath the depot path of the changed file
     * @param action the change action
     * @param changelistId the changelist the change was submitted in
     * @return the stubbed file spec
     */
    private IFileSpec rangeSpec(String depotPath, FileAction action, int changelistId) {
        IFileSpec spec = mock(IFileSpec.class);
        when(spec.getDepotPathString()).thenReturn(depotPath);
        when(spec.getOriginalPathString()).thenReturn(depotPath);
        when(spec.getAction()).thenReturn(action);
        when(spec.getChangelistId()).thenReturn(changelistId);
        return spec;
    }
}
