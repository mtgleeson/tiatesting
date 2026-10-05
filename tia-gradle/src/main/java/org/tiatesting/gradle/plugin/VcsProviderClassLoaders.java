package org.tiatesting.gradle.plugin;

import org.gradle.api.logging.Logging;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the isolated class loaders the resolved {@code tiaVcs} provider jars are loaded in, for one
 * build. Shared by every project in the build, so the same jars get one class loader, and closed by
 * Gradle when the build finishes - a class loader cached anywhere longer-lived would leak its jars
 * for the life of the Gradle daemon.
 *
 * <p>Each loader is parent-first with the Tia plugin's class loader as parent, so the provider sees
 * the same {@code tia-core} types the plugin looks it up by.
 */
public abstract class VcsProviderClassLoaders implements BuildService<BuildServiceParameters.None>, AutoCloseable {

    /** Name the service is registered under. */
    static final String NAME = "tiaVcsProviderClassLoaders";

    private static final Logger LOGGER = Logging.getLogger(VcsProviderClassLoaders.class);

    private final Map<List<Object>, URLClassLoader> loaders = new ConcurrentHashMap<>();

    /**
     * Get the class loader over the given jars, creating it on first use.
     *
     * @param files the resolved provider jars
     * @param parent the parent class loader (the Tia plugin's)
     * @return a class loader over the jars
     */
    public ClassLoader get(final List<File> files, final ClassLoader parent) {
        List<Object> key = Arrays.asList(parent, new ArrayList<>(files));
        return loaders.computeIfAbsent(key, k -> new URLClassLoader(toUrls(files), parent));
    }

    /**
     * Close every class loader this build created.
     */
    @Override
    public void close() {
        for (URLClassLoader loader : loaders.values()) {
            try {
                loader.close();
            } catch (IOException e) {
                LOGGER.debug("Tia could not close a VCS provider class loader: {}", e.getMessage());
            }
        }
        loaders.clear();
    }

    /**
     * @param files the jars
     * @return their URLs
     * @throws IllegalStateException if a path cannot be converted to a URL
     */
    private static URL[] toUrls(final List<File> files) {
        URL[] urls = new URL[files.size()];
        for (int i = 0; i < files.size(); i++) {
            try {
                urls[i] = files.get(i).toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Invalid VCS provider jar path: " + files.get(i), e);
            }
        }
        return urls;
    }
}
