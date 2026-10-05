package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code tiaVcs} configuration: its default dependency is the provider for the detected
 * VCS at the plugin's version, a user-declared dependency replaces it, {@code tia-core} is excluded,
 * and {@link TiaPlugin#getVCSReader()} loads the provider from the resolved configuration.
 */
class TiaPluginVcsTest {

    @TempDir
    File projectDir;

    @Test
    void defaultDependencyIsTheGitProviderInAGitWorkspace() throws IOException {
        // given
        Files.createDirectory(new File(projectDir, ".git").toPath());
        Project project = tiaProject();
        extension(project).setProjectDir(".");

        // when
        List<String> dependencies = defaultDependencies(project);

        // then
        assertEquals(1, dependencies.size());
        assertEquals("org.tiatesting:tia-vcs-git:" + TiaVersion.get(), dependencies.get(0));
    }

    @Test
    void serverUriMakesThePerforceProviderTheDefault() {
        // given
        Project project = tiaProject();
        extension(project).setVcsServerUri("p4java://server:1666");

        // when
        List<String> dependencies = defaultDependencies(project);

        // then
        assertEquals("org.tiatesting:tia-vcs-perforce:" + TiaVersion.get(), dependencies.get(0));
    }

    @Test
    void explicitVcsSelectsItsProvider() throws IOException {
        // given - a .git directory, which the explicit setting overrides
        Files.createDirectory(new File(projectDir, ".git").toPath());
        Project project = tiaProject();
        extension(project).setVcs("perforce");

        // when
        List<String> dependencies = defaultDependencies(project);

        // then
        assertEquals("org.tiatesting:tia-vcs-perforce:" + TiaVersion.get(), dependencies.get(0));
    }

    @Test
    void userDeclaredDependencyReplacesTheDefault() {
        // given
        Project project = tiaProject();
        project.getDependencies().add(TiaPlugin.VCS_CONFIGURATION_NAME, "org.tiatesting:tia-vcs-perforce:1.0");

        // when
        List<String> dependencies = defaultDependencies(project);

        // then
        assertEquals(1, dependencies.size());
        assertEquals("org.tiatesting:tia-vcs-perforce:1.0", dependencies.get(0));
    }

    @Test
    void tiaCoreIsExcludedFromTheConfiguration() {
        // given
        Project project = tiaProject();

        // when
        Configuration configuration = project.getConfigurations().getByName(TiaPlugin.VCS_CONFIGURATION_NAME);

        // then
        boolean excluded = false;
        for (ExcludeRule rule : configuration.getExcludeRules()) {
            excluded |= "org.tiatesting".equals(rule.getGroup()) && "tia-core".equals(rule.getModule());
        }
        assertTrue(excluded, "tia-core is not excluded");
    }

    @Test
    void undetectableVcsFailsNamingTheSettings() {
        // given - no .git anywhere up the temp dir, no server URI
        Project project = tiaProject();

        // when
        Exception exception = assertThrows(Exception.class, () -> defaultDependencies(project));

        // then
        assertTrue(rootMessage(exception).contains("tiaVcs"), rootMessage(exception));
    }

    @Test
    void getVcsReaderLoadsTheProviderFromTheResolvedConfiguration() throws IOException {
        // given - a "provider jar" (a directory) registering the stub provider
        File providerDir = new File(projectDir, "provider");
        File servicesDir = new File(providerDir, "META-INF/services");
        Files.createDirectories(servicesDir.toPath());
        Files.write(new File(servicesDir, "org.tiatesting.core.vcs.VCSReaderProvider").toPath(),
                StubVcsReaderProvider.class.getName().getBytes(StandardCharsets.UTF_8));
        Project project = tiaProject();
        project.getDependencies().add(TiaPlugin.VCS_CONFIGURATION_NAME, project.files(providerDir));

        // when
        VCSReader reader = project.getPlugins().getPlugin(TiaPlugin.class).getVCSReader();

        // then
        assertEquals(StubVcsReaderProvider.BRANCH, reader.getBranchName());
        assertEquals(project.getProjectDir().getAbsolutePath(), reader.getHeadCommit());
    }

    @Test
    void configurationWithoutAProviderFailsNamingIt() throws IOException {
        // given - a directory with no service registration
        File emptyDir = new File(projectDir, "empty");
        Files.createDirectories(emptyDir.toPath());
        Project project = tiaProject();
        project.getDependencies().add(TiaPlugin.VCS_CONFIGURATION_NAME, project.files(emptyDir));

        // when
        GradleException exception = assertThrows(GradleException.class,
                () -> project.getPlugins().getPlugin(TiaPlugin.class).getVCSReader());

        // then
        assertTrue(exception.getMessage().contains(TiaPlugin.VCS_CONFIGURATION_NAME), exception.getMessage());
    }

    /**
     * @return a project rooted at the temp dir with the Tia plugin applied
     */
    private Project tiaProject() {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(TiaPlugin.class);
        return project;
    }

    /**
     * @param project the project
     * @return the project-level Tia extension
     */
    private static TiaBaseTaskExtension extension(final Project project) {
        return project.getExtensions().getByType(TiaBaseTaskExtension.class);
    }

    /**
     * Read the configuration's dependencies the way resolution sees them, which applies the default.
     *
     * @param project the project
     * @return the dependencies as {@code group:name:version}
     */
    private static List<String> defaultDependencies(final Project project) {
        return project.getConfigurations().getByName(TiaPlugin.VCS_CONFIGURATION_NAME).getIncoming()
                .getDependencies().stream()
                .map(TiaPluginVcsTest::coordinates)
                .collect(Collectors.toList());
    }

    /**
     * @param dependency a dependency
     * @return its {@code group:name:version}
     */
    private static String coordinates(final Dependency dependency) {
        return dependency.getGroup() + ":" + dependency.getName() + ":" + dependency.getVersion();
    }

    /**
     * @param throwable a failure, possibly wrapped by Gradle
     * @return the innermost cause's message
     */
    private static String rootMessage(final Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }
}
