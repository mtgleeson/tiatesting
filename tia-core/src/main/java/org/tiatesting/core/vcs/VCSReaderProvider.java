package org.tiatesting.core.vcs;

/**
 * Service provider interface each VCS module ({@code tia-vcs-git}, {@code tia-vcs-perforce})
 * implements and registers in {@code META-INF/services}, so the build plugins can construct a
 * {@link VCSReader} without a compile-time dependency on any VCS module. Providers are found by
 * {@link VCSReaderFactory}. Detection deliberately lives in {@link VcsDetector} rather than here:
 * it has to work before any provider has been resolved onto a classpath.
 */
public interface VCSReaderProvider {

    /**
     * The name users select this provider by (the {@code tiaVcs} setting), and the suffix of the
     * provider module's artifact id ({@code tia-vcs-<name>}).
     *
     * @return the lower-case provider name, e.g. {@code git}
     */
    String name();

    /**
     * Construct a reader for the project described by the settings.
     *
     * @param settings the VCS settings from the build plugin's configuration
     * @return a new reader; the caller owns it and must {@link VCSReader#close() close} it
     */
    VCSReader create(VcsSettings settings);
}
