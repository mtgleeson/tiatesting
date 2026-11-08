package org.tiatesting.vcs.perforce;

import com.perforce.p4java.client.IClient;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.server.IOptionsServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link P4DiffAnalyzer.ChangelistRange}: the changelists a submitted-range diff covers
 * for a head ahead of, equal to and behind the stored changelist, and that the static-rule
 * changed-paths query uses those bounds.
 */
class P4DiffAnalyzerChangelistRangeBoundsTest {

    @Test
    void headAheadOfStored_rangeStartsAfterStored() {
        // given
        String stored = "1005";
        String head = "1010";

        // when
        P4DiffAnalyzer.ChangelistRange range = P4DiffAnalyzer.ChangelistRange.between(stored, head);

        // then
        assertEquals(1006, range.getFrom());
        assertEquals(1010, range.getTo());
        assertFalse(range.isReversed());
        assertEquals("1006,1010", range.toRevisionRange());
    }

    @Test
    void headBehindStored_rangeStartsAfterHeadAndIsReversed() {
        // given
        String stored = "1010";
        String head = "1005";

        // when
        P4DiffAnalyzer.ChangelistRange range = P4DiffAnalyzer.ChangelistRange.between(stored, head);

        // then
        assertEquals(1006, range.getFrom());
        assertEquals(1010, range.getTo());
        assertTrue(range.isReversed());
        assertEquals("1006,1010", range.toRevisionRange());
    }

    @Test
    void headEqualToStored_noRange() {
        // given
        String stored = "1010";

        // when
        P4DiffAnalyzer.ChangelistRange range = P4DiffAnalyzer.ChangelistRange.between(stored, stored);

        // then
        assertNull(range);
    }

    @Test
    void changedFilePaths_headBehindStored_queriesTheChangelistsBetweenThem() throws P4JavaException {
        // given
        P4Connection p4Connection = mock(P4Connection.class);
        IOptionsServer server = mock(IOptionsServer.class);
        IClient client = mock(IClient.class);
        when(p4Connection.getServer()).thenReturn(server);
        when(p4Connection.getClient()).thenReturn(client);
        when(client.getName()).thenReturn("testclient");
        when(server.getDepotFiles(any(), eq(true))).thenReturn(new ArrayList<>());
        P4Context p4Context = new P4Context(p4Connection, "apps-main", "1005");

        // when
        new P4DiffAnalyzer().getChangedFilePaths(p4Context, "1010", false);

        // then
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<IFileSpec>> query = ArgumentCaptor.forClass((Class) List.class);
        verify(server).getDepotFiles(query.capture(), eq(true));
        assertEquals("//testclient/...@1006,1010", query.getValue().get(0).getAnnotatedPreferredPathString());
    }
}
