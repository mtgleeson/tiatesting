package org.tiatesting.vcs.perforce;

import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.VCSReaderProvider;
import org.tiatesting.core.vcs.VcsDetector;
import org.tiatesting.core.vcs.VcsSettings;

/**
 * Registers Perforce as a {@link VCSReaderProvider}, so the build plugins can construct a
 * {@link P4Reader} once this module has been resolved onto a class loader.
 */
public class P4ReaderProvider implements VCSReaderProvider {

    /**
     * @return {@code perforce}
     */
    @Override
    public String name() {
        return VcsDetector.PERFORCE;
    }

    /**
     * Connect to the Perforce server described by the settings. No connection is made when Tia is
     * disabled.
     *
     * @param settings the VCS settings; uses the enabled flag, server URI, user, password and client
     * @return a reader over the configured Perforce client
     */
    @Override
    public VCSReader create(final VcsSettings settings) {
        return new P4Reader(settings.isEnabled(), settings.getServerUri(), settings.getUserName(),
                settings.getPassword(), settings.getClientName());
    }
}
