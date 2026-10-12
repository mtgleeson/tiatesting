package org.tiatesting.core.staticselection.pom;

/**
 * The kinds of POM change a POM-aware static test selection rule can react to. A change that
 * cannot affect the behaviour of the code or tests (project metadata, the project's own version,
 * tracked-library versions, inert plugins, Tia's own configuration) has no category at all.
 *
 * <p>Anything the classifier does not recognise is {@link #OTHER}, so it still counts towards
 * {@link #FUNCTIONAL}: a false trigger costs build time, a false skip costs correctness.
 */
public enum PomChangeCategory {

    /**
     * The resolved version of an untracked, non-reactor dependency changed (including managed
     * dependencies, BOM imports and an external parent).
     */
    DEPENDENCY_VERSIONS,

    /**
     * A dependency was added or removed, or its scope, classifier, type, optional flag, system
     * path or exclusions changed. Applies to tracked libraries too.
     */
    DEPENDENCY_SET,

    /** The compiler plugin, an annotation processor, or a compiler property changed. */
    COMPILER,

    /** The surefire / failsafe plugins or a test runner property changed. */
    TEST_RUNNER,

    /** A known code generation plugin changed. */
    CODE_GENERATION,

    /** Resources, resource filtering or the source directory layout changed. */
    RESOURCES,

    /** Any other non-inert plugin or plugin management entry changed. */
    BUILD_PLUGINS,

    /**
     * A change the classifier does not recognise or cannot rule out: modules, packaging,
     * repositories, extensions, profile activation, unresolved property changes, unparseable,
     * added or deleted POMs. Only reachable through {@link #FUNCTIONAL}.
     */
    OTHER,

    /** Every category above: any change that is not inert. */
    FUNCTIONAL;

    /**
     * Check whether a rule configured with this category fires for a change of the given
     * category. {@link #FUNCTIONAL} covers every category; any other category covers only itself.
     *
     * @param changeCategory the category of a classified change.
     * @return {@code true} if a rule configured with this category fires for the change.
     */
    public boolean covers(final PomChangeCategory changeCategory) {
        return this == FUNCTIONAL || this == changeCategory;
    }
}
