package org.tiatesting.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
}
