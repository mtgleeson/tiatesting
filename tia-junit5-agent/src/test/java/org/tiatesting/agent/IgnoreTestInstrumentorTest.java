package org.tiatesting.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code @Disabled} the agent adds is described from the class loader's own JUnit and
 * is visible to reflection - which is how JUnit decides to skip the class - with Tia's reason.
 */
class IgnoreTestInstrumentorTest {

    /**
     * A class standing in for an ignored test class; it has no tests of its own.
     */
    static class SampleTarget {
    }

    @Test
    void addedAnnotationIsTheLoadersOwnRuntimeVisibleDisabled() {
        // given
        ClassLoader loader = getClass().getClassLoader();
        AnnotationDescription disabled = new IgnoreTestInstrumentor().disabledFor(loader);

        // when
        Class<?> annotated = new ByteBuddy()
                .redefine(SampleTarget.class)
                .annotateType(disabled)
                .make()
                .load(loader, ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();

        // then
        Disabled found = annotated.getAnnotation(Disabled.class);
        assertNotNull(found, "the added @Disabled must be visible at run time");
        assertEquals("Ignored by TIA testing", found.value());
    }

    @Test
    void annotationIsDescribedOncePerClassLoader() {
        // given
        IgnoreTestInstrumentor instrumentor = new IgnoreTestInstrumentor();
        ClassLoader loader = getClass().getClassLoader();

        // when
        AnnotationDescription first = instrumentor.disabledFor(loader);
        AnnotationDescription second = instrumentor.disabledFor(loader);

        // then
        assertSame(first, second);
    }

    @Test
    void aDifferentClassLoaderGetsItsOwnDescription() throws Exception {
        // given
        IgnoreTestInstrumentor instrumentor = new IgnoreTestInstrumentor();
        ClassLoader loader = getClass().getClassLoader();
        AnnotationDescription first = instrumentor.disabledFor(loader);

        // when
        AnnotationDescription other;
        try (java.net.URLClassLoader child = new java.net.URLClassLoader(new java.net.URL[0], loader)) {
            other = instrumentor.disabledFor(child);
        }

        // then - described again, for the loader that will resolve it
        assertNotSame(first, other);
        assertEquals(first.getAnnotationType().getName(), other.getAnnotationType().getName());
    }

    @Test
    void onlyClassesTiaMeantToSkipAreReportedAsFailures() {
        // given - a handler collecting the instrumentor's warnings
        java.util.List<String> warned = new java.util.ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(IgnoreTestInstrumentor.class.getName());
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(final java.util.logging.LogRecord record) {
                warned.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        IgnoreTestInstrumentor.FailureLogger failures =
                new IgnoreTestInstrumentor.FailureLogger(java.util.Collections.singleton("com.example.IgnoredTest"));

        // when
        try {
            failures.onError("com.example.SomeOtherClass", null, null, false, new IllegalStateException("x"));
            failures.onError("com.example.IgnoredTest", null, null, false, new IllegalStateException("y"));
        } finally {
            logger.removeHandler(handler);
        }

        // then
        assertEquals(1, warned.size(), warned.toString());
        assertTrue(warned.get(0).contains("com.example.IgnoredTest"), warned.get(0));
    }
}
