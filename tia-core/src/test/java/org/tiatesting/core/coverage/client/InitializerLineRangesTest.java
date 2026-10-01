package org.tiatesting.core.coverage.client;

import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.internal.analysis.CounterImpl;
import org.jacoco.core.internal.analysis.MethodCoverageImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link InitializerLineRanges} using hand-built JaCoCo method coverage nodes, so each test
 * controls exactly which source lines each method has code on.
 */
class InitializerLineRangesTest {

    /**
     * A field declared after the other methods stretches the constructor's start-end range over the
     * whole class. The computed ranges drop every other method (and the other constructor's body)
     * but keep the field initializer lines and the gaps between members.
     * Mirrors the CarService layout: fields on 8, 9, 15 and 74; constructors on 11-13 and 17-19.
     */
    @Test
    void lateFieldConstructorRangesExcludeTheOtherMethods() {
        // given
        List<IMethodCoverage> classMethods = carServiceLayout();
        IMethodCoverage noArgConstructor = classMethods.get(0);

        // when
        int[] ranges = InitializerLineRanges.compute(noArgConstructor, classMethods, Collections.emptyList());

        // then
        assertArrayEquals(new int[]{7, 16, 20, 21, 25, 25, 34, 34, 39, 39, 43, 44, 49, 49, 53, 53,
                57, 57, 66, 66, 70, 70, 74, 75}, ranges);
    }

    /**
     * A second constructor keeps the field initializer lines it shares with the first, loses the
     * first constructor's body, and keeps its own body.
     */
    @Test
    void secondConstructorExcludesTheFirstConstructorsBody() {
        // given
        List<IMethodCoverage> classMethods = carServiceLayout();
        IMethodCoverage integerConstructor = classMethods.get(1);

        // when
        int[] ranges = InitializerLineRanges.compute(integerConstructor, classMethods, Collections.emptyList());

        // then
        assertArrayEquals(new int[]{7, 10, 14, 21, 25, 25, 34, 34, 39, 39, 43, 44, 49, 49, 53, 53,
                57, 57, 66, 66, 70, 70, 74, 75}, ranges);
    }

    /**
     * A constructor with no other member inside its range needs no split ranges, so the plain
     * start-end match is kept.
     */
    @Test
    void constructorWithNoMemberInsideItsRangeReturnsNull() {
        // given
        IMethodCoverage constructor = method("<init>", 5, 6);
        IMethodCoverage laterMethod = method("later", 10, 11, 12);

        // when
        int[] ranges = InitializerLineRanges.compute(constructor, Arrays.asList(constructor, laterMethod),
                Collections.emptyList());

        // then
        assertNull(ranges);
    }

    /**
     * A neighbouring method that only overlaps the constructor's padding line (here the method's
     * signature sits directly below an implicit constructor on the class declaration line) leaves
     * the constructor's own lines whole, so plain padded start-end matching is kept.
     */
    @Test
    void constructorWithOnlyItsPaddingClippedReturnsNull() {
        // given
        IMethodCoverage constructor = method("<init>", 3);
        IMethodCoverage nextMethod = method("checkBrakePads", 5, 6);

        // when
        int[] ranges = InitializerLineRanges.compute(constructor, Arrays.asList(constructor, nextMethod),
                Collections.emptyList());

        // then
        assertNull(ranges);
    }

    /**
     * The blank line after a void method's closing brace stays in the constructor's ranges, so a
     * field inserted between two methods separated by one blank line can still be matched.
     */
    @Test
    void blankLineAfterAMethodStaysInTheConstructorsRanges() {
        // given
        IMethodCoverage constructor = method("<init>", 3, 20);
        IMethodCoverage voidMethod = method("first", 6, 7);
        IMethodCoverage nextMethod = method("second", 10, 11);

        // when
        int[] ranges = InitializerLineRanges.compute(constructor,
                Arrays.asList(constructor, voidMethod, nextMethod), Collections.emptyList());

        // then
        assertArrayEquals(new int[]{2, 4, 8, 8, 12, 21}, ranges);
    }

    /**
     * A member that sits on the same line as a field initializer (e.g. a lambda in the initializer)
     * can't remove that line from the constructor.
     */
    @Test
    void ownCodeLineIsKeptWhenAnotherMemberSharesIt() {
        // given
        IMethodCoverage constructor = method("<init>", 3, 20);
        IMethodCoverage lambda = method("lambda$new$0", 20);

        // when
        int[] ranges = InitializerLineRanges.compute(constructor, Arrays.asList(constructor, lambda),
                Collections.emptyList());

        // then
        assertArrayEquals(new int[]{2, 18, 20, 21}, ranges);
    }

