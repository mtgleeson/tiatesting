package org.tiatesting.gradle.plugin;

import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.VCSReaderProvider;
import org.tiatesting.core.vcs.VcsSettings;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A VCS provider named {@code git} for {@link TiaPluginVcsTest}. Deliberately not registered in the
 * test resources: tests register it in a service file inside a directory they add to the
 * {@code tiaVcs} configuration, so it is only found through the resolved configuration.
 */
public class StubVcsReaderProvider implements VCSReaderProvider {

    /** Branch the stub reader reports. */
    static final String BRANCH = "stub-branch";

    /**
     * @return {@code git}
     */
    @Override
    public String name() {
        return "git";
    }

    /**
     * @param settings the VCS settings
     * @return a reader reporting {@link #BRANCH} and the settings' project dir as the head commit
     */
    @Override
    public VCSReader create(final VcsSettings settings) {
        return new VCSReader() {
            @Override
            public String getBranchName() {
                return BRANCH;
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
             * No locally changed file differs from its mapped version.
             *
             * @param baseChangeNum ignored
             * @param localDiffs ignored
             * @return {@link MappingDrift#none()}
             */
            @Override
            public MappingDrift findMappingDrift(final String baseChangeNum,
                                                                     final Collection<SourceFileDiffContext> localDiffs) {
                return MappingDrift.none();
            }

            @Override
            public void close() {
            }
        };
    }
}
