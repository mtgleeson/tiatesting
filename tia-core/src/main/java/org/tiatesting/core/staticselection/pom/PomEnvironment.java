package org.tiatesting.core.staticselection.pom;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the classifier knows about the POMs around the one it is classifying: the properties it
 * inherits from its parents (in-repo, then external), whether its parent is in the repository,
 * the coordinates of the reactor's modules, how its child modules use its properties, and the
 * external POMs (parents and imported BOMs) it can read to see what their version bumps change.
 * Methods returning reactor or child information are only called when a decision needs them, so
 * an implementation can load other POMs lazily.
 *
 * <p>{@link #NONE} knows nothing beyond the POM itself; the classifier then falls back to
 * single-file heuristics (see {@link PomChangeClassifier}).
 */
interface PomEnvironment {

    /** Where a POM's parent was found. */
    enum ParentLocation {
        /** Not determined; the classifier uses its single-file heuristic. */
        UNKNOWN,
        /** The parent POM is in the repository at the declared coordinates and version. */
        IN_REPO,
        /** The parent is not in the repository (or not at the declared version), so Maven resolves it externally. */
        EXTERNAL
    }

    /** An environment with no knowledge of other POMs. */
    PomEnvironment NONE = new PomEnvironment() {
        @Override
        public Map<String, String> inheritedProperties(final boolean newSide) {
            return Collections.emptyMap();
        }

        @Override
        public ParentLocation parentLocation(final boolean newSide) {
            return ParentLocation.UNKNOWN;
        }

        @Override
        public Set<String> reactorModules() {
            return null;
        }

        @Override
        public Set<String> childPropertyReferences() {
            return null;
        }

        @Override
        public List<PomChange> childChanges() {
            return Collections.emptyList();
        }

        @Override
        public Boolean referencedByChangedPoms(final boolean newSide) {
            return null;
        }

        @Override
        public String externalPom(final String groupId, final String artifactId, final String version) {
            return null;
        }

        @Override
        public PomEnvironment externalParentEnvironment(final String oldParentPom, final String newParentPom) {
            return NONE;
        }

        @Override
        public PomEnvironment importedBomEnvironment(final String oldBomPom, final String newBomPom) {
            return NONE;
        }
    };

    /**
     * The properties the POM inherits from its ancestors, nearest ancestor winning, as written
     * (unresolved): its in-repo parents, then, above the top-most in-repo parent, any external
     * parents that can be read.
     *
     * @param newSide {@code true} for the "after" side of the diff, {@code false} for the "before" side.
     * @return the inherited property values by name; empty when no ancestor can be read.
     */
    Map<String, String> inheritedProperties(boolean newSide);

    /**
     * Where the POM's declared parent is on one side of the diff.
     *
     * @param newSide {@code true} for the "after" side, {@code false} for the "before" side.
     * @return the parent's location; {@link ParentLocation#UNKNOWN} when not determined.
     */
    ParentLocation parentLocation(boolean newSide);

    /**
     * The {@code groupId:artifactId} of every module in the POM's reactor, on either side of the diff.
     *
     * @return the reactor module coordinates, or {@code null} when unknown.
     */
    Set<String> reactorModules();

    /**
     * The names of the properties referenced by the POM's in-repo child modules (transitively),
     * on either side of the diff.
     *
     * @return the referenced property names, or {@code null} when the children are unknown.
     */
    Set<String> childPropertyReferences();

    /**
     * The functional changes the POM's property changes cause in its in-repo child modules: each
     * child is classified with its own content unchanged and only this POM's side of the diff
     * switched, so a child's own edits are not attributed to this POM.
     *
     * @return the changes, labelled with the child POM's path; empty when there are none.
     */
    List<PomChange> childChanges();

    /**
     * Whether another changed POM in the diff lists this POM as a {@code <module>} or points its
     * parent's {@code <relativePath>} at it. Asked only for a POM that was added (new side) or
     * deleted (old side): a build can only pick such a POM up through one of those references, so
     * when none exists the POM is inert on its own. See {@link PomReferences}.
     *
     * @param newSide {@code true} for the "after" side (an added POM), {@code false} for the
     *                "before" side (a deleted POM).
     * @return whether it is referenced, or {@code null} when unknown.
     */
    Boolean referencedByChangedPoms(boolean newSide);

    /**
     * Read a POM that is not in the repository (an external parent or an imported BOM).
     *
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the exact version.
     * @return the POM text, or {@code null} when it is not available.
     */
    String externalPom(String groupId, String artifactId, String version);

    /**
     * The environment for classifying the diff of this POM's external parent between its two
     * versions. Its "children" are this POM and this POM's in-repo child modules, so a parent
     * property they use is inert and its effect on the children is added.
     *
     * @param oldParentPom the parent POM text at the old version.
     * @param newParentPom the parent POM text at the new version.
     * @return the parent's environment.
     */
    PomEnvironment externalParentEnvironment(String oldParentPom, String newParentPom);

    /**
     * The environment for classifying the diff of a BOM this POM imports between its two
     * versions. An import only brings in dependency management, so the BOM has no children.
     *
     * @param oldBomPom the BOM text at the old version.
     * @param newBomPom the BOM text at the new version.
     * @return the BOM's environment.
     */
    PomEnvironment importedBomEnvironment(String oldBomPom, String newBomPom);
}