    /**
     * Methods of other classes compiled from the same source file (nested, inner, anonymous) are
     * removed from the constructor's range.
     */
    @Test
    void otherClassesInTheSourceFileAreExcluded() {
        // given
        IMethodCoverage constructor = method("<init>", 3, 30);
        IMethodCoverage nestedClassMethod = method("nestedMethod", 10, 11, 12);

        // when
        int[] ranges = InitializerLineRanges.compute(constructor, Collections.singletonList(constructor),
                Collections.singletonList(nestedClassMethod));

        // then
        assertArrayEquals(new int[]{2, 8, 13, 31}, ranges);
    }

    /**
     * A static initializer drops the lines of the class's constructor that it doesn't share, so a
     * change to an instance field or constructor body isn't treated as a change to the static
     * initializer.
     */
    @Test
    void staticInitializerExcludesTheConstructorsLines() {
        // given
        IMethodCoverage staticInitializer = method("<clinit>", 4, 30);
        IMethodCoverage constructor = method("<init>", 5, 7, 8);

        // when
        int[] ranges = InitializerLineRanges.compute(staticInitializer,
                Arrays.asList(staticInitializer, constructor), Collections.emptyList());

        // then
        assertArrayEquals(new int[]{3, 4, 6, 6, 9, 31}, ranges);
    }

    /**
     * An initializer with no line debug info can't be split, so it returns null.
     */
    @Test
    void initializerWithNoLineInfoReturnsNull() {
        // given
        IMethodCoverage constructor = method("<init>");

        // when
        int[] ranges = InitializerLineRanges.compute(constructor, Collections.singletonList(constructor),
                Collections.emptyList());

        // then
        assertNull(ranges);
    }

    /**
     * Only constructors and static initializers are treated as initializers.
     */
    @Test
    void isInitializerMatchesOnlyConstructorsAndStaticInitializers() {
        // given
        String constructor = "<init>";
        String staticInitializer = "<clinit>";
        String plainMethod = "init";

        // when
        boolean constructorIsInitializer = InitializerLineRanges.isInitializer(constructor);
        boolean staticInitializerIsInitializer = InitializerLineRanges.isInitializer(staticInitializer);
        boolean plainMethodIsInitializer = InitializerLineRanges.isInitializer(plainMethod);

        // then
        assertTrue(constructorIsInitializer);
        assertTrue(staticInitializerIsInitializer);
        assertFalse(plainMethodIsInitializer);
    }

    /**
     * Builds the methods of a class laid out like the junit5-git-maven-postgres CarService fixture:
     * fields initialized on lines 8, 9, 15 and 74, a no-arg constructor on 11-13, an Integer
     * constructor on 17-19, then eleven methods, the last ending on line 73.
     *
     * @return the class's methods; index 0 is the no-arg constructor, index 1 the Integer constructor
     */
    private List<IMethodCoverage> carServiceLayout() {
        List<IMethodCoverage> methods = new ArrayList<>();
        methods.add(method("<init>", 8, 9, 11, 12, 13, 15, 74));
        methods.add(method("<init>", 8, 9, 15, 17, 18, 19, 74));
        methods.add(method("cleanCar", 23, 24));
        methods.add(method("fixCar", 27, 28, 29, 30, 31, 32, 33));
        methods.add(method("checkBrakes", 36, 37, 38));
        methods.add(method("getDoorHandleModel", 41, 42));
        methods.add(method("checkBattery", 46, 47, 48));
        methods.add(method("washCar", 51, 52));
        methods.add(method("temp100", 55, 56));
        methods.add(method("temp", 59, 60, 61));
        methods.add(method("temp20", 63, 64, 65));
        methods.add(method("temp3", 68, 69));
        methods.add(method("temp4", 72, 73));
        return methods;
    }

    /**
     * Builds a method coverage node with one instruction on each of the given lines.
     *
     * @param name the JVM method name
     * @param codeLines the source lines the method has code on
     * @return the method coverage node
     */
    private IMethodCoverage method(String name, int... codeLines) {
        MethodCoverageImpl method = new MethodCoverageImpl(name, "()V", null);
        for (int line : codeLines) {
            method.increment(CounterImpl.COUNTER_1_0, CounterImpl.COUNTER_0_0, line);
        }
        return method;
    }
}
