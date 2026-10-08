package org.tiatesting.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adds JUnit Jupiter's {@code @Disabled} to the test classes Tia skips, as they load.
 *
 * <p>The annotation type is never loaded by the agent and never bundled with it: JUnit must come
 * from the project, and on Gradle the agent jar is searched before the project's classes, so a
 * bundled copy would win. When an ignored
 * test class loads, its own class loader can see the project's JUnit, so {@code @Disabled} is
 * described from the class file that loader finds - read, not loaded - and the JVM resolves the
 * annotation against that same loader when JUnit reads it.
 */
public class IgnoreTestInstrumentor {

    private static final Logger log = Logger.getLogger(IgnoreTestInstrumentor.class.getName());

    /** Binary name of JUnit Jupiter's {@code @Disabled}. */
    static final String DISABLED_ANNOTATION = "org.junit.jupiter.api.Disabled";

    /*
    The @Disabled description for the most recent test class loader, so its class file is read once
    per loader rather than once per ignored class. One entry, held strongly: a description reaches
    back to its loader through the class file locator it was read with, so a per-loader map would
    keep every loader alive, and a weakly held value is cleared by the next collection. A run almost
    always loads its test classes through one loader; at most one is kept alive here.
     */
    private ClassLoader cachedLoader;
    private AnnotationDescription cachedDisabled;

    /**
     * Register a transformer that annotates each ignored test class with {@code @Disabled} when it
     * loads, so JUnit reports it as skipped.
     *
     * @param ignoredTests the binary names of the test classes to skip
     * @param instrumentation the instrumentation handle from {@code premain}
     */
    public void ignoreTests(final Set<String> ignoredTests, final Instrumentation instrumentation) {
        new AgentBuilder.Default()
                .with(new FailureLogger())
                .type(ElementMatchers.namedOneOf(ignoredTests.toArray(new String[0])))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.annotateType(disabledFor(classLoader)))
                .installOn(instrumentation);
    }

    /**
     * Describe {@code @Disabled("Ignored by TIA testing")} as the given test class loader sees it,
     * reusing the description while the same loader keeps asking.
     *
     * @param classLoader the loader of the test class being transformed
     * @return the annotation to add
     */
    synchronized AnnotationDescription disabledFor(final ClassLoader classLoader) {
        if (cachedDisabled == null || cachedLoader != classLoader) {
            TypeDescription disabledType = TypePool.Default
                    .of(ClassFileLocator.ForClassLoader.of(classLoader))
                    .describe(DISABLED_ANNOTATION)
                    .resolve();
            cachedDisabled = AnnotationDescription.Builder.ofType(disabledType)
                    .define("value", "Ignored by TIA testing")
                    .build();
            cachedLoader = classLoader;
        }
        return cachedDisabled;
    }

    /**
     * Reports a test class Tia meant to skip but could not annotate. ByteBuddy's default is to
     * carry on silently, and the class would then run as if selected; a warning says why.
     */
    private static final class FailureLogger extends AgentBuilder.Listener.Adapter {

        /**
         * Log the failure to annotate one class.
         *
         * @param typeName the class that could not be annotated
         * @param classLoader its class loader
         * @param module its module, or null before Java 9
         * @param loaded whether the class was already loaded
         * @param throwable the failure
         */
        @Override
        public void onError(final String typeName, final ClassLoader classLoader, final JavaModule module,
                            final boolean loaded, final Throwable throwable) {
            log.log(Level.WARNING, "Tia could not mark " + typeName + " @Disabled, so it runs although Tia "
                    + "did not select it.", throwable);
        }
    }
}
