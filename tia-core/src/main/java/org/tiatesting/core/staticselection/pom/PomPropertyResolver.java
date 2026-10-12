package org.tiatesting.core.staticselection.pom;

import org.w3c.dom.Element;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${...}} property references in POM values against the properties inherited from
 * in-repo parents, the POM's own properties, and the {@code project.*} built-ins (groupId,
 * artifactId and version, falling back to the parent's). References to anything else
 * (environment, settings, properties of parents outside the repository) are left as written, so
 * two versions of a value compare by their raw text.
 */
final class PomPropertyResolver {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    /** Bounds nested resolution so a reference cycle cannot loop forever. */
    private static final int MAX_DEPTH = 10;

    private final Map<String, String> values;

    /**
     * Construct a resolver over a fixed set of property values.
     *
     * @param values the property values by name; values may themselves contain references.
     */
    private PomPropertyResolver(final Map<String, String> values) {
        this.values = values;
    }

    /**
     * Build the resolver for the POM's top level: the properties inherited from in-repo parents,
     * overridden by its own {@code <properties>}, plus the {@code project.*} built-ins.
     *
     * @param model the POM.
     * @param inherited the properties inherited from in-repo parents, as written; may be empty.
     * @return the resolver.
     */
    static PomPropertyResolver forProject(final PomModel model, final Map<String, String> inherited) {
        return new PomPropertyResolver(projectValues(model, inherited));
    }

    /**
     * Build the resolver for a profile: the top-level values overlaid with the profile's own
     * {@code <properties>}, as they would be when the profile is active.
     *
     * @param model the POM.
     * @param profile the profile element; {@code null} gives the top-level resolver.
     * @param inherited the properties inherited from in-repo parents, as written; may be empty.
     * @return the resolver.
     */
    static PomPropertyResolver forProfile(final PomModel model, final Element profile,
                                          final Map<String, String> inherited) {
        Map<String, String> values = projectValues(model, inherited);
        values.putAll(PomModel.properties(profile));
        return new PomPropertyResolver(values);
    }

    /**
     * Collect the inherited and top-level property values and the built-ins. The POM's own
     * properties override inherited ones, and built-ins are added last so a POM cannot shadow
     * them, matching Maven.
     *
     * @param model the POM.
     * @param inherited the properties inherited from in-repo parents, as written.
     * @return a mutable map of values by property name.
     */
    private static Map<String, String> projectValues(final PomModel model, final Map<String, String> inherited) {
        Map<String, String> values = new HashMap<>(inherited);
        values.putAll(PomModel.properties(model.getProject()));
        putBuiltIn(values, "groupId", model.getGroupId() != null ? model.getGroupId() : model.getParentGroupId());
        putBuiltIn(values, "artifactId", model.getArtifactId());
        putBuiltIn(values, "version", model.getVersion() != null ? model.getVersion() : model.getParentVersion());
        putBuiltIn(values, "parent.groupId", model.getParentGroupId());
        putBuiltIn(values, "parent.version", model.getParentVersion());
        return values;
    }

    /**
     * Register a built-in under its {@code project.}, {@code pom.} and bare names.
     *
     * @param values the accumulator.
     * @param name the built-in's name without prefix.
     * @param value the value; skipped when {@code null}.
     */
    private static void putBuiltIn(final Map<String, String> values, final String name, final String value) {
        if (value == null) {
            return;
        }
        values.put("project." + name, value);
        values.put("pom." + name, value);
        if (!name.startsWith("parent.")) {
            values.put(name, value);
        }
    }

    /**
     * Resolve every known reference in the value, repeatedly, so a property defined in terms of
     * another resolves fully.
     *
     * @param raw the value as written; may be {@code null}.
     * @return the resolved value, or {@code null} for {@code null}.
     */
    String resolve(final String raw) {
        if (raw == null || raw.indexOf("${") < 0) {
            return raw;
        }
        String current = raw;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Matcher matcher = REFERENCE.matcher(current);
            StringBuffer resolved = new StringBuffer();
            boolean replaced = false;
            while (matcher.find()) {
                String value = values.get(matcher.group(1));
                if (value != null) {
                    replaced = true;
                    matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
                } else {
                    matcher.appendReplacement(resolved, Matcher.quoteReplacement(matcher.group()));
                }
            }
            matcher.appendTail(resolved);
            current = resolved.toString();
            if (!replaced) {
                break;
            }
        }
        return current;
    }

    /**
     * The names of the properties a value references directly.
     *
     * @param value the value as written; may be {@code null}.
     * @return the referenced property names, in order of appearance.
     */
    static Set<String> references(final String value) {
        Set<String> names = new LinkedHashSet<>();
        if (value == null) {
            return names;
        }
        Matcher matcher = REFERENCE.matcher(value);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
