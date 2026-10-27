package org.tiatesting.maven;

import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MissingUpstreamChanges;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.VCSReaderProvider;
import org.tiatesting.core.vcs.VcsSettings;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link VCSReaderProvider} named {@code git}, registered in the test resources'
 * {@code META-INF/services} so {@link AbstractTiaMojoVcsReaderTest} can hand the mojo a class loader
 * on which a provider is found, without a real VCS module on the test classpath.
 */
public class StubGitReaderProvider implements VCSReaderProvider {

    /** Branch name the stub reader reports, so tests can tell it apart from other readers. */
    static final String STUB_BRANCH = "stub-branch";

    /**
     * @return {@code git}
     */
    @Override
    public String name() {
        return "git";
    }

    /**
     * Create a reader reporting {@link #STUB_BRANCH} and the settings' project dir as the head commit.
     *
     * @param settings the VCS settings
     * @return a reader with no changes
     */
    @Override
    public VCSReader create(final VcsSettings settings) {
        return new VCSReader() {
            @Override
            public String getBranchName() {
                return STUB_BRANCH;
            }

            @Override
            public String getHeadCommit() {
                return settings.getProjectDir();
            }

            @Override
            public Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum, final List<String> sourceFilesDirs,
                                                           final List<String> testFilesDirs,
                                                           final boolean checkLocalChanges) {
                return Collections.emptySet();
            }

            @Override
            public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs, final String baseChangeNum,
                                            final boolean checkLocalChanges) {
            }

            @Override
            public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
                return Collections.emptySet();
            }

            /**
             * Content-by-path loading is not exercised by these tests.
             *
             * @param paths unused.
             * @param baseChangeNum unused.
             * @param checkLocalChanges unused.
             * @return never returns.
             */
            @Override
            public Map<String, FileContentPair> loadFileContents(final Set<String> paths, final String baseChangeNum,
                                                                 final boolean checkLocalChanges) {
                throw new UnsupportedOperationException("loadFileContents is not used by this test");
            }

            /**
             * No locally changed file is missing an upstream change.
             *
             * @param baseChangeNum ignored
             * @param localDiffs ignored
             * @return {@link MissingUpstreamChanges#none()}
             */
            @Override
            public MissingUpstreamChanges findMissingUpstreamChanges(final String baseChangeNum,
                                                                     final Collection<SourceFileDiffContext> localDiffs) {
                return MissingUpstreamChanges.none();
            }

            @Override
            public void close() {
            }
        };
    }
}
