package org.tiatesting.vcs.perforce;

import com.perforce.p4java.client.IClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link P4Reader#readBranchName} derives the branch name from the stream path
 * for stream clients and falls back to the client name for classic (non-stream) clients.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P4ReaderBranchNameTest {

    private static final String CLIENT_NAME = "jenkins-loc";

    @Mock private P4Connection p4Connection;
    @Mock private IClient client;

    @BeforeEach
    void setUp() {
        when(p4Connection.getClient()).thenReturn(client);
        when(client.getName()).thenReturn(CLIENT_NAME);
    }

    @Test
    void streamClientUsesTheFlattenedStreamPath() {
        // given
        when(client.getStream()).thenReturn("//libraries-automation/localization-ml");

        // when
        String branchName = P4Reader.readBranchName(p4Connection);

        // then
        assertEquals("libraries-automation-localization-ml", branchName);
    }

    @Test
    void classicClientWithNullStreamUsesTheClientName() {
        // given
        when(client.getStream()).thenReturn(null);

        // when
        String branchName = P4Reader.readBranchName(p4Connection);

        // then
        assertEquals(CLIENT_NAME, branchName);
    }

    @Test
    void classicClientWithEmptyStreamUsesTheClientName() {
        // given
        when(client.getStream()).thenReturn("");

        // when
        String branchName = P4Reader.readBranchName(p4Connection);

        // then
        assertEquals(CLIENT_NAME, branchName);
    }
}
