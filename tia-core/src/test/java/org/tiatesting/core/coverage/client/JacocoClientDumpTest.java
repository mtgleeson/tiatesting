package org.tiatesting.core.coverage.client;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.runtime.IRemoteCommandVisitor;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link JacocoClient#dumpExecutionData(Socket)} against a fake JaCoCo agent: a local
 * server socket that speaks the agent's remote control protocol through JaCoCo's own
 * {@link RemoteControlReader} and {@link RemoteControlWriter}.
 */
class JacocoClientDumpTest {

    private ServerSocket server;

    /**
     * Closes the fake agent's server socket after each test.
     *
     * @throws IOException if the server socket cannot be closed
     */
    @AfterEach
    void closeServer() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /**
     * Every class the agent dumps is read back with its id, name and probe values intact, across a
     * dump large enough to span many buffer fills.
     *
     * @throws Exception if the fake agent or the socket fails
     */
    @Test
    void dumpReadsEveryClassTheAgentSends() throws Exception {
        // given
        List<ExecutionData> sent = executionData(2000, 120);
        CompletableFuture<boolean[]> command = startAgent(sent, true);

        // when
        ExecutionDataStore received;
        try (Socket socket = connect()) {
            received = new JacocoClient().dumpExecutionData(socket);
        }

        // then
        assertEquals(sent.size(), received.getContents().size());
        for (ExecutionData expected : sent) {
            ExecutionData actual = received.get(expected.getId());
            assertNotNull(actual, "missing class " + expected.getName());
            assertEquals(expected.getName(), actual.getName());
            assertArrayEquals(expected.getProbes(), actual.getProbes());
        }
        assertArrayEquals(new boolean[]{true, true}, command.get(5, TimeUnit.SECONDS),
                "the client should ask the agent to dump and reset");
    }

    /**
     * An agent that drops the connection before acknowledging the dump makes the read fail rather
     * than return partial data as if it were complete.
     *
     * @throws Exception if the fake agent or the socket fails
     */
    @Test
    void dumpFailsWhenTheAgentClosesBeforeAcknowledging() throws Exception {
        // given
        startAgent(executionData(10, 8), false);

        // when
        IOException error;
        try (Socket socket = connect()) {
            error = assertThrows(IOException.class, () -> new JacocoClient().dumpExecutionData(socket));
        }

        // then
        assertTrue(error.getMessage().contains("closed unexpectedly"), error.getMessage());
    }

    /**
     * Builds synthetic execution data: classes with distinct ids and names, and every third probe hit.
     *
     * @param classCount the number of classes
     * @param probeCount the number of probes per class
     * @return the execution data, one entry per class
     */
    private List<ExecutionData> executionData(int classCount, int probeCount) {
        List<ExecutionData> data = new ArrayList<>();
        for (int i = 0; i < classCount; i++) {
            boolean[] probes = new boolean[probeCount];
            for (int p = 0; p < probeCount; p += 3) {
                probes[p] = true;
            }
            data.add(new ExecutionData(0x5EED0000L + i, "com/example/Sample" + i, probes));
        }
        return data;
    }

    /**
     * Starts a fake agent on an ephemeral local port that accepts one connection, waits for a dump
     * command and answers it with the given execution data, written through a buffered stream as the
     * JaCoCo 0.8.15 agent does.
     *
     * @param data the execution data to dump
     * @param acknowledge whether to send the command-ok block after the data; when false the agent
     *                    closes the connection in its place
     * @return the dump command's {@code [dump, reset]} flags, completed once the command is read
     * @throws IOException if the server socket cannot be opened
     */
    private CompletableFuture<boolean[]> startAgent(List<ExecutionData> data, boolean acknowledge)
            throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        CompletableFuture<boolean[]> command = new CompletableFuture<>();
        Thread agent = new Thread(() -> {
            try (Socket socket = server.accept()) {
                BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
                RemoteControlWriter writer = new RemoteControlWriter(out);
                RemoteControlReader reader = new RemoteControlReader(socket.getInputStream());
                reader.setRemoteCommandVisitor(new IRemoteCommandVisitor() {
                    @Override
                    public void visitDumpCommand(boolean dump, boolean reset) throws IOException {
                        command.complete(new boolean[]{dump, reset});
                        writer.visitSessionInfo(new SessionInfo("fake-agent", 1L, 2L));
                        for (ExecutionData executionData : data) {
                            writer.visitClassExecution(executionData);
                        }
                        if (acknowledge) {
                            writer.sendCmdOk();
                            out.flush();
                        } else {
                            out.flush();
                            socket.close();
                        }
                    }
                });
                reader.read();
            } catch (IOException e) {
                command.completeExceptionally(e);
            }
        }, "fake-jacoco-agent");
        agent.setDaemon(true);
        agent.start();
        return command;
    }

    /**
     * Connects a client socket to the fake agent.
     *
     * @return the connected socket
     * @throws IOException if the connection fails
     */
    private Socket connect() throws IOException {
        return new Socket(server.getInetAddress(), server.getLocalPort());
    }
}
