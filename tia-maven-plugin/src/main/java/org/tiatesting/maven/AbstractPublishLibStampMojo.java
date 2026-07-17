package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.tiatesting.core.library.LibraryPublishStamper;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.vcs.VCSReader;

/**
 * Mojo that records a library publish in the Tia publish ledger and stamps the source methods
 * impacted since the library's mapping baseline. Bound by default to the {@code install} phase so
 * one binding covers both a local {@code mvn install} (which publishes to {@code ~/.m2}, where a
 * local consumer build resolves from) and a CI {@code mvn deploy} (whose lifecycle runs the
 * install phase on the way through). Configured on the <em>library</em> module's build - the
 * consumer's Tia run only drains what this mojo stamps. Concrete subclasses live in each
 * {@code *-maven-plugin} module and only need to supply a {@link VCSReader} via
 * {@link #getVCSReader()}. See the library publish-time stamping chapter in {@code WIKI.md}.
 *
 * <p>Only a build that owns mapping-DB writes may stamp: when {@code tiaUpdateDBMapping} is
 * false (a developer machine against a shared DB) the mojo is a no-op - the local development
 * flow is covered app-side without any persisted stamps.
 */
public abstract class AbstractPublishLibStampMojo extends AbstractTiaMojo {

    /**
     * Record this module's publish in the ledger and stamp its impacted methods. No-ops when Tia
     * is disabled or this build does not own mapping-DB writes; the stamper itself skips (with a
     * warning) when the module is not a tracked library in the Tia DB.
     */
    @Override
    public void execute() {
        if (!isTiaEnabled()) {
            getLog().debug("Tia is disabled - skipping publish stamp.");
            return;
        }
        if (!isTiaUpdateDBMapping()) {
            getLog().info("Tia publish stamp skipped: this build does not own mapping-DB writes "
                    + "(tiaUpdateDBMapping=false).");
            return;
        }

        String groupArtifact = getProject().getGroupId() + ":" + getProject().getArtifactId();
        String publishedVersion = getProject().getVersion();
        String jarFilePath = resolveBuiltArtifactPath();

        final VCSReader vcsReader = getVCSReader();
        try (DataStore dataStore = new H2DataStore(buildH2ConnectionSettings(vcsReader.getBranchName()))) {
            LibraryPublishStamper.PublishStampResult result = new LibraryPublishStamper()
                    .stampPublish(dataStore, vcsReader, groupArtifact, publishedVersion, jarFilePath);
            getLog().info("Tia publish stamp for " + groupArtifact + " " + publishedVersion
                    + ": " + result.getOutcome() + " (seq " + result.getPublishSeq()
                    + ", " + result.getStampedMethodIds().size() + " methods).");
        }
    }

    /**
     * Resolve the file path of the artifact this build produced, for content-hashing into the
     * ledger row. At the install phase the packaged artifact file is attached to the project;
     * when it is unavailable (e.g. packaging skipped) the publish is still recorded, with a null
     * hash - the drain then identifies the build by exact version for releases.
     *
     * @return the built artifact's absolute path, or null when no artifact file is attached.
     */
    private String resolveBuiltArtifactPath() {
        Artifact artifact = getProject().getArtifact();
        if (artifact == null || artifact.getFile() == null) {
            getLog().warn("No built artifact file attached to the project - the publish will be "
                    + "recorded without a jar hash.");
            return null;
        }
        return artifact.getFile().getAbsolutePath();
    }
}
