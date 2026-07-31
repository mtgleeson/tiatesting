package org.tiatesting.core.persistence;

/** Derives the per-branch schema name Tia isolates each VCS branch's mapping into. */
public final class BranchSchema {

    private static final int MAX_IDENTIFIER_LENGTH = 63; // Postgres identifier limit

    private BranchSchema() { }

    /**
     * Derive the schema name for a branch: {@code tia_} + the branch lowercased with every
     * character outside {@code [a-z0-9_]} replaced by {@code _}, clamped to 63 characters.
     * The {@code tia_} prefix guarantees a valid identifier and namespaces Tia's objects.
     *
     * @param branch the VCS branch name (may be null/empty)
     * @return the sanitised, prefixed, length-clamped schema name
     */
    public static String schemaName(String branch) {
        String safe = (branch == null ? "" : branch).toLowerCase().replaceAll("[^a-z0-9_]", "_");
        String name = "tia_" + safe;
        return name.length() > MAX_IDENTIFIER_LENGTH ? name.substring(0, MAX_IDENTIFIER_LENGTH) : name;
    }
}
