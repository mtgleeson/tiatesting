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
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Tests for {@link P4DiffAnalyzer#findMappingDrift}: one batched {@code p4 files -a} call with a
 * behind range ({@code #>have,@<baseCl>}) and an ahead range ({@code @>baseCl,#have}) per file,
 * with the changelists returned per file split by side and collected oldest first.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4DiffAnalyzerMappingDriftTest {

    private static final String FOO_DEPOT = "//stream/main/src/main/java/com/example/Foo.java";
    private static final String BAR_DEPOT = "//stream/main/src/main/java/com/example/Bar.java";
    private static final String BAZ_DEPOT = "//stream/main/src/main/java/com/example/Baz.java";

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
    void reportsTheChangelistsOnEachSideOfTheStoredChangelistPerFile() throws P4JavaException {
        // given - the mapping is at CL 120. Foo is synced before CLs 105 and 110 (behind), Bar is
        // synced to CL 125 (ahead) and Baz is synced to its revision at CL 120.
        when(server.execMapCmdList(eq("files"), any(String[].class), isNull())).thenReturn(Arrays.asList(
                fileRevision(FOO_DEPOT, "110"),
                fileRevision(FOO_DEPOT, "105"),
                noRevisionsEntry(FOO_DEPOT + "@>120,#have"),
                noRevisionsEntry(BAR_DEPOT + "#>have,@120"),
                fileRevision(BAR_DEPOT, "125"),
                noRevisionsEntry(BAZ_DEPOT + "#>have,@120"),
                noRevisionsEntry(BAZ_DEPOT + "@>120,#have")));

        // when
        MappingDrift drift = analyzer.findMappingDrift(p4Context, "120",
                Arrays.asList(openedDiff(FOO_DEPOT), openedDiff(BAR_DEPOT), openedDiff(BAZ_DEPOT)));

        // then - one batched query with both ranges per file
        ArgumentCaptor<String[]> argv = ArgumentCaptor.forClass(String[].class);
        verify(server).execMapCmdList(eq("files"), argv.capture(), isNull());
        assertEquals(Arrays.asList("-a",
                FOO_DEPOT + "#>have,@120", FOO_DEPOT + "@>120,#have",
                BAR_DEPOT + "#>have,@120", BAR_DEPOT + "@>120,#have",
                BAZ_DEPOT + "#>have,@120", BAZ_DEPOT + "@>120,#have"), Arrays.asList(argv.getValue()));

        // and - Foo is behind (oldest first), Bar is ahead and Baz has no drift
        Map<String, MappingDrift.FileDrift> expected = new LinkedHashMap<>();
        expected.put(FOO_DEPOT, new MappingDrift.FileDrift(Arrays.asList("105", "110"), Collections.emptyList()));
        expected.put(BAR_DEPOT, new MappingDrift.FileDrift(Collections.emptyList(), Collections.singletonList("125")));
        assertTrue(drift.isBaselineFound());
        assertEquals(expected, drift.getDriftByFetchKey());
    }

    @Test
    void noLocalDiffsMakesNoServerCall() throws P4JavaException {
        // given - nothing opened

        // when
        MappingDrift drift = analyzer.findMappingDrift(p4Context, "120",
                Collections.emptyList());

        // then
        assertTrue(drift.getDriftByFetchKey().isEmpty());
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
     * @param fileSpec the depot path with its revision range.
     * @return the error entry {@code p4 files} returns for a range with no revisions.
     */
    private static Map<String, Object> noRevisionsEntry(String fileSpec) {
        Map<String, Object> result = new HashMap<>();
        result.put("code0", "285219084");
        result.put("fmt0", fileSpec + " - no revision(s) after that revision.");
        return result;
    }
}
