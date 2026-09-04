package org.tiatesting.core.persistence;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Derives the schema name Tia isolates a datastore into, from the VCS branch and an optional
 * caller-declared suffix.
 *
 * <p>The branch is the base of the name and always has been. The suffix is the second dimension:
 * two test tasks in one project that share a datastore corrupt each other - each sees only its own
 * source set, so each deletes the other's tracked suites, and they share the one {@code tia_core}
 * row and therefore the one stored commit value. Declaring a suffix per test task gives each its
 * own schema, its own suite table and its own commit stamp.
 *
 * <p>The suffix is declared by the user rather than derived by Tia. A derived name is a function of
 * the build configuration, so it moves when the configuration moves - adding a second test task
 * would silently relocate the first one's schema and orphan its mapping, with nothing failing.
 */
public final class BranchSchema {

    private static final int MAX_IDENTIFIER_LENGTH = 63; // Postgres identifier limit
    private static final String PREFIX = "tia_";

    private BranchSchema() { }

    /**
     * Derive the schema name for a branch and an optional suffix: {@code tia_} + the branch, and
     * when a suffix is given {@code _} + the suffix, each lowercased with every character outside
     * {@code [a-z0-9_]} replaced by {@code _}, clamped to the 63-character identifier limit. The
     * {@code tia_} prefix guarantees a valid identifier and namespaces Tia's objects.
     *
     * <p><b>With no suffix the result is byte-identical to what Tia has always produced</b>, so an
     * existing project keeps its schema and its mapping on upgrade. That is why the room reserved
     * for the suffix below is reserved only when there is a suffix to reserve it for: reserving it
     * unconditionally would shorten the name for any branch long enough to hit the limit, silently
     * relocating that project's schema and forcing a re-seed.
     *
     * <p><b>The suffix is never the part that gets clamped away.</b> The branch is truncated to
     * leave room for it, because clamping the whole concatenation from the right would drop the
     * suffix on any branch already at the limit - and two test tasks would land back in one schema,
     * silently, which is the exact bug the suffix exists to prevent. A suffix long enough to leave
     * no room for any branch at all falls back to a checksum of the pair, which is unreadable but
     * unique.
     *
     * <p>Truncating the branch means two long branches sharing a prefix resolve to one schema. That
     * is pre-existing behaviour, not something the suffix introduces, and it is unchanged here.
     * A residual collision is possible in principle between a branch that already ends in what
     * another's suffix begins with - branch {@code main_integration} with suffix {@code test}, and
     * branch {@code main} with suffix {@code integration_test}, both reaching
     * {@code tia_main_integration_test} - because every non-alphanumeric sanitises to {@code _} and
     * so no separator can be unambiguous. It is caught loudly at build-configuration time by the
     * guard that refuses two Tia-enabled test tasks resolving to one schema, rather than being
     * contorted around here.
     *
     * @param branch the VCS branch name (may be null/empty)
     * @param suffix the caller-declared schema suffix, or null/blank for none
     * @return the sanitised, prefixed, length-clamped schema name
     */
    public static String schemaName(String branch, String suffix) {
        String safeBranch = sanitise(branch);

        if (suffix == null || suffix.trim().isEmpty()) {
            return clamp(PREFIX + safeBranch);
        }

        String safeSuffix = sanitise(suffix);
        int branchBudget = MAX_IDENTIFIER_LENGTH - PREFIX.length() - 1 - safeSuffix.length();

        if (branchBudget < 1) {
            return checksumName(safeBranch, safeSuffix);
        }

        String clampedBranch = safeBranch.length() > branchBudget
                ? safeBranch.substring(0, branchBudget) : safeBranch;
        return PREFIX + clampedBranch + "_" + safeSuffix;
    }

    /**
     * Lowercase a name and replace every character outside {@code [a-z0-9_]} with {@code _}.
     *
     * @param value the raw branch or suffix; may be null
     * @return the sanitised value, or the empty string when {@code value} is null
     */
    private static String sanitise(String value) {
        return (value == null ? "" : value).toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    /**
     * Truncate a name to the identifier limit.
     *
     * @param name the candidate schema name
     * @return {@code name}, truncated to 63 characters if longer
     */
    private static String clamp(String name) {
        return name.length() > MAX_IDENTIFIER_LENGTH
                ? name.substring(0, MAX_IDENTIFIER_LENGTH) : name;
    }

    /**
     * Last-resort name for a suffix so long that no branch characters fit alongside it. Unreadable,
     * but stable for a given pair and distinct from any other pair - the only property that still
     * matters once readability is unattainable.
     *
     * @param safeBranch the sanitised branch
     * @param safeSuffix the sanitised suffix
     * @return a prefixed checksum-based schema name within the identifier limit
     */
    private static String checksumName(String safeBranch, String safeSuffix) {
        CRC32 crc = new CRC32();
        crc.update((safeBranch + " " + safeSuffix).getBytes(StandardCharsets.UTF_8));
        return clamp(PREFIX + Long.toHexString(crc.getValue()) + "_" + safeSuffix);
    }
}
