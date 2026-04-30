package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.JavaForkOptions;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.slf4j.Logger;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.gradle.plugin.LibraryJarResolver;
import org.tiatesting.gradle.plugin.TiaBaseTaskExtension;
import org.tiatesting.spock.library.LibraryMetadataSystemProperties;
import org.tiatesting.spock.library.PreResolvedLibraryMetadataReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class TiaSpockGitGradlePluginTestExtension {
    private static final Logger LOGGER = Logging.getLogger(TiaSpockGitGradlePluginTestExtension.class);

    public TiaSpockGitGradlePluginTestExtension(){
    }

    public <T extends Test & JavaForkOptions> void applyTo(final T task) {
        String taskName = task.getName();
        LOGGER.debug("Applying Tia to " + taskName);
        TiaBaseTaskExtension tiaProjectExtension = task.getProject().getExtensions().findByType(TiaBaseTaskExtension.class);
        TiaBaseTaskExtension tiaTaskExtension = task.getExtensions().create("tia", TiaBaseTaskExtension.class);
        JacocoTaskExtension jacocoTaskExtension = task.getExtensions().findByType(JacocoTaskExtension.class);

        Action<Task> action = new Action<Task>() {
            @Override
            public void execute(Task task) {
                Test testTask = (Test)task;
                populateTestTaskExtension(tiaProjectExtension, tiaTaskExtension);
                boolean isTiaEnabled = isEnabled(tiaTaskExtension, testTask);

                if (isTiaEnabled){
                    // set the system properties needed by Tia passed in as configuration from the Gradle plugin
                    testTask.systemProperty("tiaEnabled", true);
                    testTask.systemProperty("tiaUpdateDBMapping", tiaTaskExtension.getUpdateDBMapping());
                    testTask.systemProperty("tiaUpdateDBStats", tiaTaskExtension.getUpdateDBStats());
                    testTask.systemProperty("tiaProjectDir", tiaTaskExtension.getProjectDir());
                    testTask.systemProperty("tiaClassFilesDirs", tiaTaskExtension.getClassFilesDirs());
                    testTask.systemProperty("tiaSourceFilesDirs", tiaTaskExtension.getSourceFilesDirs());
                    testTask.systemProperty("tiaTestFilesDirs", tiaTaskExtension.getTestFilesDirs());
                    testTask.systemProperty("tiaDBFilePath", tiaTaskExtension.getDbFilePath());
                    testTask.systemProperty("tiaCheckLocalChanges", tiaTaskExtension.getCheckLocalChanges());

                    LibraryJarResolver resolver = new LibraryJarResolver(testTask.getProject(), LOGGER);
                    String libraryJarsCsv = resolver.resolveLibraryJarsCsv(
                            tiaTaskExtension.getSourceLibs(),
                            tiaTaskExtension.getSourceProjectDir());
                    if (libraryJarsCsv != null && !libraryJarsCsv.isEmpty()){
                        testTask.systemProperty("tiaLibraryJars", libraryJarsCsv);
                    }

                    forwardLibraryMetadata(testTask, tiaTaskExtension, resolver);

                    // only apply and configure the jacoco task extension if we're updating the tia DB
                    if (tiaTaskExtension.getUpdateDBMapping()) {
                        LOGGER.debug("Enabling Jacoco in TCP server mode");
                        jacocoTaskExtension.setEnabled(true);
                        jacocoTaskExtension.setOutput(JacocoTaskExtension.Output.TCP_SERVER);
                    }
                }else{
                    testTask.systemProperty("tiaEnabled", false);
                }
            }
        };

        task.doFirst(action);
    }

    /**
     * Override the task extension object properties with the project object extension.
     *
     * This allows the user to define the Tia configuration at the project level, and override it for each test task
     * configuration type like 'test' and 'integrationTest'.
     *
     * @param tiaProjectExt
     * @param tiaTaskExt
     */
    private void populateTestTaskExtension(TiaBaseTaskExtension tiaProjectExt, TiaBaseTaskExtension tiaTaskExt){
        if (tiaTaskExt.getEnabled() == null){
            tiaTaskExt.setEnabled(tiaProjectExt.getEnabled());
        }

        if (tiaTaskExt.getUpdateDBMapping() == null){
            tiaTaskExt.setUpdateDBMapping(tiaProjectExt.getUpdateDBMapping());
        }

        if (tiaTaskExt.getUpdateDBStats() == null){
            tiaTaskExt.setUpdateDBStats(tiaProjectExt.getUpdateDBStats());
        }

        if (tiaTaskExt.getProjectDir() == null){
            tiaTaskExt.setProjectDir(tiaProjectExt.getProjectDir());
        }

        if (tiaTaskExt.getClassFilesDirs() == null){
            tiaTaskExt.setClassFilesDirs(tiaProjectExt.getClassFilesDirs());
        }

        if (tiaTaskExt.getSourceFilesDirs() == null){
            tiaTaskExt.setSourceFilesDirs(tiaProjectExt.getSourceFilesDirs());
        }

        if (tiaTaskExt.getTestFilesDirs() == null){
            tiaTaskExt.setTestFilesDirs(tiaProjectExt.getTestFilesDirs());
        }

        if (tiaTaskExt.getDbFilePath() == null){
            tiaTaskExt.setDbFilePath(tiaProjectExt.getDbFilePath());
        }

        if (tiaTaskExt.getCheckLocalChanges() == null){
            tiaTaskExt.setCheckLocalChanges(tiaProjectExt.getCheckLocalChanges());
        }

        if (tiaTaskExt.getSourceLibs() == null){
            tiaTaskExt.setSourceLibs(tiaProjectExt.getSourceLibs());
        }

        if (tiaTaskExt.getSourceProjectDir() == null){
            tiaTaskExt.setSourceProjectDir(tiaProjectExt.getSourceProjectDir());
        }
    }

    /**
     * Check if Tia is enabled. Used to determine if we should load the Tia agent and analyse the
     * changes and @Ignore tests not impacted by the changes.
     *
     * Note: It's not ideal we need to cast to the DefaultTestFilter as it's the internals of Gradle and
     * could change in future versions. Another way of getting the command line --tests parameter is using the
     * @Option(option = "tests", description = "Sets test class or method name to be included, '*' is supported.")
     * https://github.com/gradle/gradle/blob/b131fefc8d9efb8e154abd09f7eb91c854df1310/subprojects/testing-base/src/main/java/org/gradle/api/tasks/testing/AbstractTestTask.java#L104
     * annotation. But again this is currently only intended for the internals of Gradle.
     * i.e. Gradle doesn't currently provide a good way to publicly expose the command line parameters.
     *
     * @param tiaTaskExtension
     * @param task
     * @return
     */
    private boolean isEnabled(final TiaBaseTaskExtension tiaTaskExtension, Test task){
        boolean enabled = tiaTaskExtension.getEnabled() != null ? tiaTaskExtension.getEnabled() : false;
        boolean updateDBMapping = tiaTaskExtension.getUpdateDBMapping() != null ? tiaTaskExtension.getUpdateDBMapping() : false;
        boolean updateDBStats = tiaTaskExtension.getUpdateDBStats() != null ? tiaTaskExtension.getUpdateDBStats() : false;
        LOGGER.warn("Tia plugin task ext: enabled: " + enabled + ", update mapping: " + updateDBMapping
                + ", update stats: " + updateDBStats);

        /**
         * If the user specified specific individual tests to run, disable Tia so those tests are run
         * and guaranteed to be the only tests to run.
         */
        if (enabled){
            Set<String> userSpecifiedTests = ((DefaultTestFilter)task.getFilter()).getCommandLineIncludePatterns();
            boolean hasUserSpecifiedTests = userSpecifiedTests != null && !userSpecifiedTests.isEmpty();

            if (hasUserSpecifiedTests){
                LOGGER.info("Users has specified tests, disabling Tia");
                enabled = false;
            }
        }

        return enabled;
    }

    /**
     * Pre-resolve library metadata on the Gradle side and forward it to the test JVM as flat
     * system properties. The test JVM uses {@link LibraryMetadataSystemProperties} to rebuild a
     * {@code LibraryImpactAnalysisConfig} so {@code TestSelector} can run reconcile / partition /
     * stamp / drain in-process — without needing a Gradle {@code Project} reference.
     *
     * <p>Why pre-resolve here: {@link LibraryJarResolver} requires either the current Gradle
     * {@code Project} or a Tooling-API connection. Neither is available inside the forked test JVM.
     * The plugin runs the resolver once at task-action time and forwards the results.
     *
     * <p>The {@code tiaLibraryJars} CSV (set above) is a separate concern — it feeds JaCoCo so
     * library classes are included in coverage. The metadata forwarded here drives TIA's selection
     * logic.
     */
    private void forwardLibraryMetadata(Test testTask, TiaBaseTaskExtension tiaTaskExtension,
                                        LibraryJarResolver resolver) {
        String sourceLibs = tiaTaskExtension.getSourceLibs();
        if (sourceLibs == null || sourceLibs.trim().isEmpty()) {
            return;
        }

        List<CoordinateAndDir> parsed = parseSourceLibs(sourceLibs);
        if (parsed.isEmpty()) {
            return;
        }

        String sourceProjectDir = tiaTaskExtension.getSourceProjectDir();
        List<PreResolvedLibraryMetadataReader.Entry> entries = new ArrayList<>(parsed.size());

        for (CoordinateAndDir cd : parsed) {
            List<String> coordSingleton = Collections.singletonList(cd.coordinate);

            String declaredVersion = null;
            List<String> sourceDirs = Collections.emptyList();
            if (cd.projectDir != null && !cd.projectDir.isEmpty()) {
                List<LibraryBuildMetadata> metadata = resolver.readLibraryBuildMetadata(cd.projectDir, coordSingleton);
                if (!metadata.isEmpty()) {
                    declaredVersion = metadata.get(0).getDeclaredVersion();
                }
                sourceDirs = resolver.readSourceDirectories(cd.projectDir);
            }

            String resolvedVersion = null;
            String resolvedJar = null;
            List<ResolvedSourceProjectLibrary> resolved =
                    resolver.resolveLibrariesInSourceProject(sourceProjectDir, coordSingleton);
            if (!resolved.isEmpty()) {
                resolvedVersion = resolved.get(0).getResolvedVersion();
                resolvedJar = resolved.get(0).getJarFilePath();
            }

            entries.add(new PreResolvedLibraryMetadataReader.Entry(
                    cd.coordinate, cd.projectDir, declaredVersion, sourceDirs, resolvedVersion, resolvedJar));
        }

        String encoded = LibraryMetadataSystemProperties.formatEntries(entries);
        if (!encoded.isEmpty()) {
            testTask.systemProperty(LibraryMetadataSystemProperties.PROP_LIBRARIES_METADATA, encoded);
        }
        if (sourceProjectDir != null && !sourceProjectDir.isEmpty()) {
            testTask.systemProperty(LibraryMetadataSystemProperties.PROP_SOURCE_PROJECT_DIR, sourceProjectDir);
        }
        String policy = tiaTaskExtension.getLibraryVersionPolicy();
        if (policy != null && !policy.isEmpty()) {
            testTask.systemProperty(LibraryMetadataSystemProperties.PROP_LIBRARY_VERSION_POLICY, policy);
        }
    }

    /**
     * Parse the user-facing {@code sourceLibs} CSV into {@code (coordinate, projectDir)} pairs.
     * Accepts both {@code groupId:artifactId} and {@code groupId:artifactId:projectDir} forms,
     * matching {@link org.tiatesting.gradle.plugin.TiaBasePlugin#buildLibraryImpactAnalysisConfig()}.
     */
    private List<CoordinateAndDir> parseSourceLibs(String sourceLibs) {
        List<CoordinateAndDir> result = new ArrayList<>();
        for (String raw : sourceLibs.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] segments = entry.split(":");
            if (segments.length == 3) {
                result.add(new CoordinateAndDir(
                        segments[0].trim() + ":" + segments[1].trim(),
                        segments[2].trim()));
            } else if (segments.length == 2) {
                result.add(new CoordinateAndDir(entry, null));
            } else {
                LOGGER.warn("Invalid sourceLibs entry '{}' — expected groupId:artifactId or "
                        + "groupId:artifactId:projectDir, skipping.", entry);
            }
        }
        return result;
    }

    private static final class CoordinateAndDir {
        final String coordinate;
        final String projectDir;

        CoordinateAndDir(String coordinate, String projectDir) {
            this.coordinate = coordinate;
            this.projectDir = projectDir;
        }
    }
}
