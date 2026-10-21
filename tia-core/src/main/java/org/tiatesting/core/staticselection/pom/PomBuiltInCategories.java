package org.tiatesting.core.staticselection.pom;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The built-in knowledge of which plugins and properties affect what: which plugins are inert,
 * which generate code, which properties are read implicitly by the compiler or test runner. These
 * are code constants rather than user configuration; extending them is a code change. Plugins are
 * matched by artifactId alone because generator and quality plugins are published under several
 * groupIds over their history.
 */
final class PomBuiltInCategories {

    /** Tia's own Maven plugin; its configuration is inert. */
    static final String TIA_PLUGIN_ARTIFACT_ID = "tia-maven-plugin";

    /** Plugins that publish, document, report on or police the build without changing what the code does. */
    private static final Set<String> INERT_PLUGINS = setOf(
            "maven-deploy-plugin", "maven-install-plugin", "maven-javadoc-plugin", "maven-source-plugin",
            "maven-gpg-plugin", "maven-release-plugin", "maven-site-plugin", "maven-project-info-reports-plugin",
            "nexus-staging-maven-plugin", "central-publishing-maven-plugin", "versions-maven-plugin",
            "maven-checkstyle-plugin", "spotbugs-maven-plugin", "maven-pmd-plugin", "maven-enforcer-plugin",
            TIA_PLUGIN_ARTIFACT_ID);

    /** Plugins whose output is generated source or classes that are not in version control. */
    private static final Set<String> CODE_GENERATION_PLUGINS = setOf(
            "openapi-generator-maven-plugin", "swagger-codegen-maven-plugin", "jaxb2-maven-plugin",
            "maven-jaxb2-plugin", "jaxb-maven-plugin", "cxf-codegen-plugin", "jaxws-maven-plugin",
            "protobuf-maven-plugin", "avro-maven-plugin", "antlr4-maven-plugin", "antlr3-maven-plugin",
            "jooq-codegen-maven", "apt-maven-plugin", "build-helper-maven-plugin", "jsonschema2pojo-maven-plugin",
            "javacc-maven-plugin", "xmlbeans-maven-plugin", "wsdl2java-maven-plugin");

    /** Properties nothing in the build reads in a way that changes what the code does. */
    private static final Set<String> INERT_PROPERTIES = setOf(
            "project.reporting.outputEncoding", "maven.deploy.skip", "maven.install.skip", "maven.javadoc.skip",
            "maven.source.skip", "maven.site.skip", "gpg.skip", "skipNexusStagingDeployMojo", "checkstyle.skip",
            "spotbugs.skip", "pmd.skip", "enforcer.skip");

    /** Test runner properties read implicitly by surefire / failsafe. */
    private static final Set<String> TEST_RUNNER_PROPERTIES = setOf(
            "argLine", "skipTests", "skipITs", "maven.test.skip", "maven.test.failure.ignore", "test", "it.test");

    private PomBuiltInCategories() {
    }

    /**
     * Categorise a plugin by its artifactId.
     *
     * @param artifactId the plugin's artifactId.
     * @return the category a change to the plugin falls into, or {@code null} when the plugin is inert.
     */
    static PomChangeCategory pluginCategory(final String artifactId) {
        if (INERT_PLUGINS.contains(artifactId)) {
            return null;
        }
        if ("maven-compiler-plugin".equals(artifactId)) {
            return PomChangeCategory.COMPILER;
        }
        if ("maven-surefire-plugin".equals(artifactId) || "maven-failsafe-plugin".equals(artifactId)) {
            return PomChangeCategory.TEST_RUNNER;
        }
        if ("maven-resources-plugin".equals(artifactId)) {
            return PomChangeCategory.RESOURCES;
        }
        if (CODE_GENERATION_PLUGINS.contains(artifactId)) {
            return PomChangeCategory.CODE_GENERATION;
        }
        return PomChangeCategory.BUILD_PLUGINS;
    }

    /**
     * Check whether a property is inert regardless of where it is used: Tia's own {@code tia*}
     * properties and the publish / report / quality switches.
     *
     * @param name the property name.
     * @return {@code true} when a change to the property is inert.
     */
    static boolean isInertProperty(final String name) {
        return name.startsWith("tia") || INERT_PROPERTIES.contains(name);
    }

    /**
     * Categorise a property that plugins read implicitly, without a {@code ${...}} reference in
     * the POM.
     *
     * @param name the property name.
     * @return the category, or {@code null} when the property is not a known implicit one.
     */
    static PomChangeCategory implicitPropertyCategory(final String name) {
        if (name.startsWith("maven.compiler.") || "project.build.sourceEncoding".equals(name)) {
            return PomChangeCategory.COMPILER;
        }
        if (name.startsWith("surefire.") || name.startsWith("failsafe.") || TEST_RUNNER_PROPERTIES.contains(name)) {
            return PomChangeCategory.TEST_RUNNER;
        }
        return null;
    }

    /**
     * Build an immutable set.
     *
     * @param values the members.
     * @return the set.
     */
    private static Set<String> setOf(final String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
