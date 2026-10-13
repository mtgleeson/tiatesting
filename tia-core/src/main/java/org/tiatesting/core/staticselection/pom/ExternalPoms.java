package org.tiatesting.core.staticselection.pom;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Caching access to external POMs for one test selection run, plus the properties a POM inherits
 * from its chain of external parents. Each coordinate is read from the {@link ExternalPomSource}
 * at most once; unparseable POMs are treated as missing.
 */
final class ExternalPoms {

    /** Bounds external parent chains and nested BOM imports. */
    static final int MAX_DEPTH = 10;

    private final ExternalPomSource source;
    private final Map<String, String> pomsByCoordinates = new HashMap<>();
    private final Map<String, Map<String, String>> propertiesByCoordinates = new HashMap<>();

    /**
     * Construct the cache.
     *
     * @param source the source to read from; {@code null} when external POMs are not available.
     */
    ExternalPoms(final ExternalPomSource source) {
        this.source = source;
    }

    /**
     * Read an external POM.
     *
     * @param groupId the groupId; may be {@code null}.
     * @param artifactId the artifactId; may be {@code null}.
     * @param version the version; may be {@code null}.
     * @return the POM text, or {@code null} when there is no source, a coordinate is missing or
     *         unresolved, or the POM is not available.
     */
    String readPom(final String groupId, final String artifactId, final String version) {
        if (source == null || isMissing(groupId) || isMissing(artifactId) || isMissing(version)) {
            return null;
        }
        String coordinates = groupId + ":" + artifactId + ":" + version;
        if (!pomsByCoordinates.containsKey(coordinates)) {
            pomsByCoordinates.put(coordinates, source.readPom(groupId, artifactId, version));
        }
        return pomsByCoordinates.get(coordinates);
    }

    /**
     * The properties a POM inherits from its external parents: the parent's own properties over
     * those of its parents, nearest winning. Parent coordinates are resolved against the POM's
     * own properties.
     *
     * @param model the POM whose parent is external; may be {@code null}.
     * @param depth how many external parents have been followed so far.
     * @return the inherited properties, as written; empty when there is no readable external parent.
     */
    Map<String, String> inheritedProperties(final PomModel model, final int depth) {
        if (model == null || model.getParent() == null || depth >= MAX_DEPTH) {
            return Collections.emptyMap();
        }
        PomPropertyResolver resolver = PomPropertyResolver.forProject(model, Collections.<String, String>emptyMap());
        return propertiesFromParent(resolver.resolve(model.getParentGroupId()),
                resolver.resolve(PomXml.childText(model.getParent(), "artifactId")),
                resolver.resolve(model.getParentVersion()), depth);
    }

    /**
     * The properties defined by an external parent and its own external parents, nearest winning.
     * Cached per coordinate, so a large parent chain is parsed once per run.
     *
     * @param groupId the parent groupId.
     * @param artifactId the parent artifactId.
     * @param version the parent version.
     * @param depth how many external parents have been followed so far.
     * @return the properties, as written; empty when the parent cannot be read or parsed.
     */
    private Map<String, String> propertiesFromParent(final String groupId, final String artifactId,
                                                     final String version, final int depth) {
        String coordinates = groupId + ":" + artifactId + ":" + version;
        Map<String, String> cached = propertiesByCoordinates.get(coordinates);
        if (cached != null) {
            return cached;
        }
        Map<String, String> properties = Collections.emptyMap();
        String parentPom = readPom(groupId, artifactId, version);
        if (parentPom != null) {
            try {
                PomModel parent = PomModel.parse(parentPom);
                properties = new HashMap<>(inheritedProperties(parent, depth + 1));
                properties.putAll(PomModel.properties(parent.getProject()));
            } catch (PomParseException e) {
                properties = Collections.emptyMap();
            }
        }
        propertiesByCoordinates.put(coordinates, properties);
        return properties;
    }

    /**
     * @param value a coordinate part.
     * @return {@code true} when it is absent or still contains an unresolved property reference.
     */
    private static boolean isMissing(final String value) {
        return value == null || value.isEmpty() || value.contains("${");
    }
}
