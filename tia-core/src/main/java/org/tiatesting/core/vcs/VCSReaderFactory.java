package org.tiatesting.core.vcs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Constructs a {@link VCSReader} from the {@link VCSReaderProvider}s visible on a class loader.
 * The build plugins call it first over their own class loader (a provider the user declared as a
 * plugin dependency) and, when that finds nothing, over an isolated class loader holding the
 * provider module they resolved for the name {@link VcsDetector} returned. Neither build plugin
 * depends on a VCS module directly, so a project only ever downloads the VCS library it uses.
 */
public final class VCSReaderFactory {

    private static final Logger log = LoggerFactory.getLogger(VCSReaderFactory.class);

    /** Maven group id of the VCS provider modules. */
    public static final String PROVIDER_GROUP_ID = "org.tiatesting";

    private static final String PROVIDER_ARTIFACT_ID_PREFIX = "tia-vcs-";

    /**
     * Static utility; not instantiable.
     */
    private VCSReaderFactory() {
    }

    /**
     * Create a reader from the providers registered on the class loader. With an explicit
     * {@code tiaVcs} the provider of that name is used; otherwise a single visible provider is
     * used as-is, and when several are visible the one {@link VcsDetector} names is chosen.
     *
     * @param settings the VCS settings from the build plugin's configuration
     * @param classLoader the class loader to look up providers on
     * @return the reader, or empty when no provider is visible on the class loader (the caller then
     * resolves one)
     * @throws VCSAnalyzerException if providers are visible but none matches the configured or
     * detected VCS
     */
    public static Optional<VCSReader> create(final VcsSettings settings, final ClassLoader classLoader) {
        return selectProvider(settings, loadProviders(classLoader)).map(provider -> {
            log.info("Using the '{}' VCS provider.", provider.name());
            return provider.create(settings);
        });
    }

    /**
     * The artifact id of the provider module for a VCS name, which the build plugins resolve at
     * {@link #PROVIDER_GROUP_ID} and their own version.
     *
     * @param vcsName a VCS name as returned by {@link VcsDetector#detect}
     * @return the artifact id, e.g. {@code tia-vcs-git}
     */
    public static String providerArtifactId(final String vcsName) {
        return PROVIDER_ARTIFACT_ID_PREFIX + vcsName;
    }

    /**
     * Pick the provider to use from those visible on the class loader.
     *
     * @param settings the VCS settings from the build plugin's configuration
     * @param providers the providers found on the class loader
     * @return the chosen provider, or empty when there are no providers
     * @throws VCSAnalyzerException if providers exist but none matches the configured or detected VCS
     */
    static Optional<VCSReaderProvider> selectProvider(final VcsSettings settings,
                                                      final List<VCSReaderProvider> providers) {
        if (providers.isEmpty()) {
            return Optional.empty();
        }

        String explicitName = VcsDetector.normalizeName(settings.getVcsName());
        if (explicitName != null) {
            return Optional.of(findByName(explicitName, providers, "tiaVcs is set to '" + explicitName + "'"));
        }

        if (providers.size() == 1) {
            return Optional.of(providers.get(0));
        }

        String detectedName = VcsDetector.detect(settings);
        return Optional.of(findByName(detectedName, providers, "the detected VCS is '" + detectedName + "'"));
    }

    /**
     * Find the provider with the given name.
     *
     * @param name the normalized provider name
     * @param providers the providers to search
     * @param reason why this name was wanted, for the error message
     * @return the matching provider
     * @throws VCSAnalyzerException if no provider has that name
     */
    private static VCSReaderProvider findByName(final String name, final List<VCSReaderProvider> providers,
                                                final String reason) {
        return providers.stream()
                .filter(provider -> provider.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new VCSAnalyzerException(reason + " but the VCS provider(s) on the plugin "
                        + "classpath are " + providerNames(providers) + ". Declare "
                        + PROVIDER_GROUP_ID + ":" + providerArtifactId(name)
                        + " as the plugin dependency instead, or correct tiaVcs."));
    }

    /**
     * Load every provider registered on the class loader.
     *
     * @param classLoader the class loader to look up providers on
     * @return the providers, in service-file order
     */
    private static List<VCSReaderProvider> loadProviders(final ClassLoader classLoader) {
        List<VCSReaderProvider> providers = new ArrayList<>();
        ServiceLoader.load(VCSReaderProvider.class, classLoader).forEach(providers::add);
        return providers;
    }

    /**
     * @param providers the providers to name
     * @return the provider names, for error messages
     */
    private static List<String> providerNames(final List<VCSReaderProvider> providers) {
        return providers.stream().map(VCSReaderProvider::name).collect(Collectors.toList());
    }
}
