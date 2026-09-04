package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves which datastore schemas a project's test tasks use.
 *
 * <p>A test task's effective settings are its own merged over the project's, and that merge is
 * performed into the task's extension when the task runs - so a task that has not run does not carry
 * it yet. Anything that needs to reason across tasks therefore has to resolve the effective values
 * itself, which is what this does.
 *
 * <p>Shared rather than duplicated because two callers need the same answer for opposite reasons,
 * and two implementations of one resolution rule would drift: the collision guard asks which tasks
 * could corrupt each other, and the reporting tasks ask where there is data to show. They differ
 * only in which tasks they count - see {@link #schemaSuffixes} and {@link #taskPathsBySchema}.
 */
public final class TiaSchemaResolver {

    private TiaSchemaResolver() { }

    /**
     * The distinct schema suffixes across the project's Tia-enabled test tasks, for the reporting
     * tasks to iterate.
     *
     * <p>Counts <b>every enabled</b> test task, not only the ones that own mapping updates. A task
     * with {@code updateDBMapping} off still writes history rows to its own schema - those are the
     * local developer runs the history report exists to surface - so excluding it would make that
     * population invisible.
     *
     * <p>A task that declares no suffix contributes {@code null}, which is the unsuffixed
     * {@code tia_<branch>} schema. A project with a single test task therefore yields exactly
     * {@code [null]} and every reporting task behaves as it always has.
     *
     * @param project the project whose test tasks to inspect
     * @param projectExtension the project-level Tia extension each task's settings fall back to
     * @return the distinct suffixes in declaration order, possibly containing null; never empty -
     *         a project with no enabled test task still yields {@code [null]}, so a report run
     *         against a datastore populated by something else still has a schema to read
     */
    public static Set<String> schemaSuffixes(final Project project,
                                             final TiaBaseTaskExtension projectExtension) {
        Set<String> suffixes = new LinkedHashSet<>();

        for (Test task : project.getTasks().withType(Test.class)) {
            TiaBaseTaskExtension taskExtension = task.getExtensions().findByType(TiaBaseTaskExtension.class);
            if (taskExtension == null) {
                continue;
            }
            if (!Boolean.TRUE.equals(effective(taskExtension.getEnabled(), projectExtension.getEnabled()))) {
                continue;
            }
            suffixes.add(effective(taskExtension.getSchemaSuffix(), projectExtension.getSchemaSuffix()));
        }

        if (suffixes.isEmpty()) {
            suffixes.add(null);
        }
        return suffixes;
    }

    /**
     * The schema suffix of the project's one distributed test task.
     *
     * <p>Unambiguous by construction: a build that configures a second distributed test task is
     * refused at configuration time, because a distributed plan groups suites across the whole
     * project and a second task's group could hold suites the first cannot run. So the daemon-side
     * distributed tasks - which plan, report on and complete a run - can derive their schema rather
     * than being told it.
     *
     * @param project the project whose test tasks to inspect
     * @param projectExtension the project-level Tia extension each task's settings fall back to
     * @return the distributed test task's effective suffix, or null when there is no distributed
     *         test task or it declares none
     */
    public static String distributedSchemaSuffix(final Project project,
                                                 final TiaBaseTaskExtension projectExtension) {
        for (Test task : project.getTasks().withType(Test.class)) {
            TiaBaseTaskExtension taskExtension = task.getExtensions().findByType(TiaBaseTaskExtension.class);
            if (taskExtension == null) {
                continue;
            }
            if (Boolean.TRUE.equals(effective(taskExtension.getDistributed(), projectExtension.getDistributed()))) {
                return effective(taskExtension.getSchemaSuffix(), projectExtension.getSchemaSuffix());
            }
        }
        return null;
    }

    /**
     * The paths of the project's <b>mapping-owning</b> test tasks, grouped by the datastore and
     * schema each resolves to, for the collision guard.
     *
     * <p>Counts only tasks that own mapping updates, unlike {@link #schemaSuffixes}. A task with
     * {@code updateDBMapping} off writes no mapping and deletes no suites, so it cannot corrupt
     * anything it shares a schema with; counting it would refuse configurations that are safe.
     *
     * @param project the project whose test tasks to inspect
     * @param projectExtension the project-level Tia extension each task's settings fall back to
     * @param branch the VCS branch, the base of every schema name
     * @return task paths grouped by a store-and-schema identity, in declaration order
     */
    public static Map<String, List<String>> taskPathsBySchema(final Project project,
                                                              final TiaBaseTaskExtension projectExtension,
                                                              final String branch) {
        Map<String, List<String>> taskPathsBySchema = new LinkedHashMap<>();

        for (Test task : project.getTasks().withType(Test.class)) {
            TiaBaseTaskExtension taskExtension = task.getExtensions().findByType(TiaBaseTaskExtension.class);
            if (taskExtension == null) {
                continue;
            }
            if (!Boolean.TRUE.equals(effective(taskExtension.getEnabled(), projectExtension.getEnabled()))
                    || !Boolean.TRUE.equals(effective(taskExtension.getUpdateDBMapping(),
                            projectExtension.getUpdateDBMapping()))) {
                continue;
            }

            String schema = org.tiatesting.core.persistence.BranchSchema.schemaName(branch,
                    effective(taskExtension.getSchemaSuffix(), projectExtension.getSchemaSuffix()));
            String datastore = effective(taskExtension.getDbUrl(), projectExtension.getDbUrl());
            if (datastore == null) {
                datastore = effective(taskExtension.getDbFilePath(), projectExtension.getDbFilePath());
            }

            String storeIdentity = datastore + " :: " + schema;
            List<String> paths = taskPathsBySchema.get(storeIdentity);
            if (paths == null) {
                paths = new ArrayList<>();
                taskPathsBySchema.put(storeIdentity, paths);
            }
            paths.add(task.getPath());
        }

        return taskPathsBySchema;
    }

    /**
     * Print a heading naming the schema a following report section describes, but only when there
     * is more than one. A single-schema project's output must not grow a header it never had.
     *
     * @param schemaSuffix the suffix of the section about to be printed, or null for none
     * @param suffixCount how many schemas are being reported over in total
     */
    public static void printSchemaHeadingIfNeeded(final String schemaSuffix, final int suffixCount) {
        if (suffixCount > 1) {
            System.out.println();
            System.out.println("=== schema: " + (schemaSuffix == null ? "(none)" : schemaSuffix) + " ===");
        }
    }

    /**
     * The report folder name for a schema: the branch alone when no suffix is declared, so an
     * existing project's report tree stays exactly where it was, and {@code branch_suffix} when one
     * is.
     *
     * @param branch the VCS branch
     * @param schemaSuffix the schema suffix, or null for none
     * @return the folder name to scope a report tree by
     */
    public static String reportFolderName(final String branch, final String schemaSuffix) {
        return schemaSuffix == null || schemaSuffix.trim().isEmpty()
                ? branch : branch + "_" + schemaSuffix;
    }

    /**
     * Resolve a setting to the task's own value when it has one, falling back to the project's.
     *
     * @param taskValue the task-level value, or null when not declared on the task
     * @param projectValue the project-level value to fall back to
     * @param <T> the setting's type
     * @return the effective value, which may be null when neither declares one
     */
    public static <T> T effective(final T taskValue, final T projectValue) {
        return taskValue != null ? taskValue : projectValue;
    }
}
