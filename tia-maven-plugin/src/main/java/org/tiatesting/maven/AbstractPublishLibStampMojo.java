package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.tiatesting.core.library.LibraryPublishStamper;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.vcs.VCSReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public abstract class AbstractPublishLibStampMojo extends AbstractTiaMojo {

    /**
     * Record this module's publish in the ledger and stamp its impacted methods, evaluating this
     * module's own configured static test selection rules (built the same way as the
     * {@code tia-select-tests} mojo, via {@link #buildStaticTestSelectionConfig()}) against the
     * files changed since the previous publish. No-ops when Tia is disabled or this build does not
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

        final VCSReader vcsReader = getVCSReader();
        StaticTestSelectionConfig staticConfig = buildStaticTestSelectionConfig();

        // The consuming schemas are declared, never derived: the consuming app is a separate build,
        // so this module cannot see its schemas. A stamp written where no consumer reads it is
        // never drained, and the suites the library change affects are never re-run. Undeclared
        // means this module's own schema, which is where the stamp has always gone.
        List<String> targetSuffixes = declaredStampSchemas();
        List<String> stamped = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (String suffix : targetSuffixes) {
            String schemaLabel = suffix == null ? "(none)" : suffix;
            try (DataStore dataStore = buildDataStore(vcsReader.getBranchName(), suffix)) {
                LibraryPublishStamper.PublishStampResult result = new LibraryPublishStamper()
                        .stampPublish(dataStore, vcsReader, groupArtifact, publishedVersion,
                                jarFilePath, staticConfig);
                getLog().info("Tia publish stamp for " + groupArtifact + " " + publishedVersion
                        + " into schema " + schemaLabel + ": " + result.getOutcome() + " (seq "
                        + result.getPublishSeq() + ", " + result.getStampedMethodIds().size()
                        + " methods).");
                stamped.add(schemaLabel);
            } catch (RuntimeException e) {
                getLog().error("Tia publish stamp for " + groupArtifact + " " + publishedVersion
                        + " FAILED for schema " + schemaLabel + ".", e);
                failed.put(schemaLabel, String.valueOf(e.getMessage()));
            }
        }

        if (!failed.isEmpty()) {
            // Every schema is attempted rather than failing at the first, so a partial stamp leaves
            // the smallest possible gap - the publish has already happened by now and cannot be
            // undone. A warning would not do: what it describes is silent under-selection in the
            // schemas that missed the stamp.
            throw new MojoExecutionException("Tia: the publish stamp for " + groupArtifact + " "
                    + publishedVersion + " reached " + stamped + " but FAILED for " + failed.keySet()
                    + ". Those schemas have no record of this publish, so they will never drain the"
                    + " methods it changed and never re-run the suites those methods affect - a"
                    + " silent gap in their selection until the library publishes again. Re-run the"
                    + " publish stamp once the cause is fixed. Failures: " + failed);
        }
    }

    /**
     * The schemas this publish stamp is written to: the declared consuming schemas, or this
     * module's own schema when none is declared.
     *
     * @return the schema suffixes to stamp, blanks discarded; never empty
     */
    private List<String> declaredStampSchemas() {
        List<String> suffixes = new ArrayList<>();
        String declared = getTiaLibraryStampSchemas();

        if (declared != null && !declared.trim().isEmpty()) {
            for (String entry : declared.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    suffixes.add(trimmed);
                }
            }
        }

        if (suffixes.isEmpty()) {
            suffixes.add(getTiaDBSchemaSuffix());
        }
        return suffixes;
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
