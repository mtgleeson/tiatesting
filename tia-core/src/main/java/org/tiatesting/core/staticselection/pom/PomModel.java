package org.tiatesting.core.staticselection.pom;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed POM: the {@code <project>} element plus the handful of values the classifier needs
 * up front (own and parent coordinates, packaging, profiles by id, every property reference).
 * Values are kept as written; resolution happens in {@link PomPropertyResolver}.
 */
final class PomModel {

    private static final String DEFAULT_PACKAGING = "jar";

    private final Element project;
    private final Element parent;
    private final Map<String, Element> profiles;
    private final Set<String> propertyReferences;

    /**
     * Index a parsed {@code <project>} element.
     *
     * @param project the root element.
     */
    private PomModel(final Element project) {
        this.project = project;
        this.parent = PomXml.child(project, "parent");
        this.profiles = indexProfiles(project);
        this.propertyReferences = collectPropertyReferences(project);
    }

    /**
     * Parse POM text.
     *
     * @param xml the POM text.
     * @return the model.
     * @throws PomParseException if the text is not well-formed XML or its root is not {@code <project>}.
     */
    static PomModel parse(final String xml) {
        Document document = PomXml.parse(xml);
        Element root = document.getDocumentElement();
        if (root == null || !PomXml.name(root).equals("project")) {
            throw new PomParseException("Not a POM: the root element is not <project>", null);
        }
        return new PomModel(root);
    }

    /**
     * Index profiles by id. A profile with no id is keyed by its position so it still compares
     * against the profile at the same position on the other side.
     *
     * @param project the root element.
     * @return the profiles by id, in document order.
     */
    private static Map<String, Element> indexProfiles(final Element project) {
        Map<String, Element> byId = new LinkedHashMap<>();
        List<Element> profileElements = PomXml.children(PomXml.child(project, "profiles"), "profile");
        for (int i = 0; i < profileElements.size(); i++) {
            Element profile = profileElements.get(i);
            String id = PomXml.childText(profile, "id");
            byId.put(id != null && !id.isEmpty() ? id : "#" + (i + 1), profile);
        }
        return byId;
    }

    /**
     * Collect the name of every property referenced anywhere in the POM, including from other
     * property values.
     *
     * @param project the root element.
     * @return the referenced property names.
     */
    private static Set<String> collectPropertyReferences(final Element project) {
        List<String> values = new ArrayList<>();
        PomXml.collectValues(project, values);
        Set<String> references = new HashSet<>();
        for (String value : values) {
            references.addAll(PomPropertyResolver.references(value));
        }
        return references;
    }

    /**
     * Read the {@code <properties>} of a project or profile element.
     *
     * @param owner the project or profile element; may be {@code null}.
     * @return the raw property values by name, in document order; empty when there are none.
     */
    static Map<String, String> properties(final Element owner) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Element property : PomXml.children(PomXml.child(owner, "properties"))) {
            properties.put(PomXml.name(property), PomXml.text(property));
        }
        return properties;
    }

    /**
     * @return the {@code <project>} element.
     */
    Element getProject() {
        return project;
    }

    /**
     * @return the {@code <parent>} element, or {@code null} when the POM has no parent.
     */
    Element getParent() {
        return parent;
    }

    /**
     * @return the POM's own {@code <groupId>} as written, or {@code null} when inherited.
     */
    String getGroupId() {
        return PomXml.childText(project, "groupId");
    }

    /**
     * @return the POM's own {@code <artifactId>} as written.
     */
    String getArtifactId() {
        return PomXml.childText(project, "artifactId");
    }

    /**
     * @return the POM's own {@code <version>} as written, or {@code null} when inherited.
     */
    String getVersion() {
        return PomXml.childText(project, "version");
    }

    /**
     * @return the POM's {@code <packaging>}, defaulting to {@code jar}.
     */
    String getPackaging() {
        String packaging = PomXml.childText(project, "packaging");
        return packaging != null && !packaging.isEmpty() ? packaging : DEFAULT_PACKAGING;
    }

    /**
     * @return the parent's {@code <groupId>} as written, or {@code null} when there is no parent.
     */
    String getParentGroupId() {
        return PomXml.childText(parent, "groupId");
    }

    /**
     * @return the parent's {@code <version>} as written, or {@code null} when there is no parent.
     */
    String getParentVersion() {
        return PomXml.childText(parent, "version");
    }

    /**
     * @return the profiles by id (or by position, for a profile with no id).
     */
    Map<String, Element> getProfiles() {
        return profiles;
    }

    /**
     * @param propertyName a property name.
     * @return {@code true} when the POM references {@code ${propertyName}} anywhere.
     */
    boolean references(final String propertyName) {
        return propertyReferences.contains(propertyName);
    }

    /**
     * Whether other POMs may inherit from this one: it is {@code pom}-packaged or aggregates
     * modules. Its properties can then be consumed by POMs this classifier does not see.
     *
     * @return {@code true} when the POM may be a parent.
     */
    boolean mayHaveChildren() {
        if ("pom".equals(getPackaging()) || PomXml.child(project, "modules") != null) {
            return true;
        }
        for (Element profile : profiles.values()) {
            if (PomXml.child(profile, "modules") != null) {
                return true;
            }
        }
        return false;
    }
}
