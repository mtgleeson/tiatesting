package org.tiatesting.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.library.LibraryPublishStamper.PublishStampResult;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.vcs.VCSReader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one library publish into every schema that consumes the library. The consuming schemas
 * are discovered (see {@code DataStoreFactory#findSchemasTrackingLibrary}); this class stamps each
 * and gathers what happened, so the Maven and Gradle publish hooks share one implementation. See
 * the consumer-discovery section of the library publish-time stamping chapter in {@code WIKI.md}.
 */
public final class LibraryPublishFanOut {

    private static final Logger log = LoggerFactory.getLogger(LibraryPublishFanOut.class);

    /** Opens a datastore on a named schema. */
    public interface SchemaDataStores {
        /**
         * Open a datastore on the given schema. The caller closes it.
         *
         * @param schemaName the exact schema name, as discovery returned it
         * @return the datastore
         */
        DataStore open(String schemaName);
    }

    /**
     * Write a publish into every consuming schema. Stamping several schemas is not atomic - each
     * is its own connection and transaction, and the publish itself has already happened - so
     * every schema is attempted rather than stopping at the first failure, leaving the smallest
     * possible gap. The caller fails the build from {@link FanOutResult#failureMessage}.
     *
     * <p>The schemas share one {@link PublishScopedVCSReader}, so the VCS work is done once per
     * distinct question - the source line once per publish, and each diff once for all the schemas
     * whose baseline and previous publish match - rather than once per schema.
     *
     * @param schemas the consuming schemas' names, from consumer discovery
     * @param dataStores opens a datastore on a named schema
     * @param vcsReader the publishing workspace's VCS reader, shared across schemas; the caller
     *                  keeps ownership and closes it
     * @param groupArtifact the published library's {@code groupId:artifactId}
     * @param publishedVersion the version being published
     * @param jarFilePath the built archive's path, for content hashing; may be null
     * @param staticConfig the library's static test selection configuration
     * @param publishingSourceDirs the publishing module's own source roots
     * @param publishingBranch the branch the publishing workspace identifies as, or null when
     *                         unknown; the source line on a VCS where a branch is the line
     * @return what each schema recorded, and which failed
     */
    public FanOutResult stampEachSchema(final List<String> schemas, final SchemaDataStores dataStores,
                                        final VCSReader vcsReader, final String groupArtifact,
                                        final String publishedVersion, final String jarFilePath,
                                        final StaticTestSelectionConfig staticConfig,
                                        final List<String> publishingSourceDirs,
                                        final String publishingBranch) {
        Map<String, PublishStampResult> stamped = new LinkedHashMap<>();
        Map<String, String> failed = new LinkedHashMap<>();
        VCSReader publishScopedReader = new PublishScopedVCSReader(vcsReader);
        for (String schema : schemas) {
            try (DataStore dataStore = dataStores.open(schema)) {
                PublishStampResult result = new LibraryPublishStamper().stampPublish(dataStore, publishScopedReader,
                        groupArtifact, publishedVersion, jarFilePath, staticConfig, publishingSourceDirs,
                        publishingBranch);
                log.info("Tia publish stamp for {} {} into schema {}: {} (seq {}, {} methods).", groupArtifact,
                        publishedVersion, schema, result.getOutcome(), result.getPublishSeq(),
                        result.getStampedMethodIds().size());
                stamped.put(schema, result);
            } catch (RuntimeException e) {
                log.error("Tia publish stamp for {} {} FAILED for schema {}.", groupArtifact, publishedVersion,
                        schema, e);
                failed.put(schema, String.valueOf(e.getMessage()));
            }
        }
        return new FanOutResult(stamped, failed);
    }

    /** What a fan-out recorded in each schema, and which schemas it could not reach. */
    public static final class FanOutResult {
        private final Map<String, PublishStampResult> stamped;
        private final Map<String, String> failed;

        /**
         * @param stamped the stamp result per schema that recorded the publish, in stamping order
         * @param failed the failure message per schema that did not
         */
        FanOutResult(final Map<String, PublishStampResult> stamped, final Map<String, String> failed) {
            this.stamped = stamped;
            this.failed = failed;
        }

        /**
         * The schemas that recorded the publish.
         *
         * @return the stamp result per schema, in stamping order
         */
        public Map<String, PublishStampResult> getStamped() {
            return stamped;
        }

        /**
         * The schemas that did not record the publish.
         *
         * @return the failure message per schema; empty when every schema was reached
         */
        public Map<String, String> getFailed() {
            return failed;
        }

        /**
         * Build the message the publishing build fails with when a schema missed the publish. A
         * warning would not do: what it describes is silent under-selection in those schemas.
         *
         * @param groupArtifact the published library's {@code groupId:artifactId}
         * @param publishedVersion the version being published
         * @return the failure message naming the schemas reached and missed, or null when none failed
         */
        public String failureMessage(final String groupArtifact, final String publishedVersion) {
            if (failed.isEmpty()) {
                return null;
            }
            return "Tia: the publish stamp for " + groupArtifact + " " + publishedVersion + " reached "
                    + stamped.keySet() + " but FAILED for " + failed.keySet()
                    + ". Those schemas have no record of this publish, so they will never drain the"
                    + " methods it changed and never re-run the suites those methods affect - a"
                    + " silent gap in their selection until the library publishes again. Re-run the"
                    + " publish stamp once the cause is fixed. Failures: " + failed;
        }
    }
}
