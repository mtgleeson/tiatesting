package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.tiatesting.core.library.LibraryPublishFanOut;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.WorkspaceIdentity;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;

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
@Mojo(name = "publish-lib-stamp", defaultPhase = LifecyclePhase.INSTALL)
public class PublishLibStampMojo extends AbstractTiaMojo {

    /**
     * Record this module's publish in the ledger and stamp its impacted methods, evaluating this
     * module's own configured static test selection rules (built the same way as the
     * {@code tia-select-tests} mojo, via {@link #buildStaticTestSelectionConfig()}) against the
     * files changed since the previous publish. The impacted-method diff runs over this module's
     * own compile source roots that exist on disk. No-ops when Tia is disabled or this build does not
     * own mapping-DB writes; the stamper itself skips (with a warning) when the module is not a
     * tracked library in the Tia DB.
     */
    @Override
    public void execute() throws MojoExecutionException {
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

        StaticTestSelectionConfig staticConfig = buildStaticTestSelectionConfig();
        // Diff against this module's own source roots, not the absolute paths the consumer's build
        // recorded on the tracked library - those belong to the consumer's workspace and need not
        // exist or be mapped in this build's VCS client.
        List<String> publishingSourceDirs = LibraryJarResolver.filterExistingSourceRoots(
                getProject().getCompileSourceRoots(), getProject().getId(), getLog());

        // The consuming schemas are discovered, never declared: every schema in the Tia database
        // holding a tracked-library row for this coordinate. This module's own branch picks no
        // schema - it is only the publish's source line on a VCS where a branch is the line.
        String dbUser = resolveDbUser();
        String dbPassword = resolveDbPassword();
        List<String> schemas = DataStoreFactory.findSchemasTrackingLibrary(getTiaDBFilePath(), getTiaDBUrl(),
                dbUser, dbPassword, getTiaDBDialect(), groupArtifact);
        if (schemas.isEmpty()) {
            getLog().warn("Tia publish stamp skipped: no schema in the Tia database tracks library '"
                    + groupArtifact + "'. It becomes tracked once a consuming project lists it in "
                    + "tiaSourceLibs and runs.");
            return;
        }

        // One identity for every schema stamped. Unlike the reporting goals this one cannot be
        // satisfied by a configured branch alone - the stamper reads the publish's own commit from
        // the VCS - but it still opens one reader for the whole goal rather than one per schema.
        LibraryPublishFanOut.FanOutResult result;
        try (WorkspaceIdentity workspaceIdentity = workspaceIdentity()) {
            result = new LibraryPublishFanOut().stampEachSchema(schemas,
                    schemaName -> DataStoreFactory.forSchema(getTiaDBFilePath(), getTiaDBUrl(), dbUser,
                            dbPassword, getTiaDBDialect(), schemaName),
                    workspaceIdentity.openVCSReader(), groupArtifact, publishedVersion, jarFilePath,
                    staticConfig, publishingSourceDirs, workspaceIdentity.getBranch());
        }

        // Every schema is attempted rather than failing at the first, so a partial stamp leaves the
        // smallest possible gap - the publish has already happened by now and cannot be undone. A
        // warning would not do: what it describes is silent under-selection in the schemas that
        // missed the stamp.
        String failure = result.failureMessage(groupArtifact, publishedVersion);
        if (failure != null) {
            throw new MojoExecutionException(failure);
        }
        if (!result.anyFollowsThisLine()) {
            getLog().warn("Tia publish stamp for " + groupArtifact + " " + publishedVersion + ": "
                    + schemas.size() + " schema(s) track the library but none follows the source line it "
                    + "was published from - recorded in their ledgers only, nothing stamped.");
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
