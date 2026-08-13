package org.tiatesting.maven;

import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSReader;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the multi-project-reactor precondition {@link AbstractTiaDistPlanMojo} adds on top of
 * {@link org.tiatesting.core.distributed.DistributedRunPreconditions#check}: {@code tia-dist-plan}
 * is not bound as a Maven aggregator, so on a reactor of more than one project it would run once
 * per project and each project's plan write would clear the previous project's plan from the
 * shared database. These tests never open a datastore - the precondition is checked, and this test
 * fails, before {@link AbstractTiaDistPlanMojo#execute()} gets that far.
 */
class AbstractTiaDistPlanMojoDistributedTest {

    /**
     * Build a bare {@link MavenProject} with the given artifact id, sufficient for {@link
     * AbstractTiaDistPlanMojo#getReactorProjects()} to name in a rejection message - no build
     * section or dependencies are needed since the precondition failure path never reads them.
     *
     * @param artifactId the artifact id the project should report
     * @return a bare Maven project with only its artifact id set
     */
    private static MavenProject projectNamed(final String artifactId) {
        Model model = new Model();
        model.setArtifactId(artifactId);
        return new MavenProject(model);
    }

    /**
     * Verify that a reactor of more than one project is refused before any datastore is opened,
     * and that the goal's own exception message names every project found in the reactor - {@code
     * tia-core} only knows the count, so the Maven goal is the layer that can name them.
     */
    @Test
    void rejectsMultiProjectReactorNamingTheProjects() {
        // given a mojo configured for an otherwise-valid distributed run, but a two-project reactor
        TestMojo mojo = new TestMojo(Arrays.asList(projectNamed("module-a"), projectNamed("module-b")));
        mojo.tiaEnabled = true;
        mojo.tiaDBUrl = "jdbc:h2:tcp://h2host:9092/tiadb";
        mojo.tiaRunId = "run-1";
        mojo.tiaDistributedGroupCount = 2;

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);

        // then - the message states the count and names both reactor projects
        String message = thrown.getMessage();
        assertTrue(message.contains("2"), "message should state the project count, was: " + message);
        assertTrue(message.contains("module-a"), "message should name module-a, was: " + message);
        assertTrue(message.contains("module-b"), "message should name module-b, was: " + message);
    }

    /**
     * Verify that a single-project build is unaffected by the reactor rule: with exactly one
     * project in the reactor, an otherwise-broken configuration still fails on the rule it would
     * have failed on before this rule existed - here, the disabled-Tia rule - not on the reactor
     * rule, and the message names no reactor projects.
     */
    @Test
    void allowsSingleProjectReactorToFallThroughToTheNextRule() {
        // given a single-project reactor with Tia disabled, otherwise a valid configuration
        TestMojo mojo = new TestMojo(Collections.singletonList(projectNamed("module-a")));
        mojo.tiaEnabled = false;
        mojo.tiaDBUrl = "jdbc:h2:tcp://h2host:9092/tiadb";
        mojo.tiaRunId = "run-1";
        mojo.tiaDistributedGroupCount = 2;

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);

        // then - the disabled-Tia rule fired, not the reactor rule, and no project names were added
        String message = thrown.getMessage();
        assertTrue(message.contains("tiaEnabled"), "message should name tiaEnabled, was: " + message);
        assertFalse(message.contains("module-a"), "single-project build should not be named: " + message);
    }

    /**
     * Concrete dist-plan mojo for the test: overrides {@link #getReactorProjects()} to return a
     * caller-chosen project list instead of reading {@link #session}, and stubs the VCS reader,
     * since neither test in this class reaches VCS-dependent selection logic - the precondition
     * failure always throws first.
     */
    private static final class TestMojo extends AbstractTiaDistPlanMojo {

        private final List<MavenProject> reactorProjects;

        /**
         * @param reactorProjects the projects {@link #getReactorProjects()} should report
         */
        private TestMojo(final List<MavenProject> reactorProjects) {
            this.reactorProjects = reactorProjects;
        }

        /**
         * @return this test's configured reactor project list, standing in for {@link #session}'s
         *         projects so the test does not need to construct a real {@code MavenSession}
         */
        @Override
        protected List<MavenProject> getReactorProjects() {
            return reactorProjects;
        }

        /**
         * @return a stub VCS reader; never called, since every test in this class hits the
         *         precondition failure before selection would read it
         */
        @Override
        public VCSReader getVCSReader() {
            return new UnreachableVCSReader();
        }
    }

    /**
     * VCS reader whose methods all fail loudly if called, so a test that unexpectedly reaches
     * VCS-dependent code fails with a clear cause instead of a confusing downstream error.
     */
    private static final class UnreachableVCSReader implements VCSReader {

        /**
         * @return never returns
         */
        @Override
        public String getBranchName() {
            throw new UnsupportedOperationException("should not be reached: precondition must fail first");
        }

        /**
         * @return never returns
         */
        @Override
        public String getHeadCommit() {
            throw new UnsupportedOperationException("should not be reached: precondition must fail first");
        }

        /**
         * @param baseChangeNum ignored
         * @param sourceFilesDirs ignored
         * @param testFilesDirs ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum,
                                                        final List<String> sourceFilesDirs,
                                                        final List<String> testFilesDirs,
                                                        final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("should not be reached: precondition must fail first");
        }

        /**
         * @param diffs ignored
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         */
        @Override
        public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs,
                                         final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("should not be reached: precondition must fail first");
        }

        /**
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("should not be reached: precondition must fail first");
        }

        /**
         * No resources to release.
         */
        @Override
        public void close() {
        }
    }
}
