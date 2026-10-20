package org.tiatesting.core.staticselection.pom;

import org.tiatesting.core.vcs.FileContentPair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A {@link PomEnvironment} backed by a {@link PomTree}. Each piece of knowledge is computed on
 * first use and kept, so a classification that never needs the reactor or the child modules never
 * reads them.
 *
 * <p>The "before" and "after" sides choose which side of the diff to read each POM on. For a
 * changed POM that is old-for-everything versus new-for-everything. For a child module classified
 * to find the effect of one POM's change, it is "that POM old, everything else new" versus
 * "everything new", so only that POM's change is attributed.
 */
final class PomTreeEnvironment implements PomEnvironment {

    private final PomTree tree;
    private final PomChangeClassifier classifier;
    private final String path;
    private final Predicate<String> oldSides;
    private final Predicate<String> newSides;
    private final boolean classifyChildren;
    private final Supplier<PomReferences> references;

    private Set<String> reactorModules;
    private Set<String> children;
    private Set<String> childPropertyReferences;
    private List<PomChange> childChanges;

    /**
     * Construct an environment.
     *
     * @param tree the repository's POMs.
     * @param classifier the classifier used for child modules.
     * @param path the POM being classified.
     * @param oldSides which side of the diff each POM is read on for the "before" side.
     * @param newSides which side of the diff each POM is read on for the "after" side.
     * @param classifyChildren whether {@link #childChanges()} classifies the child modules; off for
     *                         a child itself, whose own children are covered by the POM that changed.
     * @param references the diff's reference index, built on first use; {@code null} for a child
     *                   module, which is never added or deleted.
     */
    private PomTreeEnvironment(final PomTree tree, final PomChangeClassifier classifier, final String path,
                               final Predicate<String> oldSides, final Predicate<String> newSides,
                               final boolean classifyChildren, final Supplier<PomReferences> references) {
        this.tree = tree;
        this.classifier = classifier;
        this.path = path;
        this.oldSides = oldSides;
        this.newSides = newSides;
        this.classifyChildren = classifyChildren;
        this.references = references;
    }

    /**
     * The environment for a changed POM: everything old versus everything new.
     *
     * @param tree the repository's POMs.
     * @param classifier the classifier used for child modules.
     * @param path the changed POM.
     * @param references the diff's reference index, built on first use.
     * @return the environment.
     */
    static PomTreeEnvironment forChangedPom(final PomTree tree, final PomChangeClassifier classifier,
                                            final String path, final Supplier<PomReferences> references) {
        return new PomTreeEnvironment(tree, classifier, path, pom -> false, pom -> true, true, references);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> inheritedProperties(final boolean newSide) {
        return tree.inheritedProperties(path, newSide ? newSides : oldSides);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ParentLocation parentLocation(final boolean newSide) {
        Predicate<String> sides = newSide ? newSides : oldSides;
        PomModel model = tree.model(path, sides.test(path));
        if (model == null || model.getParent() == null) {
            return ParentLocation.UNKNOWN;
        }
        return tree.parentPath(path, sides) != null ? ParentLocation.IN_REPO : ParentLocation.EXTERNAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> reactorModules() {
        if (reactorModules == null) {
            reactorModules = tree.reactorModules(path);
        }
        return reactorModules;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> childPropertyReferences() {
        if (childPropertyReferences == null) {
            Set<String> references = new HashSet<>();
            for (String child : children()) {
                for (boolean newSide : new boolean[]{false, true}) {
                    PomModel model = tree.model(child, newSide);
                    if (model != null) {
                        references.addAll(model.getPropertyReferences());
                    }
                }
            }
            childPropertyReferences = references;
        }
        return childPropertyReferences;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PomChange> childChanges() {
        if (childChanges != null) {
            return childChanges;
        }
        if (!classifyChildren) {
            childChanges = Collections.emptyList();
            return childChanges;
        }
        List<PomChange> changes = new ArrayList<>();
        Predicate<String> thisPomOld = pom -> !pom.equals(path);
        for (String child : children()) {
            FileContentPair content = tree.content(child);
            if (content.getNewContent() == null) {
                continue;
            }
            PomTreeEnvironment childEnvironment = new PomTreeEnvironment(tree, classifier, child,
                    thisPomOld, pom -> true, false, null);
            PomChangeReport report = classifier.classify(content.getNewContent(), content.getNewContent(),
                    childEnvironment);
            for (PomChange change : report.getChanges()) {
                if (!change.isInert()) {
                    changes.add(change.withDescriptionSuffix(" (in " + child + ")"));
                }
            }
        }
        childChanges = Collections.unmodifiableList(changes);
        return childChanges;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Boolean referencedByChangedPoms(final boolean newSide) {
        return references == null ? null : references.get().isReferenced(path, newSide);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String externalPom(final String groupId, final String artifactId, final String version) {
        return tree.getExternalPoms().readPom(groupId, artifactId, version);
    }

    /**
     * The external parent's "children" are this POM and its in-repo child modules: a parent
     * property either uses is inert, and the effect on the children is this POM's
     * {@link #childChanges()} (each child classified with this POM, and so its external parent
     * version, switched old to new). This POM's own uses are classified by this POM's diff, since
     * its inherited properties include the external parents'.
     *
     * @param oldParentPom the parent POM text at the old version.
     * @param newParentPom the parent POM text at the new version.
     * @return the parent's environment.
     */
    @Override
    public PomEnvironment externalParentEnvironment(final String oldParentPom, final String newParentPom) {
        return new ExternalPomEnvironment(tree.getExternalPoms(), oldParentPom, newParentPom, 1, () -> {
            Set<String> references = new HashSet<>(childPropertyReferences());
            for (boolean newSide : new boolean[]{false, true}) {
                PomModel model = tree.model(path, (newSide ? newSides : oldSides).test(path));
                if (model != null) {
                    references.addAll(model.getPropertyReferences());
                }
            }
            return references;
        }, this::childChanges);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PomEnvironment importedBomEnvironment(final String oldBomPom, final String newBomPom) {
        return ExternalPomEnvironment.forImportedBom(tree.getExternalPoms(), oldBomPom, newBomPom, 1);
    }

    /**
     * @return the POM's in-repo child modules, read on first use.
     */
    private Set<String> children() {
        if (children == null) {
            children = tree.children(path);
        }
        return children;
    }
}
