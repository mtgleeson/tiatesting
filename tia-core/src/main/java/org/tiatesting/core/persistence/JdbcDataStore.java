package org.tiatesting.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.connection.ConnectionProvider;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.*;
import java.util.*;

/**
 * The JDBC {@link DataStore}: a facade that owns the connection lifecycle (the per-branch schema
 * selected on every connection, and {@link #close()}) and delegates each area of the schema to a
 * package-private repository sharing that connection source:
 * <ul>
 *   <li>{@link SchemaManager} - table creation</li>
 *   <li>{@link MappingReader} / {@link MappingWriter} - the suite-to-method mapping and core row
 *       reads, and the mapping writes</li>
 *   <li>{@link SealRepository} - the core row write and the seal transaction</li>
 *   <li>{@link LibraryRepository} - tracked libraries, publishes and pending stamps</li>
 *   <li>{@link TestRunHistoryRepository} - run history and its triggers</li>
 *   <li>{@link DistributedRunRepository}, {@link DistributedRunGroupRepository} and
 *       {@link DistributedMethodStagingRepository} - distributed runs</li>
 * </ul>
 * The only logic kept here is {@link #getTiaData()}, which assembles a full load from several
 * repositories.
 */
public class JdbcDataStore implements DataStore {
    private final Logger log = LoggerFactory.getLogger(JdbcDataStore.class);
    private final SqlDialect dialect;
    private final ConnectionProvider connectionProvider;
    private final String schema;
    // Memoizes the per-branch schema's CREATE SCHEMA IF NOT EXISTS: once this instance has
    // created it, later connections only need the (cheap) SET SCHEMA / search_path selection.
    private boolean branchSchemaCreated;
    private final SchemaManager schemaManager;
    private final TestRunHistoryRepository testRunHistoryRepository;
    private final LibraryRepository libraryRepository;
    private final DistributedRunRepository distributedRunRepository;
    private final DistributedRunGroupRepository distributedRunGroupRepository;
    private final DistributedMethodStagingRepository distributedMethodStagingRepository;
    private final MappingReader mappingReader;
    private final MappingWriter mappingWriter;
    private final SealRepository sealRepository;

    /**
     * Construct a datastore over a vendor-neutral SQL dialect and connection provider. The dialect
     * supplies the vendor-varying SQL (upserts, identity DDL, table-existence lookup, and the
     * per-branch schema create/select statements) and the provider owns connection acquisition and
     * lifecycle (URL building, server-mode retry, and the close/shutdown behaviour). Every
     * connection this instance hands out is pinned to {@code schema} - see the per-branch schema
     * WIKI chapter.
     *
     * @param dialect the SQL dialect that renders vendor-specific statements
     * @param connectionProvider the connection provider that opens and closes connections
     * @param schema the per-branch schema to create (if needed) and select on every connection;
     *               {@code null}/blank leaves the connection's default schema untouched
     */
    public JdbcDataStore(SqlDialect dialect, ConnectionProvider connectionProvider, String schema){
        this.dialect = dialect;
        this.connectionProvider = connectionProvider;
        this.schema = schema;
        this.schemaManager = new SchemaManager(dialect, this::getConnection, connectionProvider.jdbcUrl());
        this.testRunHistoryRepository = new TestRunHistoryRepository(dialect, this::getConnection, schemaManager);
        this.libraryRepository = new LibraryRepository(dialect, this::getConnection, schemaManager);
        this.distributedRunRepository = new DistributedRunRepository(dialect, this::getConnection, schemaManager);
        this.distributedRunGroupRepository = new DistributedRunGroupRepository(dialect, this::getConnection, schemaManager);
        this.distributedMethodStagingRepository = new DistributedMethodStagingRepository(dialect, this::getConnection, schemaManager);
        this.mappingReader = new MappingReader(dialect, this::getConnection, schemaManager);
        this.mappingWriter = new MappingWriter(dialect, this::getConnection, schemaManager);
        this.sealRepository = new SealRepository(dialect, this::getConnection, schemaManager,
                mappingReader, mappingWriter, libraryRepository);

        String schemaDescription = (schema == null || schema.trim().isEmpty())
                ? "the connection's default schema"
                : "schema '" + schema + "'";
        log.info("Using {}, with {}.", connectionProvider.connectionSummary(), schemaDescription);
    }

