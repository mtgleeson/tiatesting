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
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link P4DiffAnalyzer#loadFileContents}: workspace-relative paths resolve to depot
 * paths through {@code p4 where}, each side is one {@code p4 files} existence query plus one
 * batched {@code p4 print}, and files missing, deleted or unmapped on a side come back as
 * {@code null} content.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4DiffAnalyzerLoadFileContentsTest {

    private static final String WORKSPACE_ROOT = "/ws/root";
    private static final String ROOT_POM = "pom.xml";
    private static final String MODULE_POM = "module-a/pom.xml";
    private static final String ROOT_POM_DEPOT = "//stream/main/pom.xml";
    private static final String MODULE_POM_DEPOT = "//stream/main/module-a/pom.xml";

    @Mock private P4Connection p4Connection;
    @Mock private IOptionsServer server;
    @Mock private IClient client;

    private P4DiffAnalyzer analyzer;
    private P4Context p4Context;

    /**
     * Wire the mocked connection, server and client, with head at CL 103.
     */
    @BeforeEach
    void setUp() {
        when(p4Connection.getServer()).thenReturn(server);
        when(p4Connection.getClient()).thenReturn(client);
        when(client.getRoot()).thenReturn(WORKSPACE_ROOT);

        analyzer = new P4DiffAnalyzer();
        p4Context = new P4Context(p4Connection, "main", "103");
    }

    @Test
    void rangeMode_printsEachSideOnceAtItsChangelist() throws P4JavaException {
        // given - both POMs exist at the base CL and at head
        mockWhere(whereSpec(ROOT_POM_DEPOT, WORKSPACE_ROOT + "/pom.xml"),
                whereSpec(MODULE_POM_DEPOT, WORKSPACE_ROOT + "/module-a/pom.xml"));
        IFileSpec rootAtBase = filesSpec(ROOT_POM_DEPOT, FileAction.EDIT);
        IFileSpec moduleAtBase = filesSpec(MODULE_POM_DEPOT, FileAction.EDIT);
        IFileSpec rootAtHead = filesSpec(ROOT_POM_DEPOT, FileAction.EDIT);
        IFileSpec moduleAtHead = filesSpec(MODULE_POM_DEPOT, FileAction.EDIT);
        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Arrays.asList(rootAtBase, moduleAtBase))
                .thenReturn(Arrays.asList(rootAtHead, moduleAtHead));
        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(printStream(ROOT_POM_DEPOT, "<root/>", MODULE_POM_DEPOT, "<v1/>"))
                .thenReturn(printStream(ROOT_POM_DEPOT, "<root/>", MODULE_POM_DEPOT, "<v2/>"));

        // when
        Map<String, FileContentPair> contents = analyzer.loadFileContents(p4Context,
                new HashSet<>(Arrays.asList(ROOT_POM, MODULE_POM)), "100", false);

        // then - one batched print per side, at the base CL then at head
        assertEquals(new FileContentPair("<root/>\n", "<root/>\n"), contents.get(ROOT_POM));
        assertEquals(new FileContentPair("<v1/>\n", "<v2/>\n"), contents.get(MODULE_POM));
        ArgumentCaptor<String[]> argv = ArgumentCaptor.forClass(String[].class);
        verify(server, times(2)).execStreamCmd(eq("print"), argv.capture());
        assertEquals(new HashSet<>(Arrays.asList(ROOT_POM_DEPOT + "@100", MODULE_POM_DEPOT + "@100")),
                new HashSet<>(Arrays.asList(argv.getAllValues().get(0))));
        assertEquals(new HashSet<>(Arrays.asList(ROOT_POM_DEPOT + "@103", MODULE_POM_DEPOT + "@103")),
                new HashSet<>(Arrays.asList(argv.getAllValues().get(1))));
    }

    @Test
    void rangeMode_missingAtBaseAndDeletedAtHeadComeBackNull() throws P4JavaException {
        // given - the module POM was added after the base CL; the root POM is deleted at head
        mockWhere(whereSpec(ROOT_POM_DEPOT, WORKSPACE_ROOT + "/pom.xml"),
                whereSpec(MODULE_POM_DEPOT, WORKSPACE_ROOT + "/module-a/pom.xml"));
        IFileSpec rootAtBase = filesSpec(ROOT_POM_DEPOT, FileAction.EDIT);
        IFileSpec moduleMissingAtBase = filesSpec(null, null);
        IFileSpec rootDeletedAtHead = filesSpec(ROOT_POM_DEPOT, FileAction.DELETE);
        IFileSpec moduleAtHead = filesSpec(MODULE_POM_DEPOT, FileAction.ADD);
        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Arrays.asList(rootAtBase, moduleMissingAtBase))
                .thenReturn(Arrays.asList(rootDeletedAtHead, moduleAtHead));
        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(printStream(ROOT_POM_DEPOT, "<root/>"))
                .thenReturn(printStream(MODULE_POM_DEPOT, "<new/>"));

        // when
        Map<String, FileContentPair> contents = analyzer.loadFileContents(p4Context,
                new HashSet<>(Arrays.asList(ROOT_POM, MODULE_POM)), "100", false);

        // then - only existing revisions are printed; the absent sides are null
        assertEquals(new FileContentPair("<root/>\n", null), contents.get(ROOT_POM));
        assertEquals(new FileContentPair(null, "<new/>\n"), contents.get(MODULE_POM));
        ArgumentCaptor<String[]> argv = ArgumentCaptor.forClass(String[].class);
        verify(server, times(2)).execStreamCmd(eq("print"), argv.capture());
        assertEquals(Collections.singletonList(ROOT_POM_DEPOT + "@100"), Arrays.asList(argv.getAllValues().get(0)));
        assertEquals(Collections.singletonList(MODULE_POM_DEPOT + "@103"), Arrays.asList(argv.getAllValues().get(1)));
    }

    @Test
    void unmappedPathAndOverriddenViewLinesAreIgnored() throws P4JavaException {
        // given - the root POM resolves (alongside an overridden view line with no depot path);
        // the module POM has no mapping in the client view
        mockWhere(whereSpec(null, WORKSPACE_ROOT + "/pom.xml"),
                whereSpec(ROOT_POM_DEPOT, WORKSPACE_ROOT + "/pom.xml"));
        IFileSpec rootAtBase = filesSpec(ROOT_POM_DEPOT, FileAction.EDIT);
        IFileSpec rootAtHead = filesSpec(ROOT_POM_DEPOT, FileAction.EDIT);
        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Collections.singletonList(rootAtBase))
                .thenReturn(Collections.singletonList(rootAtHead));
        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(printStream(ROOT_POM_DEPOT, "<a/>"))
                .thenReturn(printStream(ROOT_POM_DEPOT, "<b/>"));

        // when
        Map<String, FileContentPair> contents = analyzer.loadFileContents(p4Context,
                new HashSet<>(Arrays.asList(ROOT_POM, MODULE_POM)), "100", false);

        // then
        assertEquals(new FileContentPair("<a/>\n", "<b/>\n"), contents.get(ROOT_POM));
        assertEquals(new FileContentPair(null, null), contents.get(MODULE_POM));
    }

    @Test
    void windowsLocalPathsResolveAgainstTheNormalisedRoot() throws P4JavaException {
        // given - a Windows client root and a backslash where-result
        when(client.getRoot()).thenReturn("C:\\p4\\ws");
        mockWhere(whereSpec(MODULE_POM_DEPOT, "C:\\p4\\ws\\module-a\\pom.xml"));
        IFileSpec moduleAtBase = filesSpec(MODULE_POM_DEPOT, FileAction.EDIT);
        IFileSpec moduleAtHead = filesSpec(MODULE_POM_DEPOT, FileAction.EDIT);
        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Collections.singletonList(moduleAtBase))
                .thenReturn(Collections.singletonList(moduleAtHead));
        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(printStream(MODULE_POM_DEPOT, "<v1/>"))
                .thenReturn(printStream(MODULE_POM_DEPOT, "<v2/>"));

        // when
        Map<String, FileContentPair> contents = analyzer.loadFileContents(p4Context,
                Collections.singleton(MODULE_POM), "100", false);

        // then
        assertEquals(new FileContentPair("<v1/>\n", "<v2/>\n"), contents.get(MODULE_POM));
    }

    @Test
    void localMode_printsHaveAndReadsTheWorkingCopy(@TempDir File workspace) throws Exception {
        // given - the workspace root is a temp dir; the module POM is edited on disk, the root
        // POM is removed from disk, and a new POM exists only on disk (not yet in the depot)
        String root = workspace.getAbsolutePath().replace('\\', '/');
        when(client.getRoot()).thenReturn(root);
        writeFile(workspace, MODULE_POM, "<local/>");
        writeFile(workspace, "module-b/pom.xml", "<b/>");
        mockWhere(whereSpec(ROOT_POM_DEPOT, root + "/pom.xml"),
                whereSpec(MODULE_POM_DEPOT, root + "/module-a/pom.xml"),
                whereSpec("//stream/main/module-b/pom.xml", root + "/module-b/pom.xml"));
        IFileSpec rootAtHead = filesSpec(ROOT_POM_DEPOT, FileAction.EDIT);
        IFileSpec moduleAtHead = filesSpec(MODULE_POM_DEPOT, FileAction.EDIT);
        IFileSpec newModuleMissing = filesSpec(null, null);
        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Arrays.asList(rootAtHead, moduleAtHead, newModuleMissing));
        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(printStream(ROOT_POM_DEPOT, "<root/>", MODULE_POM_DEPOT, "<head/>"));

        // when
        Map<String, FileContentPair> contents = analyzer.loadFileContents(p4Context,
                new HashSet<>(Arrays.asList(ROOT_POM, MODULE_POM, "module-b/pom.xml")), "100", true);

        // then - "before" printed at the have revision (one print only), "after" read from disk
        assertEquals(new FileContentPair("<root/>\n", null), contents.get(ROOT_POM));
        assertEquals(new FileContentPair("<head/>\n", "<local/>"), contents.get(MODULE_POM));
        assertEquals(new FileContentPair(null, "<b/>"), contents.get("module-b/pom.xml"));
        ArgumentCaptor<String[]> argv = ArgumentCaptor.forClass(String[].class);
        verify(server, times(1)).execStreamCmd(eq("print"), argv.capture());
        assertTrue(Arrays.stream(argv.getValue()).allMatch(arg -> arg.endsWith("#have")));
    }

    @Test
    void sideWithNoExistingFilesSkipsThePrint() throws P4JavaException {
        // given - the only path does not exist at the base CL, but does at head
        mockWhere(whereSpec(MODULE_POM_DEPOT, WORKSPACE_ROOT + "/module-a/pom.xml"));
        IFileSpec missingAtBase = filesSpec(null, null);
        IFileSpec moduleAtHead = filesSpec(MODULE_POM_DEPOT, FileAction.ADD);
        when(server.getDepotFiles(any(), anyBoolean()))
                .thenReturn(Collections.singletonList(missingAtBase))
                .thenReturn(Collections.singletonList(moduleAtHead));
        when(server.execStreamCmd(eq("print"), any(String[].class)))
                .thenReturn(printStream(MODULE_POM_DEPOT, "<new/>"));

        // when
        Map<String, FileContentPair> contents = analyzer.loadFileContents(p4Context,
                Collections.singleton(MODULE_POM), "100", false);

        // then - only the head side is printed
        assertEquals(new FileContentPair(null, "<new/>\n"), contents.get(MODULE_POM));
        verify(server, times(1)).execStreamCmd(eq("print"), any(String[].class));
    }

    @Test
    void emptyPathSetMakesNoServerCalls() throws P4JavaException {
        // given - no paths

        // when
        Map<String, FileContentPair> contents = analyzer.loadFileContents(p4Context,
                Collections.emptySet(), "100", false);

        // then
        assertTrue(contents.isEmpty());
        verify(client, never()).where(any());
        verify(server, never()).getDepotFiles(any(), anyBoolean());
    }

    /**
     * Stub the {@code p4 where} call to return the given resolution specs.
     *
     * @param whereSpecs the where-result specs.
     * @throws P4JavaException never - mock setup only.
     */
    private void mockWhere(IFileSpec... whereSpecs) throws P4JavaException {
        when(client.where(any())).thenReturn(Arrays.asList(whereSpecs));
    }

    /**
     * Build a stubbed {@code p4 where} resolution spec mapping a depot path to a local path.
     *
     * @param depotPath the depot path, or null for an overridden view line.
     * @param localPath the resolved local workspace path.
     * @return the stubbed file spec.
     */
    private IFileSpec whereSpec(String depotPath, String localPath) {
        IFileSpec spec = mock(IFileSpec.class);
        when(spec.getDepotPathString()).thenReturn(depotPath);
        when(spec.getLocalPathString()).thenReturn(localPath);
        return spec;
    }

    /**
     * Build a stubbed {@code p4 files} result spec.
     *
     * @param depotPath the depot path, or null when the file does not exist at the queried CL.
     * @param action the head action at the queried CL.
     * @return the stubbed file spec.
     */
    private IFileSpec filesSpec(String depotPath, FileAction action) {
        IFileSpec spec = mock(IFileSpec.class);
        when(spec.getDepotPathString()).thenReturn(depotPath);
        when(spec.getAction()).thenReturn(action);
        return spec;
    }

    /**
     * Build a {@code p4 print}-shaped stream for alternating depot path / content arguments.
     * Each file gets a {@code //depot/path#1 - edit change 1 (text)} header followed by its
     * content and a newline.
     *
     * @param depotPathsAndContents depot path, content, depot path, content, ...
     * @return the stream of the assembled print output.
     */
    private static ByteArrayInputStream printStream(String... depotPathsAndContents) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depotPathsAndContents.length; i += 2) {
            b.append(depotPathsAndContents[i]).append("#1 - edit change 1 (text)\n");
            b.append(depotPathsAndContents[i + 1]).append('\n');
        }
        return new ByteArrayInputStream(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Write a file under the workspace directory, creating parent directories.
     *
     * @param workspace the workspace root directory.
     * @param relativePath the workspace-relative path.
     * @param content the content to write.
     * @throws Exception if the write fails.
     */
    private static void writeFile(File workspace, String relativePath, String content) throws Exception {
        File file = new File(workspace, relativePath);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
