package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.persistence.connection.ConnectionProvider;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the {@code tia_distributed_run_method_stage} staging operations against embedded H2:
 * writing one runner's observed method trackers, unioning several runners' writes for the same
 * run, and clearing a run's staged rows once the sealer has consumed them. The table itself is
 * created by the distributed-run schema bootstrap; this class exercises its read/write/delete
 * operations.
 */
class JdbcDataStoreMethodStagingTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with nothing staged.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-method-stage-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData(true);
    }

    /**
     * Close the data store so its embedded H2 database releases its file lock.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
    }

    /**
     * Verify that staging a runner's method trackers and reading them back for the same run id
     * reproduces every tracker's name and line range exactly, proving the write and read agree on
     * every column.
     */
    @Test
    void shouldRoundTripStagedTrackers() {
        // given
        Map<Integer, MethodImpactTracker> staged = new HashMap<>();
        staged.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        staged.put(102, new MethodImpactTracker("com/example/B.bar.()V", 30, 45));

        // when
        dataStore.persistStagedMethodTrackers("run-1", staged);
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-1");

        // then
        assertEquals(2, read.size());
        assertEquals("com/example/A.foo.()V", read.get(101).getMethodName());
        assertEquals(10, read.get(101).getLineNumberStart());
        assertEquals(20, read.get(101).getLineNumberEnd());
        assertEquals(45, read.get(102).getLineNumberEnd());
    }

    /**
     * Verify that two runners staging disjoint method ids under the same run id both survive: the
     * read returns the union of both writes, not just the most recent one. This is the property
     * the sealer depends on to rebuild the full catalogue from every runner's partial view, so the
     * assertion checks the full merged key set rather than just the size.
     */
    @Test
    void shouldUnionTrackersStagedBySeveralRunners() {
        // given
        Map<Integer, MethodImpactTracker> fromRunnerOne = new HashMap<>();
        fromRunnerOne.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> fromRunnerTwo = new HashMap<>();
        fromRunnerTwo.put(202, new MethodImpactTracker("com/example/C.baz.()V", 5, 9));

        // when
        dataStore.persistStagedMethodTrackers("run-1", fromRunnerOne);
        dataStore.persistStagedMethodTrackers("run-1", fromRunnerTwo);
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-1");

        // then
        assertEquals(2, read.size());
        assertTrue(read.containsKey(101));
        assertTrue(read.containsKey(202));
        assertEquals("com/example/A.foo.()V", read.get(101).getMethodName());
        assertEquals("com/example/C.baz.()V", read.get(202).getMethodName());
    }

    /**
     * Verify that staging the same method id twice with different line numbers overwrites rather
     * than fails: the read returns the second write's values. This is the test that would fail
     * with a primary-key violation if the write were a plain INSERT instead of an upsert, and a
     * real distributed run always has overlapping method ids across runners.
     */
    @Test
    void shouldLetALaterStageOverwriteTheSameMethodId() {
        // given
        Map<Integer, MethodImpactTracker> firstStage = new HashMap<>();
        firstStage.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> secondStage = new HashMap<>();
        secondStage.put(101, new MethodImpactTracker("com/example/A.foo.()V", 11, 25));

        // when
        dataStore.persistStagedMethodTrackers("run-1", firstStage);
        dataStore.persistStagedMethodTrackers("run-1", secondStage);
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-1");

        // then
        assertEquals(1, read.size());
        assertEquals(11, read.get(101).getLineNumberStart());
        assertEquals(25, read.get(101).getLineNumberEnd());
    }

    /**
     * Verify that staged trackers under two different run ids are isolated from each other: a read
     * for one run never surfaces rows staged under another, which matters because the staging
     * table has no other scoping mechanism than the run id column.
     */
    @Test
    void shouldKeepRunsIsolated() {
        // given
        Map<Integer, MethodImpactTracker> runOneStaged = new HashMap<>();
        runOneStaged.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> runTwoStaged = new HashMap<>();
        runTwoStaged.put(202, new MethodImpactTracker("com/example/C.baz.()V", 5, 9));

        // when
        dataStore.persistStagedMethodTrackers("run-1", runOneStaged);
        dataStore.persistStagedMethodTrackers("run-2", runTwoStaged);
        Map<Integer, MethodImpactTracker> readRunOne = dataStore.readStagedMethodTrackers("run-1");
        Map<Integer, MethodImpactTracker> readRunTwo = dataStore.readStagedMethodTrackers("run-2");

        // then
        assertEquals(1, readRunOne.size());
        assertTrue(readRunOne.containsKey(101));
        assertEquals(1, readRunTwo.size());
        assertTrue(readRunTwo.containsKey(202));
    }

    /**
     * Verify that reading an unknown run id returns an empty map rather than null or throwing, so
     * a caller can iterate the result unconditionally.
     */
    @Test
    void shouldReturnAnEmptyMapForAnUnknownRun() {
        // given
        // nothing has been staged for any run

        // when
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-does-not-exist");

        // then
        assertTrue(read.isEmpty());
    }

    /**
     * Verify that staging an empty map does not fail, since a runner whose group produced no
     * coverage (every suite in its group was skipped, or the group itself had no impacted methods)
     * must still complete normally rather than error out.
     */
    @Test
    void shouldPersistAnEmptyStagingMapWithoutFailing() {
        // given
        Map<Integer, MethodImpactTracker> empty = new HashMap<>();

        // when
        dataStore.persistStagedMethodTrackers("run-1", empty);
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-1");

        // then
        assertTrue(read.isEmpty());
    }

    /**
     * Verify that deleting one run's staged trackers leaves another run's staged trackers intact,
     * so the sealer's post-seal cleanup for one run can never lose a concurrently-staged peer run's
     * data.
     */
    @Test
    void shouldDeleteOnlyTheNamedRunsStagedTrackers() {
        // given
        Map<Integer, MethodImpactTracker> runOneStaged = new HashMap<>();
        runOneStaged.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> runTwoStaged = new HashMap<>();
        runTwoStaged.put(202, new MethodImpactTracker("com/example/C.baz.()V", 5, 9));
        dataStore.persistStagedMethodTrackers("run-1", runOneStaged);
        dataStore.persistStagedMethodTrackers("run-2", runTwoStaged);

        // when
        dataStore.deleteStagedMethodTrackers("run-1");

        // then
        assertTrue(dataStore.readStagedMethodTrackers("run-1").isEmpty());
        assertEquals(1, dataStore.readStagedMethodTrackers("run-2").size());
    }

    /**
     * Verify that deleting an unknown run id is a silent no-op rather than throwing, since the
     * sealer's cleanup call has no way to know in advance whether a given run ever staged anything.
     */
    @Test
    void shouldIgnoreDeletionOfAnUnknownRun() {
        // given
        // nothing has been staged for any run

        // when / then (must not throw)
        dataStore.deleteStagedMethodTrackers("run-does-not-exist");
    }

    /**
     * Verify that {@link DataStore#persistStagedMethodTrackers(String, Map)} bootstraps the schema
     * itself on a datastore that has never had {@code getTiaData} called on it. Every other test in
     * this class bootstraps via {@code setUp}'s {@code getTiaData(true)} call, which would mask a
     * datastore that forgot to call {@code ensureSchema} on its own staging write path - a brand
     * new per-branch schema has no tables at all, and staging is plausibly the first thing to touch
     * it in a distributed run.
     *
     * @throws Exception if the temp directory for the fresh store cannot be created
     */
    @Test
    void shouldStageWithoutARunHavingReadTheDatabaseFirst() throws Exception {
        // given
        File freshTempDir = File.createTempFile("tia-method-stage-fresh-", "");
        freshTempDir.delete();
        freshTempDir.mkdirs();
        JdbcDataStore freshDataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(freshTempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        try {
            Map<Integer, MethodImpactTracker> staged = new HashMap<>();
            staged.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));

            // when
            freshDataStore.persistStagedMethodTrackers("run-1", staged);
            Map<Integer, MethodImpactTracker> read = freshDataStore.readStagedMethodTrackers("run-1");

            // then
            assertEquals(1, read.size());
            assertEquals("com/example/A.foo.()V", read.get(101).getMethodName());
        } finally {
            freshDataStore.close();
        }
    }

    /**
     * Verify that {@link JdbcDataStore#persistStagedMethodTrackers(String, Map)} writes method ids
     * to the database in ascending order regardless of the order the caller's map iterates them in.
     * This is the property the deadlock fix depends on: two distributed runners staging the same
     * overlapping ids from differently-ordered maps must still take Postgres row locks in the same
     * sequence, or the two runners' upsert transactions can deadlock and abort the run. The write
     * order is captured via a recording {@link ConnectionProvider} that wraps every
     * {@link PreparedStatement} bind of the id column (parameter index 2), since reading the rows
     * back does not reliably reflect write order - a {@code SELECT} with no {@code ORDER BY} can
     * come back in primary-key order irrespective of insertion order, which would let this test pass
     * even without the fix.
     *
     * @throws Exception if the temp directory for the recording store cannot be created
     */
    @Test
    void shouldWriteMethodIdsInAscendingOrderRegardlessOfMapIterationOrder() throws Exception {
        // given
        File orderTempDir = File.createTempFile("tia-method-stage-order-", "");
        orderTempDir.delete();
        orderTempDir.mkdirs();
        RecordingConnectionProvider recordingConnectionProvider = new RecordingConnectionProvider(
                new H2ConnectionProvider(H2ConnectionSettings.embedded(orderTempDir.getAbsolutePath())));
        JdbcDataStore recordingDataStore = new JdbcDataStore(new H2Dialect(), recordingConnectionProvider,
                BranchSchema.schemaName("test", null));

        // a LinkedHashMap's iteration order is its insertion order, so populating one map ascending
        // and the other descending gives two maps with the same ids but deliberately opposite
        // natural iteration order - standing in for two runners whose HashMaps happened to bucket
        // the same ids in opposite order.
        Map<Integer, MethodImpactTracker> idsInsertedAscending = new LinkedHashMap<>();
        idsInsertedAscending.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        idsInsertedAscending.put(202, new MethodImpactTracker("com/example/B.bar.()V", 30, 45));

        Map<Integer, MethodImpactTracker> idsInsertedDescending = new LinkedHashMap<>();
        idsInsertedDescending.put(202, new MethodImpactTracker("com/example/B.bar.()V", 30, 45));
        idsInsertedDescending.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));

        try {
            // when
            recordingDataStore.persistStagedMethodTrackers("run-1", idsInsertedAscending);
            List<Integer> writeOrderForAscendingInput =
                    new ArrayList<>(recordingConnectionProvider.recordedMethodIdWriteOrder());
            recordingConnectionProvider.recordedMethodIdWriteOrder().clear();

            recordingDataStore.persistStagedMethodTrackers("run-1", idsInsertedDescending);
            List<Integer> writeOrderForDescendingInput =
                    new ArrayList<>(recordingConnectionProvider.recordedMethodIdWriteOrder());

            // then
            assertEquals(Arrays.asList(101, 202), writeOrderForAscendingInput);
            assertEquals(Arrays.asList(101, 202), writeOrderForDescendingInput);
        } finally {
            recordingDataStore.close();
        }
    }

    /**
     * {@link ConnectionProvider} wrapper used only by
     * {@link #shouldWriteMethodIdsInAscendingOrderRegardlessOfMapIterationOrder()} to make the JDBC
     * bind order of the staging upsert observable. Every connection it hands out is a dynamic proxy
     * that records the value bound to the id column (parameter index 2 of the staging upsert) on
     * every {@link PreparedStatement} it prepares, in the order those binds happen, while delegating
     * every other call straight through to a real H2 connection.
     */
    private static final class RecordingConnectionProvider implements ConnectionProvider {

        private final ConnectionProvider delegate;
        private final List<Integer> recordedMethodIdWriteOrder = new ArrayList<>();

        /**
         * Wrap a real connection provider so every connection it subsequently hands out has its
         * {@code PreparedStatement} id-column binds recorded.
         *
         * @param delegate the real connection provider to wrap
         */
        RecordingConnectionProvider(ConnectionProvider delegate) {
            this.delegate = delegate;
        }

        /**
         * Open a real connection via the delegate, then wrap it in a recording proxy so any
         * {@code PreparedStatement} it prepares has its id-column binds captured.
         *
         * @return a proxied connection that records id-column bind order
         * @throws SQLException if the delegate fails to open the underlying connection
         */
        @Override
        public Connection get() throws SQLException {
            Connection real = delegate.get();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    new ConnectionRecordingHandler(real, recordedMethodIdWriteOrder));
        }

        /**
         * Delegate to the wrapped provider's JDBC URL, unchanged.
         *
         * @return the delegate's JDBC URL
         */
        @Override
        public String jdbcUrl() {
            return delegate.jdbcUrl();
        }

        /**
         * Delegate to the wrapped provider's connection summary, unchanged.
         *
         * @return the delegate's connection summary
         */
        @Override
        public String connectionSummary() {
            return delegate.connectionSummary();
        }

        /**
         * Delegate shutdown to the wrapped provider so the embedded H2 file lock is released.
         */
        @Override
        public void close() {
            delegate.close();
        }

        /**
         * The method ids bound to the staging upsert's id parameter, in the order the binds
         * happened, across every {@code PreparedStatement} prepared by connections this provider has
         * handed out. The caller is expected to clear this between the two writes under test.
         *
         * @return the mutable list of recorded id binds, in bind order
         */
        List<Integer> recordedMethodIdWriteOrder() {
            return recordedMethodIdWriteOrder;
        }
    }

    /**
     * {@link InvocationHandler} behind {@link RecordingConnectionProvider}'s proxy connections.
     * Delegates every call to a real connection, and additionally wraps the result of
     * {@code prepareStatement} in a {@link PreparedStatementRecordingHandler} so binds on the
     * returned statement are captured too.
     */
    private static final class ConnectionRecordingHandler implements InvocationHandler {

        private final Connection delegate;
        private final List<Integer> recordedMethodIdWriteOrder;

        /**
         * Build the handler around a real connection and the shared list its prepared statements
         * should record id-column binds into.
         *
         * @param delegate the real connection to delegate every call to
         * @param recordedMethodIdWriteOrder the shared list to append id-column binds to
         */
        ConnectionRecordingHandler(Connection delegate, List<Integer> recordedMethodIdWriteOrder) {
            this.delegate = delegate;
            this.recordedMethodIdWriteOrder = recordedMethodIdWriteOrder;
        }

        /**
         * Invoke the given method on the real connection, then, if it was {@code prepareStatement},
         * wrap the resulting statement in a recording proxy before returning it.
         *
         * @param proxy the proxy instance the method was called on (unused)
         * @param method the {@link Connection} method being invoked
         * @param args the arguments passed to that method
         * @return the real connection's result, or a recording-wrapped {@link PreparedStatement}
         * @throws Throwable whatever the delegated call threw
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = invokeDelegate(delegate, method, args);
            if ("prepareStatement".equals(method.getName()) && result instanceof PreparedStatement) {
                return Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class<?>[] {PreparedStatement.class},
                        new PreparedStatementRecordingHandler((PreparedStatement) result, recordedMethodIdWriteOrder));
            }
            return result;
        }
    }

    /**
     * {@link InvocationHandler} behind the recording proxy returned for a prepared statement.
     * Records every value bound to the staging upsert's id parameter (index 2) in call order, then
     * delegates every call, recorded or not, to the real statement.
     */
    private static final class PreparedStatementRecordingHandler implements InvocationHandler {

        // The staging upsert binds columns in order (run_id, id, method_name, line_start, line_end),
        // so the id column is always parameter index 2 - see persistStagedMethodTrackers.
        private static final int METHOD_ID_PARAMETER_INDEX = 2;

        private final PreparedStatement delegate;
        private final List<Integer> recordedMethodIdWriteOrder;

        /**
         * Build the handler around a real prepared statement and the shared list its id-column
         * binds should be appended to.
         *
         * @param delegate the real prepared statement to delegate every call to
         * @param recordedMethodIdWriteOrder the shared list to append id-column binds to
         */
        PreparedStatementRecordingHandler(PreparedStatement delegate, List<Integer> recordedMethodIdWriteOrder) {
            this.delegate = delegate;
            this.recordedMethodIdWriteOrder = recordedMethodIdWriteOrder;
        }

        /**
         * Record the bound value when the call is {@code setInt} on the id parameter, then invoke
         * the real statement regardless of which method was called.
         *
         * @param proxy the proxy instance the method was called on (unused)
         * @param method the {@link PreparedStatement} method being invoked
         * @param args the arguments passed to that method
         * @return the real statement's result
         * @throws Throwable whatever the delegated call threw
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("setInt".equals(method.getName()) && args != null && args.length == 2
                    && METHOD_ID_PARAMETER_INDEX == (Integer) args[0]) {
                recordedMethodIdWriteOrder.add((Integer) args[1]);
            }
            return invokeDelegate(delegate, method, args);
        }
    }

    /**
     * Invoke a method reflectively on a delegate object, unwrapping the
     * {@link InvocationTargetException} reflection wraps checked exceptions in so callers see the
     * original exception (e.g. a real {@link SQLException}) rather than a reflection artifact.
     *
     * @param delegate the object to invoke the method on
     * @param method the method to invoke
     * @param args the arguments to invoke it with
     * @return whatever the delegate's method returns
     * @throws Throwable the exception the delegate's method threw, or the method's normal return
     */
    private static Object invokeDelegate(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
