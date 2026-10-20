package org.tiatesting.core.staticselection.pom;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies the difference between two versions of a single POM into {@link PomChange}s, so a
 * POM-aware static test selection rule fires only for changes that can affect the behaviour of
 * the code or tests. See the "Static test selection" chapter in {@code WIKI.md}.
 *
 * <p>Inert: project metadata, the project's own version, a same-reactor parent version, reactor
 * module dependency versions, tracked-library versions (owned by library impact analysis), inert
 * plugins, Tia's own plugin and {@code tia*} properties, and an added or deleted POM that no other
 * changed POM lists as a module or parent. Everything unrecognised is
 * {@link PomChangeCategory#OTHER}.
 *
 * <p>Values are compared after resolving {@code ${...}} references against the POM's own
 * properties and those inherited from in-repo parents, so a property change is classified by what
 * it changes. A changed property is itself inert when this POM or its child modules reference it
 * (a child's resulting changes are added, see {@link PomEnvironment#childChanges()}), and
 * {@code OTHER} when nothing references it.
 *
 * <p>The surrounding POMs come from a {@link PomEnvironment}. A parent is in the same reactor
 * when it is in the repository at the declared version, and a dependency is a reactor module when
 * its coordinates belong to the reactor. With {@link PomEnvironment#NONE} (a single file) the
 * classifier falls back to heuristics: a parent is in the reactor when the project inherits its
 * version or moved in lockstep with it; a dependency is a reactor module when its version is
 * {@code ${project.version}} or it shares the project's groupId and moved in lockstep with the
 * project version; a changed property in a POM that may be a parent is {@code OTHER}; and an
 * added or deleted POM is {@code OTHER}.
 */
public final class PomChangeClassifier {

    private static final String DEFAULT_PLUGIN_GROUP_ID = "org.apache.maven.plugins";

    /** Top-level and profile elements that never affect what the code does. */
    private static final Set<String> INERT_ELEMENTS = new HashSet<>(Arrays.asList(
            "modelVersion", "name", "description", "url", "inceptionYear", "organization", "licenses",
            "developers", "contributors", "mailingLists", "scm", "issueManagement", "ciManagement",
            "distributionManagement", "reporting", "prerequisites"));

    /** Project elements compared by dedicated logic rather than as opaque elements. */
    private static final Set<String> PROJECT_SPECIFIC_ELEMENTS = new HashSet<>(Arrays.asList(
            "groupId", "artifactId", "version", "packaging", "parent", "profiles"));

    /** Profile elements compared by dedicated logic rather than as opaque elements. */
    private static final Set<String> PROFILE_SPECIFIC_ELEMENTS = new HashSet<>(Arrays.asList("id", "activation"));

    /** Elements shared by projects and profiles that are compared by dedicated logic. */
    private static final Set<String> BODY_ELEMENTS = new HashSet<>(Arrays.asList(
            "properties", "dependencies", "dependencyManagement", "build"));

    /** {@code <build>} children that change what is compiled or put on the classpath. */
    private static final Set<String> RESOURCE_BUILD_ELEMENTS = new HashSet<>(Arrays.asList(
            "sourceDirectory", "testSourceDirectory", "scriptSourceDirectory", "resources", "testResources",
            "filters"));

    /** {@code <build>} children that only name or default the build. */
    private static final Set<String> INERT_BUILD_ELEMENTS = new HashSet<>(Arrays.asList("finalName", "defaultGoal"));

    /** {@code <build>} children compared by dedicated logic. */
    private static final Set<String> PLUGIN_BUILD_ELEMENTS = new HashSet<>(Arrays.asList("plugins", "pluginManagement"));

    /** Suffix of a property change whose uses in this POM are classified where they are. */
    private static final String USED_HERE = " (its uses in this POM are classified separately)";

    private final Set<String> trackedLibraries;

    /**
     * Construct a classifier.
     *
     * @param trackedLibraryCoordinates the {@code groupId:artifactId} coordinates of the tracked
     *                                  libraries, whose version changes are inert; may be {@code null}.
     */
    public PomChangeClassifier(final Collection<String> trackedLibraryCoordinates) {
        this.trackedLibraries = trackedLibraryCoordinates == null
                ? Collections.emptySet() : new HashSet<>(trackedLibraryCoordinates);
    }

    /**
     * Classify the difference between two versions of one POM on its own, with no knowledge of
     * other POMs: reactor and parent checks use the single-file heuristics, and a changed property
     * in a POM that may be a parent is {@code OTHER}.
     *
     * @param oldPom the POM text before the change, or {@code null} when the POM was added.
     * @param newPom the POM text after the change, or {@code null} when the POM was deleted.
     * @return every change found, functional and inert; empty when the two versions are equivalent.
     */
    public PomChangeReport classify(final String oldPom, final String newPom) {
        return classify(oldPom, newPom, PomEnvironment.NONE);
    }

    /**
     * Classify the difference between two versions of one POM in the environment of the other
     * POMs in its repository: properties inherited from in-repo parents are resolved, the parent
     * and reactor checks use the real parent location and reactor modules, and a changed property
     * is classified by what it changes in the POM's child modules (see {@link PomEnvironment}).
     *
     * @param oldPom the POM text before the change, or {@code null} when the POM was added.
     * @param newPom the POM text after the change, or {@code null} when the POM was deleted.
     * @param environment the POM's surroundings; {@link PomEnvironment#NONE} for a single file.
     * @return every change found, functional and inert; empty when the two versions are equivalent.
     */
    PomChangeReport classify(final String oldPom, final String newPom, final PomEnvironment environment) {
        List<PomChange> changes = new ArrayList<>();
        if (oldPom == null && newPom == null) {
            return new PomChangeReport(changes);
        }
        if (oldPom == null || newPom == null) {
            changes.add(addedOrDeleted(oldPom == null, environment));
            return new PomChangeReport(changes);
        }

        Side oldSide;
        Side newSide;
        try {
            oldSide = new Side(PomModel.parse(oldPom), environment, false);
            newSide = new Side(PomModel.parse(newPom), environment, true);
        } catch (PomParseException e) {
            changes.add(PomChange.functional(PomChangeCategory.OTHER, "POM could not be parsed: " + e.getMessage()));
            return new PomChangeReport(changes);
        }

        compareCoordinates(oldSide, newSide, changes);
        compareParent(oldSide, newSide, changes);
        compareBody(oldSide.model.getProject(), newSide.model.getProject(), oldSide, newSide,
                oldSide.resolver, newSide.resolver, PROJECT_SPECIFIC_ELEMENTS, "", changes);
        compareProfiles(oldSide, newSide, changes);
        compareProperties(oldSide, newSide, changes);
        if (newSide.childrenAffected) {
            changes.addAll(environment.childChanges());
        }
        return new PomChangeReport(changes);
    }

    /**
     * Classify a POM that exists on only one side of the diff. It is inert when no other changed
     * POM lists it as a module or points its parent's relative path at it, because a build can only
     * pick it up through such a reference and that edit is classified in its own POM. It is
     * {@code OTHER} when one does, or when the environment cannot tell.
     *
     * @param added {@code true} for an added POM, {@code false} for a deleted one.
     * @param environment the POM's surroundings.
     * @return the change.
     */
    private static PomChange addedOrDeleted(final boolean added, final PomEnvironment environment) {
        String what = added ? "POM added" : "POM deleted";
        Boolean referenced = environment.referencedByChangedPoms(added);
        if (referenced == null) {
            return PomChange.functional(PomChangeCategory.OTHER, what);
        }
        String verb = added ? "lists" : "listed";
        if (referenced) {
            return PomChange.functional(PomChangeCategory.OTHER,
                    what + "; a changed POM " + verb + " it as a module or parent");
        }
        return PomChange.inert(what + "; no changed POM " + verb + " it as a module or parent");
    }

    /**
     * Compare the project's own coordinates and packaging. A version change is inert; a groupId,
     * artifactId or packaging change is {@code OTHER}.
     *
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @param changes the accumulator.
     */
    private void compareCoordinates(final Side oldSide, final Side newSide, final List<PomChange> changes) {
        if (!Objects.equals(oldSide.groupId + ":" + oldSide.artifactId, newSide.groupId + ":" + newSide.artifactId)) {
            changes.add(PomChange.functional(PomChangeCategory.OTHER, "project coordinates "
                    + oldSide.groupId + ":" + oldSide.artifactId + " -> " + newSide.groupId + ":" + newSide.artifactId));
        }
        if (!Objects.equals(oldSide.version, newSide.version)) {
            changes.add(PomChange.inert("project version " + oldSide.version + " -> " + newSide.version));
        }
        if (!oldSide.model.getPackaging().equals(newSide.model.getPackaging())) {
            changes.add(PomChange.functional(PomChangeCategory.OTHER, "packaging "
                    + oldSide.model.getPackaging() + " -> " + newSide.model.getPackaging()));
        }
    }

    /**
     * Compare the {@code <parent>}. A version change is inert when the parent is in the same
     * reactor (the project inherits its version, or moved in lockstep with the parent) and
     * {@code DEPENDENCY_VERSIONS} otherwise. Adding, removing or replacing the parent, or
     * changing its relative path, is {@code OTHER}.
     *
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @param changes the accumulator.
     */
    private void compareParent(final Side oldSide, final Side newSide, final List<PomChange> changes) {
        Element oldParent = oldSide.model.getParent();
        Element newParent = newSide.model.getParent();
        if (oldParent == null && newParent == null) {
            return;
        }
        if (oldParent == null || newParent == null) {
            changes.add(PomChange.functional(PomChangeCategory.OTHER, oldParent == null ? "parent added" : "parent removed"));
            return;
        }

        String oldCoordinates = parentCoordinates(oldParent, oldSide.resolver);
        String newCoordinates = parentCoordinates(newParent, newSide.resolver);
        if (!oldCoordinates.equals(newCoordinates)) {
            changes.add(PomChange.functional(PomChangeCategory.OTHER, "parent " + oldCoordinates + " -> " + newCoordinates));
            return;
        }
        if (!Objects.equals(PomXml.childText(oldParent, "relativePath"), PomXml.childText(newParent, "relativePath"))) {
            changes.add(PomChange.functional(PomChangeCategory.OTHER, "parent " + newCoordinates + " relativePath changed"));
        }

        String oldVersion = oldSide.resolver.resolve(PomXml.childText(oldParent, "version"));
        String newVersion = newSide.resolver.resolve(PomXml.childText(newParent, "version"));
        if (Objects.equals(oldVersion, newVersion)) {
            return;
        }
        String description = "parent " + newCoordinates + " version " + oldVersion + " -> " + newVersion;
        if (isReactorParent(oldSide, newSide, oldVersion, newVersion)) {
            changes.add(PomChange.inert("reactor " + description));
        } else {
            classifyExternalPomBump(false, newSide.resolver.resolve(PomXml.childText(newParent, "groupId")),
                    newSide.resolver.resolve(PomXml.childText(newParent, "artifactId")), oldVersion, newVersion,
                    "external " + description, newSide.environment, changes);
        }
    }

    /**
     * Check whether a parent whose version changed is part of the same reactor. With a known
     * environment, the parent is in the reactor when the new side's parent POM is in the
     * repository at the declared version. Without one, the single-file heuristic applies: the
     * project inherits its version from the parent, or moved in lockstep with it.
     *
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @param oldVersion the parent version before the change.
     * @param newVersion the parent version after the change.
     * @return {@code true} when the parent version change is inert.
     */
    private boolean isReactorParent(final Side oldSide, final Side newSide,
                                    final String oldVersion, final String newVersion) {
        PomEnvironment.ParentLocation location = newSide.environment.parentLocation(true);
        if (location != PomEnvironment.ParentLocation.UNKNOWN) {
            return location == PomEnvironment.ParentLocation.IN_REPO;
        }
        boolean inheritsVersion = newSide.model.getVersion() == null;
        boolean lockstep = Objects.equals(oldSide.version, oldVersion) && Objects.equals(newSide.version, newVersion);
        return inheritsVersion || lockstep;
    }

    /**
     * Build a parent's resolved {@code groupId:artifactId}.
     *
     * @param parent the {@code <parent>} element.
     * @param resolver the side's resolver.
     * @return the coordinates.
     */
    private String parentCoordinates(final Element parent, final PomPropertyResolver resolver) {
        return resolver.resolve(PomXml.childText(parent, "groupId")) + ":"
                + resolver.resolve(PomXml.childText(parent, "artifactId"));
    }

    /**
     * Compare the content a project and a profile share: dependencies, dependency management, the
     * build, and every other element (inert metadata or {@code OTHER}). Properties are compared
     * separately by {@link #compareProperties}.
     *
     * @param oldOwner the project or profile element before the change; {@code null} when absent.
     * @param newOwner the project or profile element after the change; {@code null} when absent.
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @param oldResolver the resolver for {@code oldOwner}.
     * @param newResolver the resolver for {@code newOwner}.
     * @param ownerSpecificElements elements of the owner compared elsewhere.
     * @param label suffix identifying the owner in descriptions, e.g. {@code " in profile ci"}.
     * @param changes the accumulator.
     */
    private void compareBody(final Element oldOwner, final Element newOwner, final Side oldSide, final Side newSide,
                             final PomPropertyResolver oldResolver, final PomPropertyResolver newResolver,
                             final Set<String> ownerSpecificElements, final String label,
                             final List<PomChange> changes) {
        compareDependencies(PomXml.child(oldOwner, "dependencies"), PomXml.child(newOwner, "dependencies"),
                false, oldSide, newSide, oldResolver, newResolver, label, changes);
        compareDependencies(PomXml.child(PomXml.child(oldOwner, "dependencyManagement"), "dependencies"),
                PomXml.child(PomXml.child(newOwner, "dependencyManagement"), "dependencies"),
                true, oldSide, newSide, oldResolver, newResolver, label, changes);
        compareBuild(PomXml.child(oldOwner, "build"), PomXml.child(newOwner, "build"),
                oldResolver, newResolver, label, changes);

        Map<String, String> oldElements = canonicalElements(oldOwner, oldResolver, ownerSpecificElements);
        Map<String, String> newElements = canonicalElements(newOwner, newResolver, ownerSpecificElements);
        for (String name : union(oldElements.keySet(), newElements.keySet())) {
            if (Objects.equals(oldElements.get(name), newElements.get(name))) {
                continue;
            }
            String description = "<" + name + "> changed" + label;
            changes.add(INERT_ELEMENTS.contains(name)
                    ? PomChange.inert(description)
                    : PomChange.functional(PomChangeCategory.OTHER, description));
        }
    }

    /**
     * Canonicalise an owner's elements that are not compared by dedicated logic, keyed by name
     * (repeated names are concatenated).
     *
     * @param owner the project or profile element; may be {@code null}.
     * @param resolver the owner's resolver.
     * @param ownerSpecificElements elements of the owner compared elsewhere.
     * @return the canonical forms by element name.
     */
    private Map<String, String> canonicalElements(final Element owner, final PomPropertyResolver resolver,
                                                  final Set<String> ownerSpecificElements) {
        Map<String, String> elements = new LinkedHashMap<>();
        for (Element child : PomXml.children(owner)) {
            String name = PomXml.name(child);
            if (BODY_ELEMENTS.contains(name) || ownerSpecificElements.contains(name)) {
                continue;
            }
            String form = PomXml.canonical(child, resolver);
            elements.merge(name, form, (first, second) -> first + second);
        }
        return elements;
    }

    /**
     * Compare a {@code <dependencies>} list, keyed by {@code groupId:artifactId:type:classifier}.
     *
     * <p>Added or removed dependencies are {@code DEPENDENCY_SET}. In dependency management an
     * added or removed entry pins or unpins a version, so it is {@code DEPENDENCY_VERSIONS} (inert
     * for tracked libraries and reactor modules). A resolved version change is inert for tracked
     * libraries and reactor modules and {@code DEPENDENCY_VERSIONS} otherwise. A scope, optional,
     * system path or exclusions change is {@code DEPENDENCY_SET}, tracked or not.
     *
     * @param oldList the list before the change; may be {@code null}.
     * @param newList the list after the change; may be {@code null}.
     * @param managed whether the list is inside {@code <dependencyManagement>}.
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @param oldResolver the resolver for the old list's owner.
     * @param newResolver the resolver for the new list's owner.
     * @param label suffix identifying the owner in descriptions.
     * @param changes the accumulator.
     */
    private void compareDependencies(final Element oldList, final Element newList, final boolean managed,
                                     final Side oldSide, final Side newSide,
                                     final PomPropertyResolver oldResolver, final PomPropertyResolver newResolver,
                                     final String label, final List<PomChange> changes) {
        Map<String, Dependency> oldDependencies = indexDependencies(oldList, oldResolver);
        Map<String, Dependency> newDependencies = indexDependencies(newList, newResolver);
        String kind = managed ? "managed dependency " : "dependency ";

        for (String key : union(oldDependencies.keySet(), newDependencies.keySet())) {
            Dependency before = oldDependencies.get(key);
            Dependency after = newDependencies.get(key);
            if (before == null || after == null) {
                Dependency present = before == null ? after : before;
                String description = (present.isBomImport() ? "BOM import " : kind) + present.displayName()
                        + (before == null ? " added" : " removed") + label;
                if (!managed) {
                    changes.add(PomChange.functional(PomChangeCategory.DEPENDENCY_SET, description));
                } else if (isTracked(present) || referencesProjectVersion(present.rawVersion)) {
                    changes.add(PomChange.inert(description));
                } else {
                    changes.add(PomChange.functional(PomChangeCategory.DEPENDENCY_VERSIONS, description));
                }
                continue;
            }

            if (!Objects.equals(before.version, after.version)) {
                classifyVersionChange(before, after, kind, oldSide, newSide, label, changes);
            }
            List<String> setChanges = new ArrayList<>();
            describeIfChanged("scope", before.scope, after.scope, setChanges);
            describeIfChanged("optional", before.optional, after.optional, setChanges);
            describeIfChanged("systemPath", before.systemPath, after.systemPath, setChanges);
            if (!before.exclusions.equals(after.exclusions)) {
                setChanges.add("exclusions");
            }
            if (!setChanges.isEmpty()) {
                changes.add(PomChange.functional(PomChangeCategory.DEPENDENCY_SET,
                        kind + after.displayName() + " " + String.join(", ", setChanges) + label));
            }
        }
    }

    /**
     * Classify a dependency whose resolved version changed: inert for tracked libraries and
     * reactor modules, {@code DEPENDENCY_VERSIONS} otherwise.
     *
     * @param before the dependency before the change.
     * @param after the dependency after the change.
     * @param kind {@code "dependency "} or {@code "managed dependency "}.
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @param label suffix identifying the owner in descriptions.
     * @param changes the accumulator.
     */
    private void classifyVersionChange(final Dependency before, final Dependency after, final String kind,
                                       final Side oldSide, final Side newSide, final String label,
                                       final List<PomChange> changes) {
        String versions = " version " + before.version + " -> " + after.version + label;
        if (isTracked(after)) {
            changes.add(PomChange.inert("tracked library " + after.displayName() + versions
                    + " (handled by library impact analysis)"));
        } else if (isReactorModule(before, after, oldSide, newSide)) {
            changes.add(PomChange.inert("reactor module " + after.displayName() + versions));
        } else if (after.isBomImport() && before.isBomImport()) {
            classifyExternalPomBump(true, after.groupId, after.artifactId, before.version, after.version,
                    "BOM import " + after.displayName() + versions, newSide.environment, changes);
        } else {
            changes.add(PomChange.functional(PomChangeCategory.DEPENDENCY_VERSIONS,
                    kind + after.displayName() + versions));
        }
    }

    /**
     * Classify a version bump of a POM outside the repository (an external parent or an imported
     * BOM) by what it changes. Both versions are read through the environment; when either is
     * unavailable the bump is {@code DEPENDENCY_VERSIONS}. Otherwise the two versions are
     * classified like any POM diff, and the bump itself is recorded as inert with the external
     * POM's functional changes listed after it, keeping their plugin and profile tags. For a BOM
     * only dependency changes count, since an import brings in only its dependency management.
     *
     * @param bom {@code true} for an imported BOM, {@code false} for an external parent.
     * @param groupId the external POM's groupId.
     * @param artifactId the external POM's artifactId.
     * @param oldVersion the version before the change.
     * @param newVersion the version after the change.
     * @param description the description of the bump.
     * @param environment the environment of the POM that declares the parent or import.
     * @param changes the accumulator.
     */
    private void classifyExternalPomBump(final boolean bom, final String groupId, final String artifactId,
                                         final String oldVersion, final String newVersion, final String description,
                                         final PomEnvironment environment, final List<PomChange> changes) {
        String oldPom = environment.externalPom(groupId, artifactId, oldVersion);
        String newPom = environment.externalPom(groupId, artifactId, newVersion);
        if (oldPom == null || newPom == null) {
            changes.add(PomChange.functional(PomChangeCategory.DEPENDENCY_VERSIONS, description));
            return;
        }

        PomEnvironment externalEnvironment = bom
                ? environment.importedBomEnvironment(oldPom, newPom)
                : environment.externalParentEnvironment(oldPom, newPom);
        List<PomChange> relevant = new ArrayList<>();
        for (PomChange change : classify(oldPom, newPom, externalEnvironment).getChanges()) {
            if (!change.isInert() && (!bom || change.getCategory() == PomChangeCategory.DEPENDENCY_VERSIONS
                    || change.getCategory() == PomChangeCategory.DEPENDENCY_SET)) {
                relevant.add(change);
            }
        }
        changes.add(PomChange.inert(description + (relevant.isEmpty()
                ? " (no functional changes in it)" : " (its changes are listed separately)")));
        String label = " (in " + (bom ? "BOM " : "parent ") + groupId + ":" + artifactId + ":" + newVersion + ")";
        for (PomChange change : relevant) {
            changes.add(change.withDescriptionSuffix(label));
        }
    }

    /**
     * Check whether a dependency is another module of the same reactor. With a known environment
     * this is reactor membership of its {@code groupId:artifactId}. Without one, the single-file
     * heuristic applies: its version is the project's own version reference, or it shares the
     * project's groupId and its version moved in lockstep with a project version change.
     *
     * @param before the dependency before the change.
     * @param after the dependency after the change.
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @return {@code true} when the dependency is treated as a reactor module.
     */
    private boolean isReactorModule(final Dependency before, final Dependency after,
                                    final Side oldSide, final Side newSide) {
        Set<String> reactorModules = newSide.environment.reactorModules();
        if (reactorModules != null) {
            return reactorModules.contains(before.groupId + ":" + before.artifactId)
                    || reactorModules.contains(after.groupId + ":" + after.artifactId);
        }
        if (referencesProjectVersion(before.rawVersion) && referencesProjectVersion(after.rawVersion)) {
            return true;
        }
        return Objects.equals(after.groupId, newSide.groupId)
                && !Objects.equals(oldSide.version, newSide.version)
                && Objects.equals(before.version, oldSide.version)
                && Objects.equals(after.version, newSide.version);
    }

    /**
     * @param rawVersion a version as written; may be {@code null}.
     * @return {@code true} when it is exactly a reference to the project's own version.
     */
    private boolean referencesProjectVersion(final String rawVersion) {
        return "${project.version}".equals(rawVersion) || "${version}".equals(rawVersion)
                || "${pom.version}".equals(rawVersion);
    }

    /**
     * @param dependency a dependency.
     * @return {@code true} when its {@code groupId:artifactId} is a tracked library.
     */
    private boolean isTracked(final Dependency dependency) {
        return trackedLibraries.contains(dependency.groupId + ":" + dependency.artifactId);
    }

    /**
     * Index a {@code <dependencies>} list by {@code groupId:artifactId:type:classifier}.
     *
     * @param list the list; may be {@code null}.
     * @param resolver the owner's resolver.
     * @return the dependencies by key, in document order.
     */
    private Map<String, Dependency> indexDependencies(final Element list, final PomPropertyResolver resolver) {
        Map<String, Dependency> dependencies = new LinkedHashMap<>();
        for (Element element : PomXml.children(list, "dependency")) {
            Dependency dependency = new Dependency(element, resolver);
            dependencies.put(dependency.key(), dependency);
        }
        return dependencies;
    }

    /**
     * Compare a {@code <build>}: plugins, plugin management, and the other build elements
     * (resources and source layout are {@code RESOURCES}, the final name and default goal are
     * inert, anything else is {@code OTHER}).
     *
     * @param oldBuild the build before the change; may be {@code null}.
     * @param newBuild the build after the change; may be {@code null}.
     * @param oldResolver the resolver for the old build's owner.
     * @param newResolver the resolver for the new build's owner.
     * @param label suffix identifying the owner in descriptions.
     * @param changes the accumulator.
     */
    private void compareBuild(final Element oldBuild, final Element newBuild,
                              final PomPropertyResolver oldResolver, final PomPropertyResolver newResolver,
                              final String label, final List<PomChange> changes) {
        comparePlugins(PomXml.child(oldBuild, "plugins"), PomXml.child(newBuild, "plugins"),
                "plugin ", oldResolver, newResolver, label, changes);
        comparePlugins(PomXml.child(PomXml.child(oldBuild, "pluginManagement"), "plugins"),
                PomXml.child(PomXml.child(newBuild, "pluginManagement"), "plugins"),
                "managed plugin ", oldResolver, newResolver, label, changes);

        Map<String, String> oldElements = canonicalElements(oldBuild, oldResolver, PLUGIN_BUILD_ELEMENTS);
        Map<String, String> newElements = canonicalElements(newBuild, newResolver, PLUGIN_BUILD_ELEMENTS);
        for (String name : union(oldElements.keySet(), newElements.keySet())) {
            if (Objects.equals(oldElements.get(name), newElements.get(name))) {
                continue;
            }
            String description = "build <" + name + "> changed" + label;
            if (INERT_BUILD_ELEMENTS.contains(name)) {
                changes.add(PomChange.inert(description));
            } else if (RESOURCE_BUILD_ELEMENTS.contains(name)) {
                changes.add(PomChange.functional(PomChangeCategory.RESOURCES, description));
            } else {
                changes.add(PomChange.functional(PomChangeCategory.OTHER, description));
            }
        }
    }

    /**
     * Compare a {@code <plugins>} list keyed by {@code groupId:artifactId}. Any difference in a
     * plugin (version, configuration, executions, dependencies, ...) takes the plugin's built-in
     * category; inert plugins and Tia's own plugin produce inert changes. Each change is tagged
     * with the plugin's key (see {@link PomChange#getPlugin()}).
     *
     * @param oldList the list before the change; may be {@code null}.
     * @param newList the list after the change; may be {@code null}.
     * @param kind {@code "plugin "} or {@code "managed plugin "}.
     * @param oldResolver the resolver for the old list's owner.
     * @param newResolver the resolver for the new list's owner.
     * @param label suffix identifying the owner in descriptions.
     * @param changes the accumulator.
     */
    private void comparePlugins(final Element oldList, final Element newList, final String kind,
                                final PomPropertyResolver oldResolver, final PomPropertyResolver newResolver,
                                final String label, final List<PomChange> changes) {
        Map<String, Element> oldPlugins = indexPlugins(oldList, oldResolver);
        Map<String, Element> newPlugins = indexPlugins(newList, newResolver);
        for (String key : union(oldPlugins.keySet(), newPlugins.keySet())) {
            Element before = oldPlugins.get(key);
            Element after = newPlugins.get(key);
            String description;
            if (before == null || after == null) {
                description = kind + key + (before == null ? " added" : " removed") + label;
            } else {
                List<String> aspects = changedPluginAspects(before, after, oldResolver, newResolver);
                if (aspects.isEmpty()) {
                    continue;
                }
                description = kind + key + " changed: " + String.join(", ", aspects) + label;
            }
            PomChangeCategory category = PomBuiltInCategories.pluginCategory(key.substring(key.indexOf(':') + 1));
            PomChange change = category == null ? PomChange.inert(description) : PomChange.functional(category, description);
            changes.add(change.inPlugin(key));
        }
    }

    /**
     * Name the parts of a plugin that differ, with old and new values for the version.
     *
     * @param before the plugin before the change.
     * @param after the plugin after the change.
     * @param oldResolver the resolver for the old plugin's owner.
     * @param newResolver the resolver for the new plugin's owner.
     * @return the differing parts; empty when the plugin is unchanged.
     */
    private List<String> changedPluginAspects(final Element before, final Element after,
                                              final PomPropertyResolver oldResolver,
                                              final PomPropertyResolver newResolver) {
        Set<String> ignored = new HashSet<>(Arrays.asList("groupId", "artifactId"));
        Map<String, String> oldParts = canonicalElements(before, oldResolver, ignored);
        Map<String, String> newParts = canonicalElements(after, newResolver, ignored);
        List<String> aspects = new ArrayList<>();
        for (String name : union(oldParts.keySet(), newParts.keySet())) {
            if (Objects.equals(oldParts.get(name), newParts.get(name))) {
                continue;
            }
            if (name.equals("version")) {
                aspects.add("version " + oldResolver.resolve(PomXml.childText(before, "version"))
                        + " -> " + newResolver.resolve(PomXml.childText(after, "version")));
            } else {
                aspects.add(name);
            }
        }
        return aspects;
    }

    /**
     * Index a {@code <plugins>} list by resolved {@code groupId:artifactId}, defaulting the
     * groupId to {@code org.apache.maven.plugins}.
     *
     * @param list the list; may be {@code null}.
     * @param resolver the owner's resolver.
     * @return the plugins by key, in document order.
     */
    private Map<String, Element> indexPlugins(final Element list, final PomPropertyResolver resolver) {
        Map<String, Element> plugins = new LinkedHashMap<>();
        for (Element plugin : PomXml.children(list, "plugin")) {
            String groupId = resolver.resolve(PomXml.childText(plugin, "groupId"));
            String artifactId = resolver.resolve(PomXml.childText(plugin, "artifactId"));
            plugins.put((groupId == null || groupId.isEmpty() ? DEFAULT_PLUGIN_GROUP_ID : groupId) + ":" + artifactId,
                    plugin);
        }
        return plugins;
    }

    /**
     * Compare profiles by id. Each profile's content is compared like the project's, with the
     * profile's properties overlaid; an added or removed profile is compared against an empty one,
     * so it counts by what it contains. An activation change on a profile present on both sides is
     * {@code OTHER}. Each change is tagged with the profile id (see {@link PomChange#getProfile()}).
     *
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @param changes the accumulator.
     */
    private void compareProfiles(final Side oldSide, final Side newSide, final List<PomChange> changes) {
        Map<String, Element> oldProfiles = oldSide.model.getProfiles();
        Map<String, Element> newProfiles = newSide.model.getProfiles();
        for (String id : union(oldProfiles.keySet(), newProfiles.keySet())) {
            Element before = oldProfiles.get(id);
            Element after = newProfiles.get(id);
            String label = " in profile " + id;
            List<PomChange> profileChanges = new ArrayList<>();
            if (before != null && after != null) {
                String oldActivation = PomXml.canonical(PomXml.child(before, "activation"), oldSide.resolver);
                String newActivation = PomXml.canonical(PomXml.child(after, "activation"), newSide.resolver);
                if (!oldActivation.equals(newActivation)) {
                    profileChanges.add(PomChange.functional(PomChangeCategory.OTHER, "activation changed" + label));
                }
            }
            compareBody(before, after, oldSide, newSide,
                    PomPropertyResolver.forProfile(oldSide.model, before, oldSide.inherited),
                    PomPropertyResolver.forProfile(newSide.model, after, newSide.inherited),
                    PROFILE_SPECIFIC_ELEMENTS, label, profileChanges);
            for (PomChange change : profileChanges) {
                changes.add(change.inProfile(id));
            }
        }
    }

    /**
     * Compare property values, at the top level and in each profile, by resolved value. The
     * effect of a change on the values that reference the property is classified where those
     * values are compared; this records the property itself:
     * <ul>
     *   <li>inert for {@code tia*} and the publish / report / quality switches;</li>
     *   <li>{@code COMPILER} or {@code TEST_RUNNER} for properties those plugins read implicitly;</li>
     *   <li>inert when the project's own or parent version references it (e.g. a CI-friendly
     *       {@code ${revision}}): it is the project version, which children use the same way;</li>
     *   <li>inert when this POM or its child modules reference it: its uses are classified where
     *       they are (in a child via {@link PomEnvironment#childChanges()});</li>
     *   <li>{@code OTHER} when nothing references it (a plugin may read it implicitly), or when
     *       the POM may be a parent and its children are unknown.</li>
     * </ul>
     *
     * <p>When a changed property is referenced by a child module, {@code newSide.childrenAffected}
     * is set so the children's resulting changes are added. Each change to a profile's property
     * is tagged with the profile id (see {@link PomChange#getProfile()}).
     *
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @param changes the accumulator.
     */
    private void compareProperties(final Side oldSide, final Side newSide, final List<PomChange> changes) {
        compareOwnerProperties(oldSide.model.getProject(), newSide.model.getProject(),
                oldSide.resolver, newSide.resolver, oldSide, newSide, "", changes);
        Map<String, Element> oldProfiles = oldSide.model.getProfiles();
        Map<String, Element> newProfiles = newSide.model.getProfiles();
        for (String id : union(oldProfiles.keySet(), newProfiles.keySet())) {
            Element before = oldProfiles.get(id);
            Element after = newProfiles.get(id);
            List<PomChange> profileChanges = new ArrayList<>();
            compareOwnerProperties(before, after,
                    PomPropertyResolver.forProfile(oldSide.model, before, oldSide.inherited),
                    PomPropertyResolver.forProfile(newSide.model, after, newSide.inherited),
                    oldSide, newSide, " in profile " + id, profileChanges);
            for (PomChange change : profileChanges) {
                changes.add(change.inProfile(id));
            }
        }
    }

    /**
     * Compare the properties declared by one project or profile element.
     *
     * @param oldOwner the owner before the change; may be {@code null}.
     * @param newOwner the owner after the change; may be {@code null}.
     * @param oldResolver the resolver for {@code oldOwner}.
     * @param newResolver the resolver for {@code newOwner}.
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @param label suffix identifying the owner in descriptions.
     * @param changes the accumulator.
     */
    private void compareOwnerProperties(final Element oldOwner, final Element newOwner,
                                        final PomPropertyResolver oldResolver, final PomPropertyResolver newResolver,
                                        final Side oldSide, final Side newSide, final String label,
                                        final List<PomChange> changes) {
        Map<String, String> oldProperties = PomModel.properties(oldOwner);
        Map<String, String> newProperties = PomModel.properties(newOwner);
        for (String name : union(oldProperties.keySet(), newProperties.keySet())) {
            String oldValue = oldProperties.containsKey(name) ? oldResolver.resolve(oldProperties.get(name)) : null;
            String newValue = newProperties.containsKey(name) ? newResolver.resolve(newProperties.get(name)) : null;
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            String description = "property " + name + " " + describeValue(oldValue) + " -> " + describeValue(newValue)
                    + label;
            changes.add(classifyProperty(name, description, oldSide, newSide));
        }
    }

    /**
     * Classify a changed property; see {@link #compareProperties}. Sets
     * {@code newSide.childrenAffected} when a child module references it.
     *
     * @param name the property name.
     * @param description the change description.
     * @param oldSide the POM before the change.
     * @param newSide the POM after the change.
     * @return the classified change.
     */
    private PomChange classifyProperty(final String name, final String description,
                                       final Side oldSide, final Side newSide) {
        if (PomBuiltInCategories.isInertProperty(name)) {
            return PomChange.inert(description);
        }
        PomChangeCategory implicitCategory = PomBuiltInCategories.implicitPropertyCategory(name);
        if (implicitCategory != null) {
            return PomChange.functional(implicitCategory, description);
        }
        if (oldSide.isVersionProperty(name) || newSide.isVersionProperty(name)) {
            return PomChange.inert(description + " (project version)");
        }
        boolean referencedHere = oldSide.model.references(name) || newSide.model.references(name);
        if (!oldSide.model.mayHaveChildren() && !newSide.model.mayHaveChildren()) {
            return referencedHere
                    ? PomChange.inert(description + USED_HERE)
                    : PomChange.functional(PomChangeCategory.OTHER, description + " (not referenced in this POM)");
        }
        Set<String> childReferences = newSide.environment.childPropertyReferences();
        if (childReferences == null) {
            return PomChange.functional(PomChangeCategory.OTHER, description
                    + (referencedHere ? " (child modules may reference it)" : " (not referenced in this POM)"));
        }
        if (childReferences.contains(name)) {
            newSide.childrenAffected = true;
            return PomChange.inert(description + " (its uses in this POM and its modules are classified separately)");
        }
        return referencedHere
                ? PomChange.inert(description + USED_HERE)
                : PomChange.functional(PomChangeCategory.OTHER, description + " (not referenced in this POM or its modules)");
    }

    /**
     * Add {@code name old -> new} to the list when the values differ.
     *
     * @param name the field name.
     * @param oldValue the value before the change; may be {@code null}.
     * @param newValue the value after the change; may be {@code null}.
     * @param descriptions the accumulator.
     */
    private void describeIfChanged(final String name, final String oldValue, final String newValue,
                                   final List<String> descriptions) {
        if (!Objects.equals(oldValue, newValue)) {
            descriptions.add(name + " " + describeValue(oldValue) + " -> " + describeValue(newValue));
        }
    }

    /**
     * @param value a value; may be {@code null}.
     * @return the value, or {@code (none)} for {@code null}.
     */
    private String describeValue(final String value) {
        return value == null ? "(none)" : value;
    }

    /**
     * @param first the first key set.
     * @param second the second key set.
     * @return the union, first set's order first.
     */
    private static Set<String> union(final Set<String> first, final Set<String> second) {
        Set<String> union = new LinkedHashSet<>(first);
        union.addAll(second);
        return union;
    }

    /**
     * One version of the POM with the values every comparison needs.
     */
    private static final class Side {
        private final PomModel model;
        private final PomEnvironment environment;
        private final Map<String, String> inherited;
        private final PomPropertyResolver resolver;
        private final String groupId;
        /** Set when a changed property is used by a child module, so the children's changes are added. */
        private boolean childrenAffected;
        private final String artifactId;
        private final String version;

        /**
         * Resolve the project's effective coordinates, with the properties inherited on this side.
         *
         * @param model the parsed POM.
         * @param environment the POM's surroundings.
         * @param newSide {@code true} for the "after" side of the diff.
         */
        private Side(final PomModel model, final PomEnvironment environment, final boolean newSide) {
            this.model = model;
            this.environment = environment;
            this.inherited = environment.inheritedProperties(newSide);
            this.resolver = PomPropertyResolver.forProject(model, inherited);
            this.groupId = resolver.resolve("${project.groupId}");
            this.artifactId = resolver.resolve("${project.artifactId}");
            this.version = resolver.resolve("${project.version}");
        }

        /**
         * Check whether the project's own {@code <version>} or its parent's version references the
         * property, i.e. the property is (part of) the project version.
         *
         * @param name the property name.
         * @return {@code true} when either version references {@code ${name}}.
         */
        private boolean isVersionProperty(final String name) {
            return PomPropertyResolver.references(model.getVersion()).contains(name)
                    || PomPropertyResolver.references(model.getParentVersion()).contains(name);
        }
    }

    /**
     * A {@code <dependency>} with its fields resolved and defaulted.
     */
    private static final class Dependency {
        private final String groupId;
        private final String artifactId;
        private final String type;
        private final String classifier;
        private final String rawVersion;
        private final String version;
        private final String scope;
        private final String optional;
        private final String systemPath;
        private final Set<String> exclusions;

        /**
         * Read and resolve a dependency element.
         *
         * @param element the {@code <dependency>} element.
         * @param resolver the owner's resolver.
         */
        private Dependency(final Element element, final PomPropertyResolver resolver) {
            this.groupId = resolver.resolve(PomXml.childText(element, "groupId"));
            this.artifactId = resolver.resolve(PomXml.childText(element, "artifactId"));
            this.type = defaultIfBlank(resolver.resolve(PomXml.childText(element, "type")), "jar");
            this.classifier = defaultIfBlank(resolver.resolve(PomXml.childText(element, "classifier")), "");
            this.rawVersion = PomXml.childText(element, "version");
            this.version = resolver.resolve(rawVersion);
            this.scope = defaultIfBlank(resolver.resolve(PomXml.childText(element, "scope")), "compile");
            this.optional = defaultIfBlank(resolver.resolve(PomXml.childText(element, "optional")), "false");
            this.systemPath = resolver.resolve(PomXml.childText(element, "systemPath"));
            this.exclusions = new HashSet<>();
            for (Element exclusion : PomXml.children(PomXml.child(element, "exclusions"), "exclusion")) {
                exclusions.add(resolver.resolve(PomXml.childText(exclusion, "groupId")) + ":"
                        + resolver.resolve(PomXml.childText(exclusion, "artifactId")));
            }
        }

        /**
         * @return the identity key {@code groupId:artifactId:type:classifier}.
         */
        private String key() {
            return groupId + ":" + artifactId + ":" + type + ":" + classifier;
        }

        /**
         * @return {@code groupId:artifactId}, plus type and classifier when not the defaults.
         */
        private String displayName() {
            String name = groupId + ":" + artifactId;
            if (!"jar".equals(type) || !classifier.isEmpty()) {
                name += ":" + type + (classifier.isEmpty() ? "" : ":" + classifier);
            }
            return name;
        }

        /**
         * @return {@code true} for a BOM import ({@code type=pom}, {@code scope=import}).
         */
        private boolean isBomImport() {
            return "pom".equals(type) && "import".equals(scope);
        }

        /**
         * @param value a value; may be {@code null}.
         * @param defaultValue the default.
         * @return the value, or the default when blank.
         */
        private static String defaultIfBlank(final String value, final String defaultValue) {
            return value == null || value.isEmpty() ? defaultValue : value;
        }
    }
}
