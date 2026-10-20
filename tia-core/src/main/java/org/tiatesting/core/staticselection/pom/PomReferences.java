package org.tiatesting.core.staticselection.pom;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Which POM files the changed POMs of one diff point at, on each side of the diff: through a
 * {@code <module>} entry (in the project or a profile), or as the target of their parent's
 * {@code <relativePath>} (default {@code ../pom.xml}). An added or deleted POM that no changed POM
 * points at is not part of any build, because a build can only pick it up through such a
 * reference and adding or removing one edits a POM in the same diff. A matching groupId,
 * artifactId and version alone is not a reference: Maven resolves those from the repository. See
 * the "Static test selection" chapter in {@code WIKI.md}.
 */
final class PomReferences {

    private final Set<String> oldSide;
    private final Set<String> newSide;

    /**
     * Construct an index.
     *
     * @param oldSide the paths referenced on the "before" side.
     * @param newSide the paths referenced on the "after" side.
     */
    private PomReferences(final Set<String> oldSide, final Set<String> newSide) {
        this.oldSide = oldSide;
        this.newSide = newSide;
    }

    /**
     * Index the references of the given POMs on both sides of the diff, reading any not yet read
     * in one batched VCS call. A POM that is missing or unparseable on a side contributes nothing
     * on that side.
     *
     * @param tree the repository's POMs.
     * @param referringPoms the changed POM paths whose references are indexed.
     * @return the index.
     */
    static PomReferences build(final PomTree tree, final Collection<String> referringPoms) {
        tree.load(referringPoms);
        Set<String> oldReferences = new HashSet<>();
        Set<String> newReferences = new HashSet<>();
        for (String pom : referringPoms) {
            collect(tree, pom, false, oldReferences);
            collect(tree, pom, true, newReferences);
        }
        return new PomReferences(oldReferences, newReferences);
    }

    /**
     * Add the POM files one POM points at on one side: its module POMs and its parent candidate.
     *
     * @param tree the repository's POMs.
     * @param pom the referring POM path.
     * @param newSide {@code true} for the "after" side.
     * @param references the accumulator.
     */
    private static void collect(final PomTree tree, final String pom, final boolean newSide,
                                final Set<String> references) {
        PomModel model = tree.model(pom, newSide);
        if (model == null) {
            return;
        }
        for (String module : model.getModules()) {
            String modulePom = PomTree.pomFileAt(PomTree.directoryOf(pom), module);
            if (modulePom != null) {
                references.add(modulePom);
            }
        }
        String parent = PomTree.parentCandidate(pom, model);
        if (parent != null) {
            references.add(parent);
        }
    }

    /**
     * Whether a changed POM points at the given POM file on one side of the diff.
     *
     * @param path the POM path.
     * @param newSide {@code true} for the "after" side (an added POM), {@code false} for the
     *                "before" side (a deleted POM).
     * @return whether it is referenced on that side.
     */
    boolean isReferenced(final String path, final boolean newSide) {
        return (newSide ? this.newSide : oldSide).contains(path);
    }
}
