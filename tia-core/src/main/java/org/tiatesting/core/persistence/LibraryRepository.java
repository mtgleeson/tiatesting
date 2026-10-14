package org.tiatesting.core.persistence;

import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.dialect.SqlDialect;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

import static org.tiatesting.core.persistence.JdbcRows.*;
import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Reads and writes the tracked-library tables: the tracked libraries themselves, their publish
 * ledger, and the pending impacted-method and forced-selection stamps each publish leaves for the
 * next mapping run to drain. See the library publish-time stamping chapter in {@code WIKI.md}.
 */
class LibraryRepository extends JdbcRepository {

    /**
     * Construct the repository over the datastore's shared JDBC plumbing.
     *
     * @param dialect the SQL dialect rendering vendor-specific statements
     * @param connections opens a connection with the per-branch schema selected
     * @param schemaManager bootstraps the schema before first use
     */
    LibraryRepository(SqlDialect dialect, Supplier<Connection> connections, SchemaManager schemaManager) {
        super(dialect, connections, schemaManager);
    }

    /**
     * Read every tracked library. Implements {@link DataStore#readTrackedLibraries}.
     *
     * @return the tracked libraries keyed by group:artifact; empty if none are tracked
     */
    Map<String, TrackedLibrary> readTrackedLibraries() {
        Map<String, TrackedLibrary> libraries = new HashMap<>();
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);
            String sql = "SELECT * FROM " + TABLE_TIA_LIBRARY;
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql);

            while (resultSet.next()) {
                TrackedLibrary lib = new TrackedLibrary();
                lib.setGroupArtifact(resultSet.getString(COL_GROUP_ARTIFACT));
                lib.setProjectDir(resultSet.getString(COL_PROJECT_DIR));
                lib.setSourceDirsCsv(resultSet.getString(COL_SOURCE_DIRS_CSV));
                lib.setMappingBaselineCommit(resultSet.getString(COL_MAPPING_BASELINE_COMMIT));
                long lastAppliedSeq = resultSet.getLong(COL_LAST_APPLIED_SEQ);
                lib.setLastAppliedSeq(resultSet.wasNull() ? null : lastAppliedSeq);
                libraries.put(lib.getGroupArtifact(), lib);
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

        return libraries;
    }

    /**
     * Upsert one tracked library over its own connection. Implements {@link DataStore#persistTrackedLibrary}.
     *
     * @param trackedLibrary the library to write
     */
    void persistTrackedLibrary(final TrackedLibrary trackedLibrary) {
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);
            persistTrackedLibrary(connection, trackedLibrary);
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
     * Upsert one tracked library row on a caller-supplied connection, so the write can join the
     * seal transaction rather than committing on its own.
     *
     * @param connection the connection to write on
     * @param trackedLibrary the tracked library to persist
     * @throws SQLException if the upsert fails
     */
    void persistTrackedLibrary(Connection connection, TrackedLibrary trackedLibrary) throws SQLException {
        String sql = dialect.upsert(TABLE_TIA_LIBRARY,
                Arrays.asList(COL_GROUP_ARTIFACT, COL_PROJECT_DIR, COL_SOURCE_DIRS_CSV,
                        COL_MAPPING_BASELINE_COMMIT, COL_LAST_APPLIED_SEQ),
                Collections.singletonList(COL_GROUP_ARTIFACT));

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, trackedLibrary.getGroupArtifact());
            ps.setString(2, trackedLibrary.getProjectDir());
            ps.setString(3, trackedLibrary.getSourceDirsCsv());
            ps.setString(4, trackedLibrary.getMappingBaselineCommit());
            if (trackedLibrary.getLastAppliedSeq() != null) {
                ps.setLong(5, trackedLibrary.getLastAppliedSeq());
            } else {
                ps.setNull(5, Types.BIGINT);
            }
            ps.executeUpdate();
        }
        log.debug("Persisted tracked library: {}", trackedLibrary.getGroupArtifact());
    }

    /**
     * Delete a tracked library; its publishes and pending stamps cascade with it. Implements {@link DataStore#deleteTrackedLibrary}.
     *
     * @param groupArtifact the library's group:artifact key
     */
    void deleteTrackedLibrary(final String groupArtifact) {
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);
            String sql = "DELETE FROM " + TABLE_TIA_LIBRARY + " WHERE " + COL_GROUP_ARTIFACT + " = ?";
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, groupArtifact);
            ps.executeUpdate();
            log.debug("Deleted tracked library: {}", groupArtifact);
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
     * Read one library's pending impacted-method stamps. Implements {@link DataStore#readPendingLibraryImpactedMethods}.
     *
     * @param groupArtifact the library's group:artifact key
     * @return the library's pending batches; empty if none
     */
    List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(final String groupArtifact) {
        List<PendingLibraryImpactedMethod> result = new ArrayList<>();
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);
            String sql = "SELECT * FROM " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD
                    + " WHERE " + COL_GROUP_ARTIFACT + " = ?";
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, groupArtifact);
            result = buildPendingBatchesFromResultSet(ps.executeQuery());
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        return result;
    }

    /**
     * Read every library's pending impacted-method stamps. Implements {@link DataStore#readAllPendingLibraryImpactedMethods}.
     *
     * @return all pending batches; empty if none
     */
    List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods() {
        List<PendingLibraryImpactedMethod> result = new ArrayList<>();
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);
            String sql = "SELECT * FROM " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD;
            Statement statement = connection.createStatement();
            result = buildPendingBatchesFromResultSet(statement.executeQuery(sql));
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        return result;
    }

    /**
     * Write one pending impacted-method batch. Implements {@link DataStore#persistPendingLibraryImpactedMethods}.
     *
     * @param pending the batch to write
     */
    void persistPendingLibraryImpactedMethods(final PendingLibraryImpactedMethod pending) {
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);

            if (!pending.getSourceMethodIds().isEmpty()) {
                String insertSql = dialect.upsert(TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD,
                        Arrays.asList(COL_GROUP_ARTIFACT, COL_STAMP_VERSION, COL_PUBLISH_SEQ,
                                COL_TIA_SOURCE_METHOD_ID),
                        Arrays.asList(COL_GROUP_ARTIFACT, COL_PUBLISH_SEQ, COL_TIA_SOURCE_METHOD_ID));
                PreparedStatement insertPs = connection.prepareStatement(insertSql);

                for (Integer methodId : pending.getSourceMethodIds()) {
                    insertPs.setString(1, pending.getGroupArtifact());
                    insertPs.setString(2, pending.getStampVersion());
                    insertPs.setLong(3, pending.getPublishSeq());
                    insertPs.setInt(4, methodId);
                    insertPs.addBatch();
                }
                insertPs.executeBatch();
            }

            log.debug("Persisted {} pending impacted methods for {} at seq {}",
                    pending.getSourceMethodIds().size(), pending.getGroupArtifact(), pending.getPublishSeq());
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
     * Delete one drained impacted-method batch over its own connection. Implements {@link DataStore#deletePendingLibraryImpactedMethods}.
     *
     * @param groupArtifact the library's group:artifact key
     * @param publishSeq the publish sequence of the batch to delete
     */
    void deletePendingLibraryImpactedMethods(final String groupArtifact, final long publishSeq) {
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);
            deletePendingLibraryImpactedMethods(connection, groupArtifact, publishSeq);
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
     * Delete one drained publish's pending impacted-method rows on a caller-supplied connection,
     * so the delete can join the seal transaction rather than committing on its own.
     *
     * @param connection the connection to delete on
     * @param groupArtifact the {@code groupId:artifactId} of the library
     * @param publishSeq the publish sequence whose stamp rows to delete
     * @throws SQLException if the delete fails
     */
    void deletePendingLibraryImpactedMethods(Connection connection, String groupArtifact,
                                                     long publishSeq) throws SQLException {
        String sql = "DELETE FROM " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD
                + " WHERE " + COL_GROUP_ARTIFACT + " = ? AND " + COL_PUBLISH_SEQ + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, groupArtifact);
            ps.setLong(2, publishSeq);
            ps.executeUpdate();
        }
        log.debug("Deleted pending impacted methods for {} at seq {}", groupArtifact, publishSeq);
    }

    /**
     * Read every pending forced-selection batch across all tracked libraries.
     *
     * @return the forced-selection batches; never {@code null}, may be empty.
     */
    List<PendingLibraryForcedSelection> readAllPendingLibraryForcedSelections() {
        Connection connection = getConnection();
        try {
            schemaManager.ensureSchema(connection);
            String sql = "SELECT * FROM " + TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION;
            Statement statement = connection.createStatement();
            return buildForcedBatchesFromResultSet(statement.executeQuery(sql));
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
     * Read the pending forced-selection batches for one tracked library.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @return the library's forced-selection batches; never {@code null}, may be empty.
     */
    List<PendingLibraryForcedSelection> readPendingLibraryForcedSelections(final String groupArtifact) {
        Connection connection = getConnection();
        try {
            schemaManager.ensureSchema(connection);
            String sql = "SELECT * FROM " + TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION
                    + " WHERE " + COL_GROUP_ARTIFACT + " = ?";
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, groupArtifact);
            return buildForcedBatchesFromResultSet(ps.executeQuery());
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
     * Delete the forced-selection rows of one published build after they have been drained.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @param publishSeq the publish sequence whose forced-selection rows to delete.
     */
    void deletePendingLibraryForcedSelections(final String groupArtifact, final long publishSeq) {
        Connection connection = getConnection();
        try {
            schemaManager.ensureSchema(connection);
            deletePendingLibraryForcedSelections(connection, groupArtifact, publishSeq);
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
     * Delete one drained publish's pending forced-selection rows on a caller-supplied connection,
     * so the delete can join the seal transaction rather than committing on its own.
     *
     * @param connection the connection to delete on
     * @param groupArtifact the {@code groupId:artifactId} of the library
     * @param publishSeq the publish sequence whose forced-selection rows to delete
     * @throws SQLException if the delete fails
     */
    void deletePendingLibraryForcedSelections(Connection connection, String groupArtifact,
                                                       long publishSeq) throws SQLException {
        String sql = "DELETE FROM " + TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION
                + " WHERE " + COL_GROUP_ARTIFACT + " = ? AND " + COL_PUBLISH_SEQ + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, groupArtifact);
            ps.setLong(2, publishSeq);
            ps.executeUpdate();
        }
        log.debug("Deleted pending forced selections for {} at seq {}", groupArtifact, publishSeq);
    }

    /**
     * Read one library's publish ledger. Implements {@link DataStore#readLibraryPublishes}.
     *
     * @param groupArtifact the library's group:artifact key
     * @return the library's publishes; empty if none
     */
    List<LibraryPublish> readLibraryPublishes(final String groupArtifact) {
        List<LibraryPublish> result = new ArrayList<>();
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);
            String sql = "SELECT * FROM " + TABLE_TIA_LIBRARY_PUBLISH
                    + " WHERE " + COL_GROUP_ARTIFACT + " = ? ORDER BY " + COL_PUBLISH_SEQ + " ASC";
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, groupArtifact);
            ResultSet resultSet = ps.executeQuery();
            while (resultSet.next()) {
                result.add(buildLibraryPublishFromRow(resultSet));
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

        return result;
    }

    /**
     * Persist a published library build and, atomically, the impacted-method stamp and any
     * forced-selection batches for that build. Assigns and returns the next per-library publish
     * sequence. All rows are written in one transaction so a ledger row can never exist without
     * the stamps of the build it identifies.
     *
     * @param publish the publish-ledger row to write.
     * @param impactedMethodIds the tracked source method ids impacted since the baseline; may be empty.
     * @param forcedSelections the forced-selection batches produced by the library's static rules;
     *                         may be empty.
     * @return the assigned publish sequence.
     */
    long persistLibraryPublish(final LibraryPublish publish, final Set<Integer> impactedMethodIds,
                                      final List<PendingLibraryForcedSelection> forcedSelections) {
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);
            connection.setAutoCommit(false);

            // Assign the next per-library sequence inside the insert transaction. Under a
            // concurrent publish (server mode) two writers can race to the same max; the
            // (group_artifact, publish_seq) primary key turns that into a duplicate-key failure
            // rather than a silent double-assignment.
            String maxSql = "SELECT COALESCE(MAX(" + COL_PUBLISH_SEQ + "), 0) FROM "
                    + TABLE_TIA_LIBRARY_PUBLISH + " WHERE " + COL_GROUP_ARTIFACT + " = ?";
            PreparedStatement maxPs = connection.prepareStatement(maxSql);
            maxPs.setString(1, publish.getGroupArtifact());
            ResultSet maxRs = maxPs.executeQuery();
            maxRs.next();
            long assignedSeq = maxRs.getLong(1) + 1;

            String insertSql = "INSERT INTO " + TABLE_TIA_LIBRARY_PUBLISH + " ("
                    + COL_GROUP_ARTIFACT + ", " + COL_PUBLISH_SEQ + ", " + COL_PUBLISHED_VERSION + ", "
                    + COL_JAR_HASH + ", " + COL_COMMIT_VALUE + ", " + COL_PUBLISHED_AT + ") "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
            PreparedStatement insertPs = connection.prepareStatement(insertSql);
            insertPs.setString(1, publish.getGroupArtifact());
            insertPs.setLong(2, assignedSeq);
            insertPs.setString(3, publish.getPublishedVersion());
            insertPs.setString(4, publish.getJarHash());
            insertPs.setString(5, publish.getCommitValue());
            insertPs.setLong(6, publish.getPublishedAt());
            insertPs.executeUpdate();

            // Stamp the impacted methods against the assigned sequence in the same transaction,
            // so a publish row can never exist without the stamps of the build it identifies.
            if (impactedMethodIds != null && !impactedMethodIds.isEmpty()) {
                String stampSql = "INSERT INTO " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD + " ("
                        + COL_GROUP_ARTIFACT + ", " + COL_STAMP_VERSION + ", "
                        + COL_PUBLISH_SEQ + ", "
                        + COL_TIA_SOURCE_METHOD_ID + ") "
                        + "VALUES (?, ?, ?, ?)";
                PreparedStatement stampPs = connection.prepareStatement(stampSql);
                for (Integer methodId : impactedMethodIds) {
                    stampPs.setString(1, publish.getGroupArtifact());
                    stampPs.setString(2, publish.getPublishedVersion());
                    stampPs.setLong(3, assignedSeq);
                    stampPs.setInt(4, methodId);
                    stampPs.addBatch();
                }
                stampPs.executeBatch();
            }

            // Persist the forced-selection batches against the assigned sequence in the same
            // transaction as the ledger row and the impacted-method stamp, so a publish can never
            // exist without the forced selections its own static rules produced.
            int forcedRowCount = 0;
            if (forcedSelections != null && !forcedSelections.isEmpty()) {
                String forcedSql = "INSERT INTO " + TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION + " ("
                        + COL_GROUP_ARTIFACT + ", " + COL_STAMP_VERSION + ", " + COL_PUBLISH_SEQ + ", "
                        + COL_RULE_NAME + ", " + COL_MODE + ", " + COL_SUITE_NAME_PATTERN + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?)";
                PreparedStatement forcedPs = connection.prepareStatement(forcedSql);
                for (PendingLibraryForcedSelection forced : forcedSelections) {
                    if (forced.getSuiteNamePatterns().isEmpty()) {
                        forcedPs.setString(1, publish.getGroupArtifact());
                        forcedPs.setString(2, publish.getPublishedVersion());
                        forcedPs.setLong(3, assignedSeq);
                        forcedPs.setString(4, forced.getRuleName());
                        forcedPs.setString(5, forced.getMode().name());
                        forcedPs.setNull(6, Types.VARCHAR);
                        forcedPs.addBatch();
                        forcedRowCount++;
                    } else {
                        for (String pattern : forced.getSuiteNamePatterns()) {
                            forcedPs.setString(1, publish.getGroupArtifact());
                            forcedPs.setString(2, publish.getPublishedVersion());
                            forcedPs.setLong(3, assignedSeq);
                            forcedPs.setString(4, forced.getRuleName());
                            forcedPs.setString(5, forced.getMode().name());
                            forcedPs.setString(6, pattern);
                            forcedPs.addBatch();
                            forcedRowCount++;
                        }
                    }
                }
                forcedPs.executeBatch();
            }

            connection.commit();

            publish.setPublishSeq(assignedSeq);
            log.debug("Persisted library publish {} at seq {} (version '{}', {} stamped methods, "
                            + "{} forced-selection row(s) across {} batch(es)).",
                    publish.getGroupArtifact(), assignedSeq, publish.getPublishedVersion(),
                    impactedMethodIds != null ? impactedMethodIds.size() : 0,
                    forcedRowCount, forcedSelections != null ? forcedSelections.size() : 0);
            return assignedSeq;
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
     * Read every library's publish ledger. Implements {@link DataStore#readAllLibraryPublishes}.
     *
     * @return all publishes; empty if none
     */
    List<LibraryPublish> readAllLibraryPublishes() {
        List<LibraryPublish> result = new ArrayList<>();
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);
            String sql = "SELECT * FROM " + TABLE_TIA_LIBRARY_PUBLISH
                    + " ORDER BY " + COL_GROUP_ARTIFACT + ", " + COL_PUBLISH_SEQ + " ASC";
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql);
            while (resultSet.next()) {
                result.add(buildLibraryPublishFromRow(resultSet));
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

        return result;
    }

    /**
     * Find the publish matching a resolved library jar. Implements {@link DataStore#lookupLibraryPublish}.
     *
     * @param groupArtifact the library's group:artifact key
     * @param jarHash the resolved jar's hash
     * @param version the resolved jar's version
     * @return the matching publish, or null if none matches
     */
    LibraryPublish lookupLibraryPublish(final String groupArtifact, final String jarHash, final String version) {
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);

            LibraryPublish byHash = findHighestSeqPublish(connection, groupArtifact, COL_JAR_HASH, jarHash);
            if (byHash != null) {
                return byHash;
            }
            return findHighestSeqPublish(connection, groupArtifact, COL_PUBLISHED_VERSION, version);
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
     * Find the highest-sequence publish row for a library where the given column equals the given
     * value. Matches are expected to be rare (usually one), so the highest sequence is selected in
     * Java rather than with a dialect-specific row-limit clause.
     *
     * @param connection the open H2 connection to query on
     * @param groupArtifact the library coordinate to search within
     * @param matchColumn the ledger column to match on ({@code jar_hash} or {@code published_version})
     * @param matchValue the value to match; a null value skips the match and returns null
     * @return the matching row with the highest {@code publish_seq}, or null when nothing matches
     * @throws SQLException if the query fails
     */
    private LibraryPublish findHighestSeqPublish(Connection connection, String groupArtifact,
                                                 String matchColumn, String matchValue) throws SQLException {
        if (matchValue == null) {
            return null;
        }
        String sql = "SELECT * FROM " + TABLE_TIA_LIBRARY_PUBLISH
                + " WHERE " + COL_GROUP_ARTIFACT + " = ? AND " + matchColumn + " = ?";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setString(1, groupArtifact);
        ps.setString(2, matchValue);
        ResultSet resultSet = ps.executeQuery();

        LibraryPublish best = null;
        while (resultSet.next()) {
            LibraryPublish candidate = buildLibraryPublishFromRow(resultSet);
            if (best == null || candidate.getPublishSeq() > best.getPublishSeq()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Materialise a {@link LibraryPublish} from the current row of a {@code tia_library_publish}
     * result set.
     *
     * @param resultSet the result set positioned on a ledger row
     * @return the populated publish model
     * @throws SQLException if a column read fails
     */
    private LibraryPublish buildLibraryPublishFromRow(ResultSet resultSet) throws SQLException {
        LibraryPublish publish = new LibraryPublish();
        publish.setGroupArtifact(resultSet.getString(COL_GROUP_ARTIFACT));
        publish.setPublishSeq(resultSet.getLong(COL_PUBLISH_SEQ));
        publish.setPublishedVersion(resultSet.getString(COL_PUBLISHED_VERSION));
        publish.setJarHash(resultSet.getString(COL_JAR_HASH));
        publish.setCommitValue(resultSet.getString(COL_COMMIT_VALUE));
        publish.setPublishedAt(resultSet.getLong(COL_PUBLISHED_AT));
        return publish;
    }

    /**
     * Group flat pending rows from a result set into batches keyed by
     * {@code (groupArtifact, stampVersion)}.
     *
     * <p>The reducer uses a {@link LinkedHashMap}, so it tolerates rows arriving in arbitrary
     * order - rows for the same batch are accumulated by key regardless of where they appear
     * in the result. Callers therefore do not need to apply an {@code ORDER BY} in the SQL,
     * which lets H2 skip materialising an {@code MVSortedTempResult} for these reads.
     *
     * @param resultSet the JDBC result set positioned before the first row
     * @return one {@link PendingLibraryImpactedMethod} per distinct
     *         {@code (groupArtifact, publishSeq)} pair in the result
     * @throws SQLException if the underlying result-set traversal fails
     */
    private List<PendingLibraryImpactedMethod> buildPendingBatchesFromResultSet(ResultSet resultSet) throws SQLException {
        Map<String, PendingLibraryImpactedMethod> batchMap = new LinkedHashMap<>();

        while (resultSet.next()) {
            String ga = resultSet.getString(COL_GROUP_ARTIFACT);
            long publishSeq = resultSet.getLong(COL_PUBLISH_SEQ);
            String key = ga + "|" + publishSeq;

            PendingLibraryImpactedMethod batch = batchMap.get(key);
            if (batch == null) {
                batch = new PendingLibraryImpactedMethod(ga, resultSet.getString(COL_STAMP_VERSION),
                        publishSeq, new HashSet<>());
                batchMap.put(key, batch);
            }
            batch.getSourceMethodIds().add(resultSet.getInt(COL_TIA_SOURCE_METHOD_ID));
        }

        return new ArrayList<>(batchMap.values());
    }

    /**
     * Group flat forced-selection rows into batches keyed by
     * {@code (group_artifact, publish_seq, rule_name)}, collecting each batch's non-null patterns.
     *
     * @param resultSet the result set over the forced-selection table.
     * @return one {@link PendingLibraryForcedSelection} per distinct batch key.
     * @throws SQLException if the result set cannot be read.
     */
    private List<PendingLibraryForcedSelection> buildForcedBatchesFromResultSet(ResultSet resultSet) throws SQLException {
        Map<String, PendingLibraryForcedSelection> batchMap = new LinkedHashMap<>();
        while (resultSet.next()) {
            String ga = resultSet.getString(COL_GROUP_ARTIFACT);
            long seq = resultSet.getLong(COL_PUBLISH_SEQ);
            String ruleName = resultSet.getString(COL_RULE_NAME);
            String key = ga + "|" + seq + "|" + ruleName;
            PendingLibraryForcedSelection batch = batchMap.get(key);
            if (batch == null) {
                batch = new PendingLibraryForcedSelection(ga, resultSet.getString(COL_STAMP_VERSION),
                        seq, ruleName,
                        StaticTestSelectionRuleMode.valueOf(resultSet.getString(COL_MODE)),
                        new ArrayList<String>());
                batchMap.put(key, batch);
            }
            String pattern = resultSet.getString(COL_SUITE_NAME_PATTERN);
            if (pattern != null) {
                batch.getSuiteNamePatterns().add(pattern);
            }
        }
        return new ArrayList<>(batchMap.values());
    }
}