    /**
     * Read the full Tia data from the database, bootstrapping the schema on first contact.
     *
     * @return the fully loaded Tia DB
     */
    @Override
    public TiaData getTiaData() {
        TiaData tiaData = new TiaData();

        Connection connection = getConnection();

        try {
            // Bootstrap the schema (create on first contact). A freshly created DB has no
            // mapping - return the empty TiaData.
            if (!schemaManager.ensureSchema(connection)){
                return tiaData;
            } else {
                long startQueryTime = System.currentTimeMillis();
                tiaData = mappingReader.getCoreData(connection);
                log.debug("SQL query time for core: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setTestSuitesTracked(mappingReader.getTestSuitesData(connection, true));
                log.debug("SQL query time for test suites: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setTestSuitesFailed(mappingReader.getTestSuitesFailed(connection));
                log.debug("SQL query time for failed tests: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setMethodsTracked(mappingReader.getMethodsTracked(connection));
                log.debug("SQL query time for methods tracked: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setLibrariesTracked(readTrackedLibraries());
                log.debug("SQL query time for tracked libraries: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                // Lazy: only HTML reports consume tiaData.getPendingLibraryImpactedMethods() and
                // tiaData.getPendingLibraryForcedSelections(). The select-tests selector +
                // drainer use per-library / full-table reads directly instead, so paying for a
                // full-table scan + result materialisation here on every load is wasted IO.
                // The loaders run on first getter call and cache; reports trigger them naturally.
                tiaData.setPendingLibraryImpactedMethodsLoader(this::readAllPendingLibraryImpactedMethods);
                tiaData.setPendingLibraryForcedSelectionsLoader(this::readAllPendingLibraryForcedSelections);
                tiaData.setLibraryPublishesLoader(this::readAllLibraryPublishes);
                startQueryTime = System.currentTimeMillis();
                tiaData.setTestRunHistory(readTestRunHistory());
                log.debug("SQL query time for test run history: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                return tiaData;
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#getTiaCore}.
     */
    @Override
    public TiaData getTiaCore() {
        return mappingReader.getTiaCore();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#getTestSuitesTracked}.
     */
    @Override
    public Map<String, TestSuiteTracker> getTestSuitesTracked() {
        return mappingReader.getTestSuitesTracked();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#readTestSuiteCoverage}.
     */
    @Override
    public Map<String, List<ClassImpactTracker>> readTestSuiteCoverage(final Set<String> suiteNames) {
        return mappingReader.readTestSuiteCoverage(suiteNames);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#getMethodsTracked}.
     */
    @Override
    public Map<Integer, MethodImpactTracker> getMethodsTracked() {
        return mappingReader.getMethodsTracked();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#getUniqueMethodIdsTracked}.
     */
    @Override
    public Set<Integer> getUniqueMethodIdsTracked() {
        return mappingReader.getUniqueMethodIdsTracked();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#hasUnreferencedSourceMethods}.
     */
    @Override
    public boolean hasUnreferencedSourceMethods() {
        return mappingReader.hasUnreferencedSourceMethods();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#getMethodsTrackedForFiles}.
     */
    @Override
    public Map<String, Map<Integer, MethodImpactTracker>> getMethodsTrackedForFiles(final Set<String> sourceFilenames) {
        return mappingReader.getMethodsTrackedForFiles(sourceFilenames);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#getTestSuitesForMethods}.
     */
    @Override
    public Map<Integer, Set<String>> getTestSuitesForMethods(final Set<Integer> methodIds) {
        return mappingReader.getTestSuitesForMethods(methodIds);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#getNumTestSuites}.
     */
    @Override
    public int getNumTestSuites() {
        return mappingReader.getNumTestSuites();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#getNumSourceMethods}.
     */
    @Override
    public int getNumSourceMethods() {
        return mappingReader.getNumSourceMethods();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#getTestSuitesFailed}.
     */
    @Override
    public Set<String> getTestSuitesFailed() {
        return mappingReader.getTestSuitesFailed();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link SealRepository#persistCoreData}.
     */
    @Override
    public void persistCoreData(final TiaData tiaData) {
        sealRepository.persistCoreData(tiaData);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link SealRepository#persistSealedRunData}.
     */
    @Override
    public void persistSealedRunData(final SealedRunData sealedRunData) {
        sealRepository.persistSealedRunData(sealedRunData);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingWriter#persistTestSuitesFailed}.
     */
    @Override
    public void persistTestSuitesFailed(final Set<String> suitesToClear, final Set<String> suitesFailed) {
        mappingWriter.persistTestSuitesFailed(suitesToClear, suitesFailed);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingWriter#clearUnsealedTestSuites}.
     */
    @Override
    public void clearUnsealedTestSuites() {
        mappingWriter.clearUnsealedTestSuites();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingWriter#persistSourceMethods}.
     */
    @Override
    public void persistSourceMethods(final Map<Integer, MethodImpactTracker> methodsTracked) {
        mappingWriter.persistSourceMethods(methodsTracked);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingWriter#persistTestSuites}.
     */
    @Override
    public void persistTestSuites(final Map<String, TestSuiteTracker> testSuites) {
        mappingWriter.persistTestSuites(testSuites);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingWriter#deleteTestSuites}.
     */
    @Override
    public void deleteTestSuites(final Set<String> testSuites) {
        mappingWriter.deleteTestSuites(testSuites);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readTrackedLibraries}.
     */
    @Override
    public Map<String, TrackedLibrary> readTrackedLibraries() {
        return libraryRepository.readTrackedLibraries();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#persistTrackedLibrary}.
     */
    @Override
    public void persistTrackedLibrary(final TrackedLibrary trackedLibrary) {
        libraryRepository.persistTrackedLibrary(trackedLibrary);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#deleteTrackedLibrary}.
     */
    @Override
    public void deleteTrackedLibrary(final String groupArtifact) {
        libraryRepository.deleteTrackedLibrary(groupArtifact);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readPendingLibraryImpactedMethods}.
     */
    @Override
    public List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(final String groupArtifact) {
        return libraryRepository.readPendingLibraryImpactedMethods(groupArtifact);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readAllPendingLibraryImpactedMethods}.
     */
    @Override
    public List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods() {
        return libraryRepository.readAllPendingLibraryImpactedMethods();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#persistPendingLibraryImpactedMethods}.
     */
    @Override
    public void persistPendingLibraryImpactedMethods(final PendingLibraryImpactedMethod pending) {
        libraryRepository.persistPendingLibraryImpactedMethods(pending);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#deletePendingLibraryImpactedMethods}.
     */
    @Override
    public void deletePendingLibraryImpactedMethods(final String groupArtifact, final long publishSeq) {
        libraryRepository.deletePendingLibraryImpactedMethods(groupArtifact, publishSeq);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readAllPendingLibraryForcedSelections}.
     */
    @Override
    public List<PendingLibraryForcedSelection> readAllPendingLibraryForcedSelections() {
        return libraryRepository.readAllPendingLibraryForcedSelections();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readPendingLibraryForcedSelections}.
     */
    @Override
    public List<PendingLibraryForcedSelection> readPendingLibraryForcedSelections(final String groupArtifact) {
        return libraryRepository.readPendingLibraryForcedSelections(groupArtifact);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#deletePendingLibraryForcedSelections}.
     */
    @Override
    public void deletePendingLibraryForcedSelections(final String groupArtifact, final long publishSeq) {
        libraryRepository.deletePendingLibraryForcedSelections(groupArtifact, publishSeq);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readLibraryPublishes}.
     */
    @Override
    public List<LibraryPublish> readLibraryPublishes(final String groupArtifact) {
        return libraryRepository.readLibraryPublishes(groupArtifact);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#persistLibraryPublish}.
     */
    @Override
    public long persistLibraryPublish(final LibraryPublish publish, final Set<Integer> impactedMethodIds,
                                      final List<PendingLibraryForcedSelection> forcedSelections) {
        return libraryRepository.persistLibraryPublish(publish, impactedMethodIds, forcedSelections);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readAllLibraryPublishes}.
     */
    @Override
    public List<LibraryPublish> readAllLibraryPublishes() {
        return libraryRepository.readAllLibraryPublishes();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MappingReader#getMethodsTrackedForIds}.
     */
    @Override
    public Map<Integer, MethodImpactTracker> getMethodsTrackedForIds(final Set<Integer> methodIds) {
        return mappingReader.getMethodsTrackedForIds(methodIds);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#lookupLibraryPublish}.
     */
    @Override
    public LibraryPublish lookupLibraryPublish(final String groupArtifact, final String jarHash, final String version) {
        return libraryRepository.lookupLibraryPublish(groupArtifact, jarHash, version);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link TestRunHistoryRepository#persistTestRunHistoryEntry}.
     */
    @Override
    public void persistTestRunHistoryEntry(final TestRunHistoryEntry entry) {
        testRunHistoryRepository.persistTestRunHistoryEntry(entry);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link TestRunHistoryRepository#readTestRunHistory}.
     */
    @Override
    public List<TestRunHistoryEntry> readTestRunHistory() {
        return testRunHistoryRepository.readTestRunHistory();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link TestRunHistoryRepository#persistTestRunTriggers}.
     */
    @Override
    public void persistTestRunTriggers(final String historyId, final List<TestRunTrigger> triggers) {
        testRunHistoryRepository.persistTestRunTriggers(historyId, triggers);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link TestRunHistoryRepository#readTestRunTriggers}.
     */
    @Override
    public List<TestRunTrigger> readTestRunTriggers(final String historyId) {
        return testRunHistoryRepository.readTestRunTriggers(historyId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link TestRunHistoryRepository#readTestRunTriggersByHistoryId}.
     */
    @Override
    public Map<String, List<TestRunTrigger>> readTestRunTriggersByHistoryId(final Collection<String> historyIds) {
        return testRunHistoryRepository.readTestRunTriggersByHistoryId(historyIds);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#persistDistributedRunPlan}.
     */
    @Override
    public void persistDistributedRunPlan(final DistributedRunPlan plan) {
        distributedRunRepository.persistDistributedRunPlan(plan);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#readDistributedRunDrainResult}.
     */
    @Override
    public LibraryImpactDrainResult readDistributedRunDrainResult(final String runId) {
        return distributedRunRepository.readDistributedRunDrainResult(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#readDistributedRun}.
     */
    @Override
    public DistributedRun readDistributedRun(final String runId) {
        return distributedRunRepository.readDistributedRun(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunGroupRepository#readDistributedRunGroups}.
     */
    @Override
    public List<DistributedRunGroup> readDistributedRunGroups(final String runId) {
        return distributedRunGroupRepository.readDistributedRunGroups(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunGroupRepository#readDistributedRunGroupSuites}.
     */
    @Override
    public List<String> readDistributedRunGroupSuites(final String runId, final int groupNumber) {
        return distributedRunGroupRepository.readDistributedRunGroupSuites(runId, groupNumber);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#readAllDistributedRuns}.
     */
    @Override
    public List<DistributedRun> readAllDistributedRuns() {
        return distributedRunRepository.readAllDistributedRuns();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#persistDistributedRunSelectionDetails}.
     */
    @Override
    public void persistDistributedRunSelectionDetails(final String runId, final TestRunSelectionDetails details) {
        distributedRunRepository.persistDistributedRunSelectionDetails(runId, details);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#readDistributedRunSelectionDetails}.
     */
    @Override
    public TestRunSelectionDetails readDistributedRunSelectionDetails(final String runId) {
        return distributedRunRepository.readDistributedRunSelectionDetails(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunGroupRepository#claimNextPendingGroup}.
     */
    @Override
    public DistributedRunGroup claimNextPendingGroup(final String runId, final String runnerKey,
                                                      final long claimedAtMs) {
        return distributedRunGroupRepository.claimNextPendingGroup(runId, runnerKey, claimedAtMs);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunGroupRepository#reportGroupProgress}.
     */
    @Override
    public boolean reportGroupProgress(final String runId, final int groupNumber, final String runnerKey,
                                       final long actualDurationMs, final int suitesRan,
                                       final int suitesFailed, final int suitesObserved,
                                       final long suitesDurationMs) {
        return distributedRunGroupRepository.reportGroupProgress(runId, groupNumber, runnerKey, actualDurationMs, suitesRan, suitesFailed, suitesObserved, suitesDurationMs);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunGroupRepository#completeGroup}.
     */
    @Override
    public DistributedRunGroup completeGroup(final String runId, final int groupNumber, final String runnerKey,
                                             final long completedAtMs) {
        return distributedRunGroupRepository.completeGroup(runId, groupNumber, runnerKey, completedAtMs);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#electSealer}.
     */
    @Override
    public boolean electSealer(final String runId, final String runnerKey, final long sealedAtMs) {
        return distributedRunRepository.electSealer(runId, runnerKey, sealedAtMs);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#markDistributedRunSealed}.
     */
    @Override
    public void markDistributedRunSealed(final String runId) {
        distributedRunRepository.markDistributedRunSealed(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedMethodStagingRepository#persistStagedMethodTrackers}.
     */
    @Override
    public void persistStagedMethodTrackers(final String runId, final Map<Integer, MethodImpactTracker> methodsTracked,
                                            final Set<Integer> coveredMethodIds) {
        distributedMethodStagingRepository.persistStagedMethodTrackers(runId, methodsTracked, coveredMethodIds);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedMethodStagingRepository#readStagedMethodTrackers}.
     */
    @Override
    public Map<Integer, MethodImpactTracker> readStagedMethodTrackers(final String runId) {
        return distributedMethodStagingRepository.readStagedMethodTrackers(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedMethodStagingRepository#readStagedCoveredMethodIds}.
     */
    @Override
    public Set<Integer> readStagedCoveredMethodIds(final String runId) {
        return distributedMethodStagingRepository.readStagedCoveredMethodIds(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedMethodStagingRepository#deleteStagedMethodTrackers}.
     */
    @Override
    public void deleteStagedMethodTrackers(final String runId) {
        distributedMethodStagingRepository.deleteStagedMethodTrackers(runId);
    }

    /**
     * Release any process-level resources the datastore holds open, by delegating to the
     * {@link ConnectionProvider}'s lifecycle. For embedded H2 the provider issues a graceful
     * {@code SHUTDOWN} that flushes the MVStore write buffer to disk and releases the
     * {@code .mv.db} file lock, so a forked test JVM can open the same file; for server mode it is
     * a no-op (the shared server database must not be torn down for other clients). See the
     * connection-lifecycle handling in {@link ConnectionProvider} and the pluggable-datastore
     * WIKI chapter.
     */
    @Override
    public void close() {
        connectionProvider.close();
    }

    /**
     * Acquire a connection via the injected {@link ConnectionProvider}, then pin it to this
     * instance's per-branch {@link #schema}. The provider owns the vendor-specific acquisition
     * (URL, credentials, and the server-mode retry). On the first call, if a schema is configured,
     * it is created (idempotently) before being selected - creation is memoised via
     * {@link #branchSchemaCreated} since later connections only need re-selecting, but every
     * connection (including the first) has the schema selected so unqualified statements resolve
     * against it rather than the vendor's default schema. Any {@link SQLException} - from the
     * provider or from the create/select statements - is wrapped in the unchecked
     * {@link TiaPersistenceException} so every caller in this class keeps its previous
     * unchecked-failure behaviour. If the schema create/select statements fail after the
     * connection was already acquired, the connection is closed before the wrapped exception is
     * thrown, so a schema-setup failure never leaks an open connection.
     *
     * @return an open connection to the configured database, with the per-branch schema selected
     * @throws TiaPersistenceException wrapping any {@link SQLException} raised while acquiring the
     *         connection or creating/selecting the schema
     */
    Connection getConnection(){
        Connection connection;
        try {
            connection = connectionProvider.get();
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }

        if (schema != null && !schema.trim().isEmpty()) {
            try (Statement statement = connection.createStatement()) {
                // H2 requires the schema to exist before SET SCHEMA, so create-before-select
                // ordering is mandatory; skip the CREATE round trip once it is known to exist.
                if (!branchSchemaCreated) {
                    statement.execute(dialect.createSchemaIfNotExistsSql(schema));
                    branchSchemaCreated = true;
                }
                statement.execute(dialect.selectSchemaSql(schema));
            } catch (SQLException e) {
                try {
                    connection.close();
                } catch (SQLException closeEx) {
                    // Swallow: the original schema-setup failure is the one worth reporting.
                }
                throw new TiaPersistenceException(e);
            }
        }
        return connection;
    }

    /**
     * Expose the injected {@link ConnectionProvider} for tests that need to assert which provider the
     * {@link DataStoreFactory} wired in (for example that a postgres URL routes to the auto-creating
     * {@code PostgresConnectionProvider}). Package-private: for test use only, like {@link #getConnection()}.
     *
     * @return the connection provider this datastore was constructed with
     */
    ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }
}
