package org.tiatesting.core.staticselection.pom;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the classifier knows about the POMs around the one it is classifying: the properties it
 * inherits from in-repo parents, whether its parent is in the repository, the coordinates of the
 * reactor's modules, and how its child modules use its properties. Methods returning reactor or
 * child information are only called when a decision needs them, so an implementation can load
 * other POMs lazily.
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
    };

    /**
     * The properties the POM inherits from its in-repo ancestors, nearest ancestor winning, as
     * written (unresolved).
     *
     * @param newSide {@code true} for the "after" side of the diff, {@code false} for the "before" side.
     * @return the inherited property values by name; empty when there are no in-repo ancestors.
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
}
