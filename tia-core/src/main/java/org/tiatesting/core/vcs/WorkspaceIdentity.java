package org.tiatesting.core.vcs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * The two values every Tia stage needs to know about the workspace it is running against: the
 * <b>branch</b>, which selects the datastore schema, and the <b>commit</b> the workspace is on.
 * Both are resolved from configuration when the build supplies them, and from the version control
 * system only when it does not.
 *
 * <p>This class exists so that a build can run with no version control system available at all. A
 * distributed test run plans on one machine and then executes on CI runners that may hold nothing
 * but a checked-out tree - no {@code .git} directory, no reachable Perforce server. Those runners
 * still need the branch to find their schema and the commit to verify they are running the plan's
 * selection against the code it was made for, and the pipeline already knows both. Supplying them
 * through {@code tiaBranch} and {@code tiaCommitValue} is what removes the version control system
 * from every stage after planning. See the "Distributed test runs" chapter in {@code WIKI.md}.
 *
 * <p><b>Resolution is lazy and per value.</b> A configured value never causes a reader to be
 * constructed, which matters because construction is not free and not local: {@code GitReader}
 * opens a JGit repository, and {@code P4Reader} opens a connection to a Perforce server in its
 * constructor. A caller that needs only the branch - the completion and status stages both do -
 * therefore touches nothing when the branch alone is configured. A value that is not configured
 * falls back to the reader, so every existing build keeps working with no new configuration.
 *
 * <p><b>One reader, closed once.</b> The reader is constructed at most once and held until {@link
 * #close()}, so a caller that also needs diffs can take the same instance through {@link
 * #openVCSReader()} rather than opening a second Perforce connection for the same build. Callers
 * should use try-with-resources.
 */
public final class WorkspaceIdentity implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceIdentity.class);

    /** Name of the property carrying the branch, used in configuration and in the fork handoff. */
    public static final String PROP_BRANCH = "tiaBranch";

    /** Name of the property carrying the commit, used in configuration and in the fork handoff. */
    public static final String PROP_COMMIT_VALUE = "tiaCommitValue";

    private final String configuredBranch;
    private final String configuredCommitValue;
    private final Supplier<VCSReader> vcsReaderSupplier;

    private VCSReader vcsReader;
    private String vcsBranch;
    private String vcsCommitValue;
    private boolean readFromVcs;
    private boolean closed;

    /**
     * Store the configured values and the means of reaching the version control system if they
     * turn out not to cover what a caller asks for.
     *
     * @param configuredBranch the configured branch, already trimmed, or null when not configured
     * @param configuredCommitValue the configured commit, already trimmed, or null when not
     *                              configured
     * @param vcsReaderSupplier constructs a reader for this workspace when one is needed
     */
    private WorkspaceIdentity(final String configuredBranch, final String configuredCommitValue,
                              final Supplier<VCSReader> vcsReaderSupplier) {
        this.configuredBranch = configuredBranch;
        this.configuredCommitValue = configuredCommitValue;
        this.vcsReaderSupplier = vcsReaderSupplier;
    }

    /**
     * Build an identity that answers from the configured values where it can and from the version
     * control system where it cannot.
     *
     * <p>The supplier is not invoked here, and is not invoked at all when every value a caller asks
     * for was configured. It is required all the same: every call site has a reader to offer even
     * when it expects never to need one, so a null supplier is a wiring mistake, and reporting it
     * at construction names it where it was made rather than on whichever getter first happens to
     * need the version control system.
     *
     * @param configuredBranch the value of {@code tiaBranch}, or null/blank when not configured
     * @param configuredCommitValue the value of {@code tiaCommitValue}, or null/blank when not
     *                              configured
     * @param vcsReaderSupplier constructs a reader for this workspace; invoked at most once, and
     *                          only when a value that was not configured is asked for
     * @return an identity that resolves each value on demand
     * @throws IllegalArgumentException if {@code vcsReaderSupplier} is null
     */
    public static WorkspaceIdentity resolving(final String configuredBranch,
                                              final String configuredCommitValue,
                                              final Supplier<VCSReader> vcsReaderSupplier) {
        if (vcsReaderSupplier == null) {
            throw new IllegalArgumentException("a workspace identity needs a VCS reader supplier to "
                    + "fall back to, even when " + PROP_BRANCH + " and " + PROP_COMMIT_VALUE
                    + " are both configured");
        }
        return new WorkspaceIdentity(trimmedOrNull(configuredBranch),
                trimmedOrNull(configuredCommitValue), vcsReaderSupplier);
    }

    /**
     * Treat a blank configured value as an absent one, and trim what is left.
     *
     * <p>Blank has to mean absent rather than "use this": an unresolved property placeholder and a
     * CI variable that was never set both arrive as an empty string, and an empty branch would
     * resolve to a schema name derived from nothing. Trimming matters for the same class of reason
     * - a value passed through a CI variable or a properties file commonly carries trailing
     * whitespace, which would otherwise produce a schema name, or a commit comparison, that differs
     * invisibly from the intended one.
     *
     * @param value the raw configured value
     * @return the trimmed value, or null when it was null or blank
     */
    private static String trimmedOrNull(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    /**
     * The branch this build is running against, and therefore the branch whose schema its datastore
     * reads and writes.
     *
     * @return the configured {@code tiaBranch} when set, otherwise the branch reported by the
     *         version control system; never null or blank
     * @throws VCSAnalyzerException if the branch was not configured and could not be read from the
     *                              version control system; the message names {@code tiaBranch} as
     *                              the way to run without one
     */
    public String getBranch() {
        if (configuredBranch != null) {
            return configuredBranch;
        }
        readIdentityFromVcs(PROP_BRANCH, "branch");
        return vcsBranch;
    }

    /**
     * The commit this build is running against.
     *
     * @return the configured {@code tiaCommitValue} when set, otherwise the head commit reported by
     *         the version control system
     * @throws VCSAnalyzerException if the commit was not configured and could not be read from the
     *                              version control system; the message names {@code tiaCommitValue}
     *                              as the way to run without one
     */
    public String getCommitValue() {
        if (configuredCommitValue != null) {
            return configuredCommitValue;
        }
        readIdentityFromVcs(PROP_COMMIT_VALUE, "commit");
        return vcsCommitValue;
    }

    /**
     * The version control reader for this workspace, for the one caller that needs more than the
     * two identity values: the test selection, which reads diffs.
     *
     * <p>Unlike the two getters this always constructs a reader, since a configured branch or commit
     * says nothing about diffs. It shares the instance with this identity's own resolution, so a
     * caller that needs both the branch and the diffs pays for one repository handle, or one
     * Perforce connection, rather than two.
     *
     * @return the reader for this workspace, constructed on the first call and reused afterwards
     * @throws IllegalStateException if this identity has already been closed
     */
    public VCSReader openVCSReader() {
        if (closed) {
            throw new IllegalStateException("this workspace identity is closed, so its VCS reader "
                    + "can no longer be used");
        }
        if (vcsReader == null) {
            vcsReader = vcsReaderSupplier.get();
            if (vcsReader == null) {
                throw new VCSAnalyzerException("no VCS reader was available for this workspace");
            }
        }
        return vcsReader;
    }

    /**
     * Read both values from the version control system, once, and cache them.
     *
     * <p>Both are read together because a reader that is open has already done the expensive part -
     * {@code GitReader} reads the branch and head commit in its constructor - so a second read costs
     * nothing, while a second <em>open</em> would cost another repository handle or Perforce
     * connection.
     *
     * <p>A failure here is wrapped rather than propagated, because the underlying message describes
     * the version control system ("Could not find .git directory") and says nothing about the way
     * out, which is to configure the value instead. The property named is the one the caller asked
     * for, so a runner missing only the commit is not told to set the branch.
     *
     * @param propertyName the property that would have made this read unnecessary, named in the
     *                     failure message
     * @param description what was being read, for the failure message
     * @throws VCSAnalyzerException if the reader could not be constructed or could not be read
     */
    private void readIdentityFromVcs(final String propertyName, final String description) {
        if (readFromVcs) {
            return;
        }
        log.debug("Resolving the workspace {} from the version control system: {} is not configured.",
                description, propertyName);
        try {
            VCSReader reader = openVCSReader();
            vcsBranch = reader.getBranchName();
            vcsCommitValue = reader.getHeadCommit();
            readFromVcs = true;
        } catch (RuntimeException e) {
            // Release whatever was opened before the failure: this identity is not closed, so
            // nothing else would.
            closeReader();
            throw new VCSAnalyzerException("Tia could not resolve the workspace " + description
                    + " from the version control system. Set " + propertyName + " when the build "
                    + "runs without version control access, for example on a distributed test run's "
                    + "runner: " + e.getMessage(), e);
        }
    }

    /**
     * Release the version control reader if one was constructed. Safe to call more than once, and a
     * no-op for an identity whose values all came from configuration - which is the case that never
     * constructed a reader in the first place.
     */
    @Override
    public void close() {
        closed = true;
        closeReader();
    }

    /**
     * Close the reader if one is open and forget it, so neither this method nor {@link #close()}
     * can close the same reader twice.
     */
    private void closeReader() {
        if (vcsReader != null) {
            VCSReader toClose = vcsReader;
            vcsReader = null;
            toClose.close();
        }
    }
}
