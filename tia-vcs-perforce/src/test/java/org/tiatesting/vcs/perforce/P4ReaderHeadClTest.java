package org.tiatesting.vcs.perforce;

import com.perforce.p4java.client.IClient;
import com.perforce.p4java.core.IChangelistSummary;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.exception.RequestException;
import com.perforce.p4java.option.server.GetChangelistsOptions;
import com.perforce.p4java.server.IOptionsServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link P4Reader#readHeadCL} looks up the workspace head changelist with a
 * client-syntax {@code #have} query, so it works for virtual streams, streams with import
 * paths and classic (non-stream) clients, whose have lists hold depot paths outside the
 * stream path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4ReaderHeadClTest {

    private static final String CLIENT_NAME = "jenkins-loc";
    private static final String VIRTUAL_STREAM = "//libraries-automation/localization-ml";

    @Mock private P4Connection p4Connection;
    @Mock private IOptionsServer server;
    @Mock private IClient client;

    @BeforeEach
    void setUp() {
        when(p4Connection.getServer()).thenReturn(server);
        when(p4Connection.getClient()).thenReturn(client);
        when(client.getName()).thenReturn(CLIENT_NAME);
        when(client.getStream()).thenReturn(VIRTUAL_STREAM);
    }

    @Test
    void queriesHaveListInClientSyntax() throws P4JavaException {
        // given
        stubChangelists(Collections.singletonList(changelist(12345)));

        // when
        P4Reader.readHeadCL(p4Connection);

        // then
        String queriedPath = captureQueriedPath();
        assertEquals("//" + CLIENT_NAME + "/...#have", queriedPath);
        assertFalse(queriedPath.contains(VIRTUAL_STREAM));
    }

    @Test
    void returnsTheChangelistId() throws P4JavaException {
        // given
        stubChangelists(Collections.singletonList(changelist(12345)));

        // when
        String headCl = P4Reader.readHeadCL(p4Connection);

        // then
        assertEquals("12345", headCl);
    }

    @Test
    void classicClientWithNoStreamStillQueriesClientSyntax() throws P4JavaException {
        // given
        when(client.getStream()).thenReturn(null);
        stubChangelists(Collections.singletonList(changelist(678)));

        // when
        String headCl = P4Reader.readHeadCL(p4Connection);

        // then
        assertEquals("678", headCl);
        assertEquals("//" + CLIENT_NAME + "/...#have", captureQueriedPath());
    }

    @Test
    void emptyResultThrowsNamingTheClient() throws P4JavaException {
        // given
        stubChangelists(Collections.emptyList());

        // when
        VCSAnalyzerException thrown = assertThrows(VCSAnalyzerException.class,
                () -> P4Reader.readHeadCL(p4Connection));

        // then
        assertTrue(thrown.getMessage().contains(CLIENT_NAME));
    }

    @Test
    void p4JavaExceptionIsWrapped() throws P4JavaException {
        // given
        RequestException failure = new RequestException("server unavailable");
        when(server.getChangelists(any(), any(GetChangelistsOptions.class))).thenThrow(failure);

        // when
        VCSAnalyzerException thrown = assertThrows(VCSAnalyzerException.class,
                () -> P4Reader.readHeadCL(p4Connection));

        // then
        assertSame(failure, thrown.getCause());
    }

    /**
     * Stub the server's {@code getChangelists} call to return the given summaries.
     *
     * @param summaries the changelist summaries the server should return.
     * @throws P4JavaException never; declared by the stubbed p4java method.
     */
    private void stubChangelists(final List<IChangelistSummary> summaries) throws P4JavaException {
        when(server.getChangelists(any(), any(GetChangelistsOptions.class))).thenReturn(summaries);
    }

    /**
     * Build a mock changelist summary with the given id.
     *
     * @param id the changelist id.
     * @return a mock summary returning {@code id} from {@code getId()}.
     */
    private static IChangelistSummary changelist(final int id) {
        IChangelistSummary summary = mock(IChangelistSummary.class);
        when(summary.getId()).thenReturn(id);
        return summary;
    }

    /**
     * Capture the single file spec passed to {@code getChangelists} and return its path,
     * including the {@code #have} revision specifier.
     *
     * @return the queried path as it would be sent to the server.
     * @throws P4JavaException never; declared by the verified p4java method.
     */
    @SuppressWarnings("unchecked")
    private String captureQueriedPath() throws P4JavaException {
        ArgumentCaptor<List<IFileSpec>> specs = ArgumentCaptor.forClass(List.class);
        verify(server).getChangelists(specs.capture(), any(GetChangelistsOptions.class));
        assertEquals(1, specs.getValue().size());
        return specs.getValue().get(0).getAnnotatedPreferredPathString();
    }
}
