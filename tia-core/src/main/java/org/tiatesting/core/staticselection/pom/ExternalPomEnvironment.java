package org.tiatesting.core.staticselection.pom;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@link PomEnvironment} of an external POM (a parent or imported BOM outside the repository)
 * whose two versions are being compared. It has no reactor; its own parent is external too and is
 * read through the same {@link ExternalPoms}; its "children" are whatever in the repository
 * inherits from it, supplied by the environment that created it.
 */
final class ExternalPomEnvironment implements PomEnvironment {

    private final ExternalPoms externalPoms;
    private final String oldPom;
    private final String newPom;
    private final int depth;
    private final Supplier<Set<String>> childPropertyReferences;
    private final Supplier<List<PomChange>> childChanges;

    /**
     * Construct an environment.
     *
     * @param externalPoms the external POM reader.
     * @param oldPom the POM text at the old version.
     * @param newPom the POM text at the new version.
     * @param depth how many external POMs deep this one is; reading stops at {@link ExternalPoms#MAX_DEPTH}.
     * @param childPropertyReferences the properties referenced by the POMs inheriting from this one.
     * @param childChanges the changes this POM's property changes cause in the POMs inheriting from it.
     */
    ExternalPomEnvironment(final ExternalPoms externalPoms, final String oldPom, final String newPom, final int depth,
                           final Supplier<Set<String>> childPropertyReferences,
                           final Supplier<List<PomChange>> childChanges) {
        this.externalPoms = externalPoms;
        this.oldPom = oldPom;
        this.newPom = newPom;
        this.depth = depth;
        this.childPropertyReferences = childPropertyReferences;
        this.childChanges = childChanges;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> inheritedProperties(final boolean newSide) {
        return externalPoms.inheritedProperties(parse(newSide ? newPom : oldPom), depth);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ParentLocation parentLocation(final boolean newSide) {
        PomModel model = parse(newSide ? newPom : oldPom);
        return model == null || model.getParent() == null ? ParentLocation.UNKNOWN : ParentLocation.EXTERNAL;
    }

    /**
     * An external POM has no reactor: none of its dependencies are reactor modules.
     *
     * @return an empty set.
     */
    @Override
    public Set<String> reactorModules() {
        return Collections.emptySet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> childPropertyReferences() {
        return childPropertyReferences.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PomChange> childChanges() {
        return childChanges.get();
    }

    /**
     * An external POM is not part of the diff, so it is never added or deleted there.
     *
     * @param newSide unused.
     * @return {@code null}, unknown.
     */
    @Override
    public Boolean referencedByChangedPoms(final boolean newSide) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String externalPom(final String groupId, final String artifactId, final String version) {
        return depth >= ExternalPoms.MAX_DEPTH ? null : externalPoms.readPom(groupId, artifactId, version);
    }

    /**
     * The parent of this external POM inherits this POM's children, plus this POM itself, whose
     * own property uses are classified by this POM's diff.
     *
     * @param oldParentPom the parent POM text at the old version.
     * @param newParentPom the parent POM text at the new version.
     * @return the parent's environment.
     */
    @Override
    public PomEnvironment externalParentEnvironment(final String oldParentPom, final String newParentPom) {
        return new ExternalPomEnvironment(externalPoms, oldParentPom, newParentPom, depth + 1, () -> {
            Set<String> references = new HashSet<>(childPropertyReferences.get());
            references.addAll(ownReferences());
            return references;
        }, childChanges);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PomEnvironment importedBomEnvironment(final String oldBomPom, final String newBomPom) {
        return forImportedBom(externalPoms, oldBomPom, newBomPom, depth + 1);
    }

    /**
     * The environment of an imported BOM: no children, since an import brings in only dependency
     * management.
     *
     * @param externalPoms the external POM reader.
     * @param oldBomPom the BOM text at the old version.
     * @param newBomPom the BOM text at the new version.
     * @param depth how many external POMs deep the BOM is.
     * @return the BOM's environment.
     */
    static ExternalPomEnvironment forImportedBom(final ExternalPoms externalPoms, final String oldBomPom,
                                                 final String newBomPom, final int depth) {
        return new ExternalPomEnvironment(externalPoms, oldBomPom, newBomPom, depth,
                Collections::<String>emptySet, Collections::<PomChange>emptyList);
    }

    /**
     * @return the properties this POM references on either side.
     */
    private Set<String> ownReferences() {
        Set<String> references = new HashSet<>();
        for (String pom : new String[]{oldPom, newPom}) {
            PomModel model = parse(pom);
            if (model != null) {
                references.addAll(model.getPropertyReferences());
            }
        }
        return references;
    }

    /**
     * @param pom a POM text.
     * @return the parsed model, or {@code null} when it does not parse.
     */
    private static PomModel parse(final String pom) {
        try {
            return pom == null ? null : PomModel.parse(pom);
        } catch (PomParseException e) {
            return null;
        }
    }
}
