package org.tiatesting.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adds JUnit Jupiter's {@code @Disabled} to the test classes Tia skips, as they load.
 *
 * <p>The annotation type is never loaded by the agent and never bundled with it: on Gradle the agent
 * runs before the test classpath is in place, and JUnit must come from the project. When an ignored
 * test class loads, its own class loader can see the project's JUnit, so {@code @Disabled} is
 * described from the class file that loader finds - read, not loaded - and the JVM resolves the
 * annotation against that same loader when JUnit reads it.
 */
public class IgnoreTestInstrumentor {

    private static final Logger log = Logger.getLogger(IgnoreTestInstrumentor.class.getName());

    /** Binary name of JUnit Jupiter's {@code @Disabled}. */
    static final String DISABLED_ANNOTATION = "org.junit.jupiter.api.Disabled";

    /*
    The @Disabled description per test class loader, so its class file is read once per loader. The
    value is held weakly too: a description reaches back to its loader through the class file
    locator it was read with, and a strongly held value would keep its weak key alive forever.
     */
    private final Map<ClassLoader, WeakReference<AnnotationDescription>> disabledByLoader = new WeakHashMap<>();

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
     * Describe {@code @Disabled("Ignored by TIA testing")} as the given test class loader sees it.
     *
     * @param classLoader the loader of the test class being transformed
     * @return the annotation to add
     */
    synchronized AnnotationDescription disabledFor(final ClassLoader classLoader) {
        WeakReference<AnnotationDescription> cached = disabledByLoader.get(classLoader);
        AnnotationDescription disabled = cached != null ? cached.get() : null;
        if (disabled == null) {
            TypeDescription disabledType = TypePool.Default
                    .of(ClassFileLocator.ForClassLoader.of(classLoader))
                    .describe(DISABLED_ANNOTATION)
                    .resolve();
            disabled = AnnotationDescription.Builder.ofType(disabledType)
                    .define("value", "Ignored by TIA testing")
                    .build();
            disabledByLoader.put(classLoader, new WeakReference<>(disabled));
        }
        return disabled;
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
