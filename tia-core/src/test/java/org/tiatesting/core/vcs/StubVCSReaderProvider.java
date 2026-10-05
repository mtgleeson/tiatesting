package org.tiatesting.core.vcs;

import org.tiatesting.core.diff.SourceFileDiffContext;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A named {@link VCSReaderProvider} for {@link VCSReaderFactoryTest}. Registered in the test
 * resources' {@code META-INF/services} under the name {@code stub} so the factory's
 * {@code ServiceLoader} path can be exercised; tests also construct it directly with other names.
 */
public class StubVCSReaderProvider implements VCSReaderProvider {

    private final String name;

    /**
     * No-arg constructor used by {@code ServiceLoader}; names the provider {@code stub}.
     */
    public StubVCSReaderProvider() {
        this("stub");
    }

    /**
     * @param name the provider name to report
     */
    public StubVCSReaderProvider(final String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Create a reader that reports the project dir as its branch, so tests can see the settings
     * reached the provider.
     *
     * @param settings the VCS settings
     * @return a reader with no changes
     */
    @Override
    public VCSReader create(final VcsSettings settings) {
        return new VCSReader() {
            @Override
            public String getBranchName() {
                return settings.getProjectDir();
            }

            @Override
            public String getHeadCommit() {
                return name;
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

            @Override
            public void close() {
            }
        };
    }
}
