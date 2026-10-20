package org.tiatesting.core.staticselection.pom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.VCSReader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The repository's POMs on both sides of one diff, read from the VCS on demand and cached. Knows
 * how POMs relate: a POM's in-repo parent (through {@code <parent><relativePath>}, checked against
 * the declared coordinates and version), its reactor (the POMs reachable through
 * {@code <modules>} from the top-most aggregator above it), and its child modules (reactor POMs
 * whose parent chain includes it).
 *
 * <p>Reads are batched: one {@link VCSReader#loadFileContents} call per set of POMs needed at a
 * time (the directory candidates for an aggregator, then one call per level of the module tree).
 * Nothing beyond the changed POMs is read unless a classification decision needs it.
 *
 * <p>Paths are repo-relative and forward-slash separated, like {@link VCSReader#getChangedFilePaths}.
 */
final class PomTree {

    private static final Logger log = LoggerFactory.getLogger(PomTree.class);

    private static final String POM_FILE_NAME = "pom.xml";

    /** Maven's default {@code <relativePath>}. */
    private static final String DEFAULT_PARENT_RELATIVE_PATH = "../pom.xml";

    /** Bounds the parent walk so a cyclic layout cannot loop forever. */
    private static final int MAX_PARENT_DEPTH = 20;

    private final VCSReader vcsReader;
    private final String baseChangeNum;
    private final boolean checkLocalChanges;
    private final Map<String, FileContentPair> contents = new HashMap<>();
    private final Map<String, PomModel> oldModels = new HashMap<>();
    private final Map<String, PomModel> newModels = new HashMap<>();
    private final Map<String, Set<String>> reactorsByRoot = new HashMap<>();
    private final ExternalPoms externalPoms;

    /**
     * Construct a tree over one diff.
     *
     * @param vcsReader the VCS reader POM content is read from.
     * @param baseChangeNum the "before" commit of the diff.
     * @param checkLocalChanges whether the diff is head against the working copy.
     * @param externalPomSource reads POMs outside the repository (external parents, imported
     *                          BOMs); {@code null} when they are not available.
     */
    PomTree(final VCSReader vcsReader, final String baseChangeNum, final boolean checkLocalChanges,
            final ExternalPomSource externalPomSource) {
        this.vcsReader = vcsReader;
        this.baseChangeNum = baseChangeNum;
        this.checkLocalChanges = checkLocalChanges;
        this.externalPoms = new ExternalPoms(externalPomSource);
    }

    /**
     * @return the reader for POMs outside the repository.
     */
    ExternalPoms getExternalPoms() {
        return externalPoms;
    }

    /**
     * Read the content of every given path not read before, in one VCS call.
     *
     * @param paths the POM paths.
     */
    void load(final Collection<String> paths) {
        Set<String> missing = new LinkedHashSet<>();
        for (String path : paths) {
            if (path != null && !contents.containsKey(path)) {
                missing.add(path);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        Map<String, FileContentPair> loaded = vcsReader.loadFileContents(missing, baseChangeNum, checkLocalChanges);
        for (String path : missing) {
            FileContentPair content = loaded.get(path);
            contents.put(path, content != null ? content : new FileContentPair(null, null));
        }
    }

    /**
     * Read the parent chains of the given POMs ahead of classification, one level per VCS call
     * across all of them, so resolving inherited properties does not read one parent at a time.
     * Follows the {@code <relativePath>} candidates on both sides without checking coordinates;
     * {@link #parentPath} does the checking.
     *
     * @param paths the POMs whose parent chains to read.
     */
    void loadParentChains(final Collection<String> paths) {
        Set<String> seen = new HashSet<>(paths);
        Set<String> level = new LinkedHashSet<>(paths);
        for (int depth = 0; depth <= MAX_PARENT_DEPTH && !level.isEmpty(); depth++) {
            load(level);
            Set<String> next = new LinkedHashSet<>();
            for (String pom : level) {
                for (boolean newSide : new boolean[]{false, true}) {
                    String candidate = parentCandidate(pom, model(pom, newSide));
                    if (candidate != null && seen.add(candidate)) {
                        next.add(candidate);
                    }
                }
            }
            level = next;
        }
    }

    /**
     * The file a POM's {@code <relativePath>} points to (default {@code ../pom.xml}), unchecked.
     * Shared with {@link PomReferences} so both apply Maven's relative-path rule the same way.
     *
     * @param path the POM path.
     * @param model the POM on one side; may be {@code null}.
     * @return the candidate parent path, or {@code null} when there is no parent or no repository lookup.
     */
    static String parentCandidate(final String path, final PomModel model) {
        if (model == null || model.getParent() == null) {
            return null;
        }
        String relativePath = model.getParentRelativePath();
        if (relativePath == null) {
            relativePath = DEFAULT_PARENT_RELATIVE_PATH;
        } else if (relativePath.isEmpty()) {
            return null;
        }
        String candidate = pomFileAt(directoryOf(path), relativePath);
        return candidate == null || candidate.equals(path) ? null : candidate;
    }

    /**
     * The before / after content of a POM, reading it if needed.
     *
     * @param path the POM path.
     * @return the content; a side is {@code null} when the file does not exist there.
     */
    FileContentPair content(final String path) {
        load(Collections.singleton(path));
        return contents.get(path);
    }

    /**
     * The parsed POM on one side, reading it if needed.
     *
     * @param path the POM path.
     * @param newSide {@code true} for the "after" side.
     * @return the model, or {@code null} when the file does not exist on that side or is not a parseable POM.
     */
    PomModel model(final String path, final boolean newSide) {
        Map<String, PomModel> models = newSide ? newModels : oldModels;
        if (models.containsKey(path)) {
            return models.get(path);
        }
        FileContentPair content = content(path);
        String xml = newSide ? content.getNewContent() : content.getOldContent();
        PomModel model = null;
        if (xml != null) {
            try {
                model = PomModel.parse(xml);
            } catch (PomParseException e) {
                log.debug("Could not parse {} ({} side): {}", path, newSide ? "new" : "old", e.getMessage());
            }
        }
        models.put(path, model);
        return model;
    }

    /**
     * Find a POM's parent in the repository. The candidate is the file {@code <relativePath>}
     * points to (default {@code ../pom.xml}); it is the parent only when its effective
     * {@code groupId:artifactId} and version match the declared parent, as Maven checks.
     *
     * @param path the child POM path.
     * @param sides which side of the diff to read each POM on.
     * @return the parent POM's path, or {@code null} when the POM has no parent in the repository.
     */
    String parentPath(final String path, final Predicate<String> sides) {
        return parentPath(path, sides, 0);
    }

    /**
     * Depth-bounded {@link #parentPath(String, Predicate)}.
     *
     * @param path the child POM path.
     * @param sides which side of the diff to read each POM on.
     * @param depth how many parents have been followed so far.
     * @return the parent POM's path, or {@code null}.
     */
    private String parentPath(final String path, final Predicate<String> sides, final int depth) {
        PomModel child = model(path, sides.test(path));
        if (depth > MAX_PARENT_DEPTH) {
            return null;
        }
        String candidate = parentCandidate(path, child);
        if (candidate == null) {
            return null;
        }
        PomModel parent = model(candidate, sides.test(candidate));
        if (parent == null) {
            return null;
        }

        PomPropertyResolver parentResolver = PomPropertyResolver.forProject(parent,
                inheritedProperties(candidate, sides, depth + 1));
        String declared = parentResolver.resolve(child.getParentGroupId()) + ":"
                + parentResolver.resolve(PomXml.childText(child.getParent(), "artifactId")) + ":"
                + parentResolver.resolve(child.getParentVersion());
        String actual = parentResolver.resolve("${project.groupId}:${project.artifactId}:${project.version}");
        return declared.equals(actual) ? candidate : null;
    }

    /**
     * The properties a POM inherits from its ancestors, as written, nearest ancestor winning: its
     * in-repo parents, then the external parents above the top-most in-repo one, when they can be
     * read.
     *
     * @param path the POM path.
     * @param sides which side of the diff to read each POM on.
     * @return the inherited properties; empty when no parent can be read.
     */
    Map<String, String> inheritedProperties(final String path, final Predicate<String> sides) {
        return inheritedProperties(path, sides, 0);
    }

    /**
     * Depth-bounded {@link #inheritedProperties(String, Predicate)}.
     *
     * @param path the POM path.
     * @param sides which side of the diff to read each POM on.
     * @param depth how many parents have been followed so far.
     * @return the inherited properties.
     */
    private Map<String, String> inheritedProperties(final String path, final Predicate<String> sides,
                                                    final int depth) {
        String parent = parentPath(path, sides, depth);
        if (parent == null) {
            return externalPoms.inheritedProperties(model(path, sides.test(path)), 0);
        }
        Map<String, String> properties = new HashMap<>(inheritedProperties(parent, sides, depth + 1));
        properties.putAll(PomModel.properties(model(parent, sides.test(parent)).getProject()));
        return properties;
    }

    /**
     * The POM's in-repo ancestors, nearest first.
     *
     * @param path the POM path.
     * @param sides which side of the diff to read each POM on.
     * @return the ancestor paths; empty when the POM has no in-repo parent.
     */
    List<String> ancestors(final String path, final Predicate<String> sides) {
        List<String> ancestors = new ArrayList<>();
        String current = parentPath(path, sides);
        while (current != null && !ancestors.contains(current) && ancestors.size() <= MAX_PARENT_DEPTH) {
            ancestors.add(current);
            current = parentPath(current, sides);
        }
        return ancestors;
    }

    /**
     * The reactor a POM belongs to: every POM reachable through {@code <modules>} (on either side)
     * from the top-most {@code pom.xml} in the directories above it whose module tree includes it,
     * or the POM's own module tree when no aggregator above it lists it.
     *
     * @param path the POM path.
     * @return the reactor's POM paths, including {@code path}.
     */
    Set<String> reactor(final String path) {
        List<String> candidates = aggregatorCandidates(path);
        load(candidates);
        for (String candidate : candidates) {
            if (model(candidate, false) == null && model(candidate, true) == null) {
                continue;
            }
            Set<String> tree = moduleTree(candidate);
            if (tree.contains(path)) {
                return tree;
            }
        }
        return moduleTree(path);
    }

    /**
     * The {@code pom.xml} of every directory from the repository root down to the POM's own
     * directory, top-most first, plus the POM itself.
     *
     * @param path the POM path.
     * @return the candidate aggregator paths.
     */
    private List<String> aggregatorCandidates(final String path) {
        List<String> candidates = new ArrayList<>();
        String directory = directoryOf(path);
        String prefix = "";
        candidates.add(POM_FILE_NAME);
        if (!directory.isEmpty()) {
            for (String segment : directory.split("/")) {
                prefix = prefix.isEmpty() ? segment : prefix + "/" + segment;
                candidates.add(prefix + "/" + POM_FILE_NAME);
            }
        }
        if (!candidates.contains(path)) {
            candidates.add(path);
        }
        return candidates;
    }

    /**
     * Every POM reachable from a root through {@code <modules>} on either side, read one tree
     * level per VCS call. Cached by root.
     *
     * @param root the aggregator POM path.
     * @return the tree's POM paths, including the root.
     */
    private Set<String> moduleTree(final String root) {
        Set<String> cached = reactorsByRoot.get(root);
        if (cached != null) {
            return cached;
        }
        Set<String> tree = new LinkedHashSet<>();
        Deque<String> level = new ArrayDeque<>(Collections.singleton(root));
        while (!level.isEmpty()) {
            load(level);
            Set<String> next = new LinkedHashSet<>();
            for (String pom : level) {
                if (!tree.add(pom)) {
                    continue;
                }
                for (boolean newSide : new boolean[]{false, true}) {
                    PomModel model = model(pom, newSide);
                    if (model == null) {
                        continue;
                    }
                    for (String module : model.getModules()) {
                        String modulePom = pomFileAt(directoryOf(pom), module);
                        if (modulePom != null && !tree.contains(modulePom)) {
                            next.add(modulePom);
                        }
                    }
                }
            }
            level = new ArrayDeque<>(next);
        }
        log.debug("Read the module tree of {}: {} POM(s).", root, tree.size());
        reactorsByRoot.put(root, tree);
        return tree;
    }

    /**
     * The POM's in-repo child modules, transitively: the reactor POMs whose parent chain includes
     * it on either side of the diff.
     *
     * @param path the POM path.
     * @return the child POM paths; empty when it has none.
     */
    Set<String> children(final String path) {
        Set<String> children = new LinkedHashSet<>();
        Predicate<String> oldSides = pom -> false;
        Predicate<String> newSides = pom -> true;
        for (String pom : reactor(path)) {
            if (!pom.equals(path)
                    && (ancestors(pom, newSides).contains(path) || ancestors(pom, oldSides).contains(path))) {
                children.add(pom);
            }
        }
        return children;
    }

    /**
     * The effective {@code groupId:artifactId} of every module in the POM's reactor, on either side.
     *
     * @param path the POM path.
     * @return the reactor module coordinates.
     */
    Set<String> reactorModules(final String path) {
        Set<String> coordinates = new HashSet<>();
        for (String pom : reactor(path)) {
            for (boolean newSide : new boolean[]{false, true}) {
                PomModel model = model(pom, newSide);
                if (model == null) {
                    continue;
                }
                Predicate<String> sides = newSide ? (p -> true) : (p -> false);
                PomPropertyResolver resolver = PomPropertyResolver.forProject(model, inheritedProperties(pom, sides));
                coordinates.add(resolver.resolve("${project.groupId}:${project.artifactId}"));
            }
        }
        return coordinates;
    }

    /**
     * The directory part of a repo-relative path.
     *
     * @param path the path.
     * @return the directory, or empty for a file at the repository root.
     */
    static String directoryOf(final String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /**
     * Resolve a POM reference (a {@code <module>} entry or {@code <relativePath>}) against a
     * directory: a reference to an {@code .xml} file is used as is, a directory reference gets
     * {@code pom.xml} appended.
     *
     * @param directory the referring POM's directory.
     * @param reference the reference, relative to {@code directory}.
     * @return the normalised repo-relative POM path, or {@code null} when the reference is
     *         absolute or leaves the repository.
     */
    static String pomFileAt(final String directory, final String reference) {
        String normalized = normalize(directory, reference.trim().replace('\\', '/'));
        if (normalized == null) {
            return null;
        }
        if (normalized.endsWith(".xml")) {
            return normalized;
        }
        return normalized.isEmpty() ? POM_FILE_NAME : normalized + "/" + POM_FILE_NAME;
    }

    /**
     * Join a relative reference onto a directory, folding {@code .} and {@code ..} segments.
     *
     * @param directory the base directory, repo-relative.
     * @param reference the forward-slash relative reference.
     * @return the normalised path, or {@code null} when the reference is absolute or climbs
     *         above the repository root.
     */
    private static String normalize(final String directory, final String reference) {
        if (reference.startsWith("/") || reference.matches("^[A-Za-z]:.*")) {
            return null;
        }
        Deque<String> segments = new ArrayDeque<>();
        String joined = directory.isEmpty() ? reference : directory + "/" + reference;
        for (String segment : joined.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return String.join("/", segments);
    }
}
