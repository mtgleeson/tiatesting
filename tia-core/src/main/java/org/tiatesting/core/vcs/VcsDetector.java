package org.tiatesting.core.vcs;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Works out which VCS a project uses, using only the configuration and the file system, so the
 * build plugins can decide which provider module ({@code tia-vcs-<name>}) to resolve before any
 * VCS library is on a classpath. In order:
 * <ol>
 *     <li>an explicit {@code tiaVcs} setting wins (validated against {@link #KNOWN_VCS_NAMES});</li>
 *     <li>a configured server URI means Perforce - it is a Perforce-only setting, so it is a
 *     deliberate signal and outranks a {@code .git} that may belong to an enclosing repository;</li>
 *     <li>a {@code .git} entry in the project directory or any parent means Git.</li>
 * </ol>
 * Perforce is never guessed from the environment (P4CONFIG / P4PORT); such users set
 * {@code tiaVcs} explicitly.
 */
public final class VcsDetector {

    /** Provider name for Git. */
    public static final String GIT = "git";

    /** Provider name for Perforce. */
    public static final String PERFORCE = "perforce";

    /** Every VCS name Tia ships a provider module for. */
    public static final List<String> KNOWN_VCS_NAMES = Collections.unmodifiableList(Arrays.asList(GIT, PERFORCE));

    private static final String GIT_DIR_NAME = ".git";

    /**
     * Static utility; not instantiable.
     */
    private VcsDetector() {
    }

    /**
     * Detect the VCS name for the project described by the settings.
     *
     * @param settings the VCS settings from the build plugin's configuration
     * @return one of {@link #KNOWN_VCS_NAMES}
     * @throws VCSAnalyzerException if an explicit name is unknown, or nothing identifies the VCS
     */
    public static String detect(final VcsSettings settings) {
        String explicitName = normalizeName(settings.getVcsName());
        if (explicitName != null) {
            if (!KNOWN_VCS_NAMES.contains(explicitName)) {
                throw new VCSAnalyzerException("Unknown VCS '" + settings.getVcsName()
                        + "' configured in tiaVcs. Supported values: " + KNOWN_VCS_NAMES);
            }
            return explicitName;
        }

        if (!isBlank(settings.getServerUri())) {
            return PERFORCE;
        }

        if (settings.getProjectDir() != null && hasGitEntry(settings.getProjectDir())) {
            return GIT;
        }

        throw new VCSAnalyzerException("Could not detect the version control system for project dir '"
                + settings.getProjectDir() + "': no " + GIT_DIR_NAME + " found in it or any parent directory, "
                + "and tiaVcsServerUri is not set. Set tiaVcs (Gradle: tia { vcs = ... }) to one of "
                + KNOWN_VCS_NAMES + ".");
    }

    /**
     * Normalize a configured VCS name for comparison: trimmed and lower-cased, with a blank value
     * treated as not configured.
     *
     * @param name the configured name, possibly {@code null} or blank
     * @return the normalized name, or {@code null} when none was configured
     */
    static String normalizeName(final String name) {
        if (isBlank(name)) {
            return null;
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Check whether the directory or any of its parents contains a {@code .git} entry. A file
     * counts as well as a directory, so worktrees and submodules (where {@code .git} is a pointer
     * file) are still identified as Git.
     *
     * @param projectDir the directory to start from
     * @return {@code true} if a {@code .git} entry was found
     */
    private static boolean hasGitEntry(final String projectDir) {
        File currentDir;
        try {
            currentDir = new File(projectDir).getCanonicalFile();
        } catch (IOException e) {
            throw new VCSAnalyzerException("Failed to resolve project path: " + projectDir, e);
        }

        while (currentDir != null) {
            if (new File(currentDir, GIT_DIR_NAME).exists()) {
                return true;
            }
            currentDir = currentDir.getParentFile();
        }
        return false;
    }

    /**
     * @param value the value to check
     * @return {@code true} if the value is {@code null} or only whitespace
     */
    private static boolean isBlank(final String value) {
        return value == null || value.trim().isEmpty();
    }
}
