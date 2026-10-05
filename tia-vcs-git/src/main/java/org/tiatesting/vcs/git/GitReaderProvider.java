package org.tiatesting.vcs.git;

import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.VCSReaderProvider;
import org.tiatesting.core.vcs.VcsDetector;
import org.tiatesting.core.vcs.VcsSettings;

/**
 * Registers Git as a {@link VCSReaderProvider}, so the build plugins can construct a
 * {@link GitReader} once this module has been resolved onto a class loader.
 */
public class GitReaderProvider implements VCSReaderProvider {

    /**
     * @return {@code git}
     */
    @Override
    public String name() {
        return VcsDetector.GIT;
    }

    /**
     * Open the Git repository containing the project dir.
     *
     * @param settings the VCS settings; only the project dir is used
     * @return a reader over the repository found from the project dir upwards
     */
    @Override
    public VCSReader create(final VcsSettings settings) {
        return new GitReader(settings.getProjectDir());
    }
}
