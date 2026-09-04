package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the {@code tableExists} wildcard-collision bug: {@code DatabaseMetaData
 * .getTables}'s {@code schemaPattern} and {@code tableNamePattern} arguments treat {@code _} and
 * {@code %} as JDBC LIKE wildcards, and every per-branch schema name is
 * {@code tia_<sanitised-branch>} which always contains {@code _}. Before the fix, checking
 * existence in schema {@code tia_v1_2} used that name as a LIKE pattern, which also matches the
 * unrelated sibling schema {@code tia_v1x2} (the {@code _} wildcard-expands to match the literal
 * {@code x}). On a DB shared by several per-branch schemas this let a fresh, still-empty branch
 * schema be reported as already migrated because a sibling's tables satisfied the pattern match,
 * so the real {@code CREATE TABLE} DDL for the fresh branch was skipped and the next statement
 * failed against a schema with no tables. See the per-branch schema WIKI chapter and
 * {@link org.tiatesting.core.persistence.dialect.H2Dialect#tableExists}.
 */
class TableExistsWildcardCollisionTest {

    private static final String BRANCH_SEEDED = "v1x2";
    private static final String BRANCH_FRESH = "v1_2";
    private static final Set<String> SUITES_FRESH = Collections.singleton("fresh_only");

    /**
     * Verifies the sanitised schema names for the two branches used by this test actually collide
     * under {@code _} wildcard expansion, i.e. that this test exercises the bug it targets rather
     * than two schema names that happen not to overlap.
     */
    @Test
    void branchSchemaNamesCollideUnderWildcardExpansion() {
        // given the two branch names used by the isolation test below
        // when their schema names are derived
        String seededSchema = BranchSchema.schemaName(BRANCH_SEEDED, null);
        String freshSchema = BranchSchema.schemaName(BRANCH_FRESH, null);
        // then they are the expected literal names, and freshSchema (used as a LIKE pattern, since
        // '_' is a single-character wildcard) matches seededSchema: "tia_v1_2" as a pattern matches
        // the literal "tia_v1x2"
        assertEquals("tia_v1x2", seededSchema);
        assertEquals("tia_v1_2", freshSchema);
        assertTrue(freshSchema.length() == seededSchema.length(),
                "the two schema names must be the same length for a plain '_' wildcard match");
    }

    /**
     * Verifies a fresh branch schema is not fooled into thinking it is already migrated by a
     * sibling schema whose name collides under {@code _} wildcard expansion. Two H2 stores are
     * built against the same {@code @TempDir} database: one on branch {@value #BRANCH_SEEDED}
     * (schema {@code tia_v1x2}) that is fully seeded first so its Tia tables exist, and one on
     * branch {@value #BRANCH_FRESH} (schema {@code tia_v1_2}) that starts fresh. Before the fix,
     * the fresh store's {@code checkTiaDBExists}/{@code ensureSchema} check would false-positive
     * on the seeded sibling's tables (matched via the wildcard pattern) and skip creating its own
     * tables, so the very next statement against the fresh, table-less schema would throw. With
     * the fix, the fresh store bootstraps its own schema correctly and reads back its own (empty)
     * data without error.
     *
     * @param dir a fresh temp directory shared as the embedded H2 database location for both stores
     */
    @Test
    void freshBranchSchemaIsNotFooledBySeededSiblingUnderWildcardCollision(@TempDir Path dir) {
        // given a seeded store on branch "v1x2" (schema tia_v1x2) whose Tia tables already exist
        DataStore seededStore = DataStoreFactory.fromConfig(dir.toString(), null, "tia", "", null, BRANCH_SEEDED, null);
        try {
            seededStore.getTiaData(true);
            seededStore.persistTestSuitesFailed(new HashSet<>(Collections.singleton("seeded_only")));
        } finally {
            seededStore.close();
        }

        // when a fresh store on branch "v1_2" (schema tia_v1_2, which as a LIKE pattern matches the
        // literal schema tia_v1x2 above) bootstraps against the same physical database
        DataStore freshStore = DataStoreFactory.fromConfig(dir.toString(), null, "tia", "", null, BRANCH_FRESH, null);
        try {
            freshStore.getTiaData(true);
            freshStore.persistTestSuitesFailed(new HashSet<>(SUITES_FRESH));

            // then the fresh store was not fooled into believing it was already migrated by the
            // seeded sibling's tables: it created its own tables and reads back only its own data
            assertEquals(SUITES_FRESH, freshStore.getTestSuitesFailed(),
                    "fresh branch tia_v1_2 must see only its own data, not the seeded sibling's");
        } finally {
            freshStore.close();
        }
    }
}
