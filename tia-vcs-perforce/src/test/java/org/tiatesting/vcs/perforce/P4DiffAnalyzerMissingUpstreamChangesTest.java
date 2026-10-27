package org.tiatesting.vcs.perforce;

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
import org.tiatesting.core.vcs.MissingUpstreamChanges;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link P4DiffAnalyzer#findMissingUpstreamChanges}: one batched
 * {@code p4 files -a <depotPath>#>have,@<baseCl>} call, with the changelists returned per file
 * collected oldest first.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4DiffAnalyzerMissingUpstreamChangesTest {

    private static final String FOO_DEPOT = "//stream/main/src/main/java/com/example/Foo.java";
    private static final String BAR_DEPOT = "//stream/main/src/main/java/com/example/Bar.java";

    @Mock private P4Connection p4Connection;
    @Mock private IOptionsServer server;

    private P4DiffAnalyzer analyzer;
    private P4Context p4Context;

    /**
     * Wire the mocked connection and server, with the workspace head at CL 103.
     */
    @BeforeEach
    void setUp() {
        when(p4Connection.getServer()).thenReturn(server);
        analyzer = new P4DiffAnalyzer();
        p4Context = new P4Context(p4Connection, "main", "103");
    }

    @Test
    void reportsTheChangelistsAfterTheHaveRevisionPerFile() throws P4JavaException {
        // given - Foo has revisions in CLs 110 and 105 after its have revision; Bar has none
        when(server.execMapCmdList(eq("files"), any(String[].class), isNull())).thenReturn(Arrays.asList(
                fileRevision(FOO_DEPOT, "110"),
                fileRevision(FOO_DEPOT, "105"),
                noRevisionsEntry(BAR_DEPOT)));

        // when
        MissingUpstreamChanges missing = analyzer.findMissingUpstreamChanges(p4Context, "120",
                Arrays.asList(openedDiff(FOO_DEPOT), openedDiff(BAR_DEPOT)));

        // then - one batched query with a #>have,@120 range per file; Foo's CLs come back oldest first
        ArgumentCaptor<String[]> argv = ArgumentCaptor.forClass(String[].class);
        verify(server).execMapCmdList(eq("files"), argv.capture(), isNull());
        assertEquals(Arrays.asList("-a", FOO_DEPOT + "#>have,@120", BAR_DEPOT + "#>have,@120"),
                Arrays.asList(argv.getValue()));
        assertTrue(missing.isBaselineFound());
        assertEquals(Collections.singletonMap(FOO_DEPOT, Arrays.asList("105", "110")),
                missing.getChangesByFetchKey());
    }

    @Test
    void noLocalDiffsMakesNoServerCall() throws P4JavaException {
        // given - nothing opened

        // when
        MissingUpstreamChanges missing = analyzer.findMissingUpstreamChanges(p4Context, "120",
                Collections.emptyList());

        // then
        assertTrue(missing.getChangesByFetchKey().isEmpty());
        verify(server, never()).execMapCmdList(any(String.class), any(String[].class), any());
    }

    /**
     * @param depotPath the depot path.
     * @return a locally opened MODIFY diff keyed by depot path, as the analyzer builds it.
     */
    private static SourceFileDiffContext openedDiff(String depotPath) {
        SourceFileDiffContext diff = new SourceFileDiffContext("/ws" + depotPath, null, ChangeType.MODIFY);
        diff.setVcsFetchKey(depotPath);
        return diff;
    }

    /**
     * @param depotPath the depot path.
     * @param change the changelist of the revision.
     * @return a {@code p4 files} result map for one revision.
     */
    private static Map<String, Object> fileRevision(String depotPath, String change) {
        Map<String, Object> result = new HashMap<>();
        result.put("depotFile", depotPath);
        result.put("change", change);
        result.put("action", "edit");
        return result;
    }

    /**
     * @param depotPath the depot path.
     * @return the error entry {@code p4 files} returns for a file with no revision in the range.
     */
    private static Map<String, Object> noRevisionsEntry(String depotPath) {
        Map<String, Object> result = new HashMap<>();
        result.put("code0", "285219084");
        result.put("fmt0", depotPath + "#>have,@120 - no revision(s) after that revision.");
        return result;
    }
}
