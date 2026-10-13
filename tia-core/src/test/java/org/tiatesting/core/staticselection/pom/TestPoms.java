package org.tiatesting.core.staticselection.pom;

/**
 * Builders for small POM texts in tests.
 */
final class TestPoms {

    private TestPoms() {
    }

    /**
     * A {@code <project>} with optional own coordinates.
     *
     * @param groupId the groupId, or {@code null} to inherit it.
     * @param artifactId the artifactId.
     * @param version the version, or {@code null} to inherit it.
     * @param elements further child elements.
     * @return the POM text.
     */
    static String project(final String groupId, final String artifactId, final String version,
                          final String... elements) {
        StringBuilder pom = new StringBuilder("<project><modelVersion>4.0.0</modelVersion>");
        if (groupId != null) {
            pom.append("<groupId>").append(groupId).append("</groupId>");
        }
        pom.append("<artifactId>").append(artifactId).append("</artifactId>");
        if (version != null) {
            pom.append("<version>").append(version).append("</version>");
        }
        for (String element : elements) {
            pom.append(element);
        }
        return pom.append("</project>").toString();
    }

    /**
     * @param groupId the parent groupId.
     * @param artifactId the parent artifactId.
     * @param version the parent version.
     * @return a {@code <parent>} element with the default relative path.
     */
    static String parent(final String groupId, final String artifactId, final String version) {
        return "<parent><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version></parent>";
    }

    /**
     * @param groupId the parent groupId.
     * @param artifactId the parent artifactId.
     * @param version the parent version.
     * @param relativePath the {@code <relativePath>} text (may be empty).
     * @return a {@code <parent>} element with an explicit relative path.
     */
    static String parent(final String groupId, final String artifactId, final String version,
                         final String relativePath) {
        return "<parent><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version><relativePath>" + relativePath + "</relativePath></parent>";
    }

    /**
     * @param modules the module entries.
     * @return a {@code <packaging>pom</packaging>} plus {@code <modules>} element.
     */
    static String modules(final String... modules) {
        StringBuilder element = new StringBuilder("<packaging>pom</packaging><modules>");
        for (String module : modules) {
            element.append("<module>").append(module).append("</module>");
        }
        return element.append("</modules>").toString();
    }

    /**
     * @param namesAndValues property name, value, name, value, ...
     * @return a {@code <properties>} element.
     */
    static String properties(final String... namesAndValues) {
        StringBuilder element = new StringBuilder("<properties>");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            element.append('<').append(namesAndValues[i]).append('>').append(namesAndValues[i + 1])
                    .append("</").append(namesAndValues[i]).append('>');
        }
        return element.append("</properties>").toString();
    }

    /**
     * @param dependencies {@link #dep} elements.
     * @return a {@code <dependencies>} element.
     */
    static String dependencies(final String... dependencies) {
        return "<dependencies>" + String.join("", dependencies) + "</dependencies>";
    }

    /**
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the version.
     * @return a {@code <dependency>} element.
     */
    static String dep(final String groupId, final String artifactId, final String version) {
        return "<dependency><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version></dependency>";
    }
}
