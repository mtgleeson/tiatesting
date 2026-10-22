package org.tiatesting.core.testrunner;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, from compiled class files alone, whether a test suite name can ever be run by a test
 * engine. Used where a suite was selected or assigned but may not be a real test: a helper or base
 * class added under a test source dir, an interface, or a class deleted since planning. See "The
 * completeness guard" in the distributed test runs chapter of {@code WIKI.md} for how the verdicts
 * are used.
 *
 * <p>Classes are read with ASM and never loaded. Every answer errs towards "may run": anything the
 * classifier cannot prove is {@link Verdict#UNDETERMINED}, which callers treat as runnable. In
 * particular a superclass outside the test dirs (other than a JDK class) may carry tests the
 * classifier cannot see, such as JUnit 3's {@code TestCase}, so it makes the answer undetermined.
 * External interfaces are not followed: a library interface carrying test default methods is
 * vanishingly rare, while helpers implementing library interfaces are common.
 *
 * <p>A class is treated as a possible test when it, or a class or interface in its hierarchy that
 * lives in the test dirs, carries a class or method annotation from a known test framework package,
 * or an annotation whose own type is in the test dirs and is (recursively) annotated with one - a
 * composed annotation such as {@code @FastTest}. A test framework whose annotations live in another
 * package is not recognised, which is why callers only ever excuse a suite that was also never
 * observed.
 *
 * <p>Class headers are cached per instance, so classifying several suites that share a base class
 * reads it once. Not thread safe.
 */
public final class TestSuiteRunnabilityClassifier {

    private static final Logger log = LoggerFactory.getLogger(TestSuiteRunnabilityClassifier.class);

    // Annotation packages whose presence on a class or method marks it as a possible test.
    private static final List<String> TEST_ANNOTATION_PREFIXES = Arrays.asList(
            "Lorg/junit/", "Lorg/testng/", "Lnet/jqwik/", "Lio/cucumber/",
            "Lcom/tngtech/archunit/", "Lorg/spockframework/", "Lspock/");
    // Superclass packages that can never be a test base: reaching one ends that branch cleanly.
    private static final List<String> JDK_PREFIXES = Arrays.asList("java/", "javax/", "jdk/", "sun/");
    private static final String OBJECT = "java/lang/Object";
    private static final String SPOCK_SPECIFICATION = "spock/lang/Specification";
    private static final String CLASS_FILE_SUFFIX = ".class";

    /** What the class files say about one suite name. */
    public enum Verdict {
        /** The class carries a test marker, or is a Spock specification. */
        RUNNABLE(null),
        /** The classifier cannot tell; callers must treat the suite as runnable. */
        UNDETERMINED(null),
        /** No test dir holds a class file for the suite. */
        NO_CLASS_FILE("no class file"),
        /** The suite is an interface, which no test engine instantiates. */
        INTERFACE("interface"),
        /** The suite is an abstract class, which no test engine instantiates. */
        ABSTRACT("abstract class"),
        /** Nothing in the suite's resolvable hierarchy carries a test marker. */
        NO_TEST_METHODS("no test methods");

        private final String reason;

        /**
         * Bind a verdict to the reason a log line gives for it.
         *
         * @param reason the human-readable reason for a never-runnable verdict, or null for one
         *               that may run
         */
        Verdict(final String reason) {
            this.reason = reason;
        }

        /**
         * Report whether a test engine can never run a suite with this verdict, so a group may
         * count it as accounted for without having observed it.
         *
         * @return true for {@link #NO_CLASS_FILE}, {@link #INTERFACE}, {@link #ABSTRACT} and
         *         {@link #NO_TEST_METHODS}
         */
        public boolean isNeverRunnable() {
            return reason != null;
        }

        /**
         * Report whether the class file exists and proves the class is not a test. Stricter than
         * {@link #isNeverRunnable()}: at plan time a missing class file usually means the tests
         * have not been compiled yet, not that the file is not a test.
         *
         * @return true for {@link #INTERFACE}, {@link #ABSTRACT} and {@link #NO_TEST_METHODS}
         */
        public boolean isProvenNotATest() {
            return isNeverRunnable() && this != NO_CLASS_FILE;
        }

        /**
         * Give the reason to show in a log line naming a never-runnable suite.
         *
         * @return the reason, e.g. {@code "no test methods"}, or null for {@link #RUNNABLE} and
         *         {@link #UNDETERMINED}
         */
        public String getReason() {
            return reason;
        }
    }

    private final List<Path> dirs;
    private final Map<String, ClassHeader> headers = new HashMap<>();

    /**
     * Bind the classifier to the test-classes dirs it reads.
     *
     * @param dirs the existing test-classes dirs to read; empty disables classification
     */
    private TestSuiteRunnabilityClassifier(final List<Path> dirs) {
        this.dirs = dirs;
    }

    /**
     * Build a classifier over a comma-separated list of test-classes dirs, the format of the
     * {@code tiaTestClassesDirs} fork property.
     *
     * @param testClassesDirsCsv the dirs, comma separated; null or blank disables classification
     * @return the classifier
     */
    public static TestSuiteRunnabilityClassifier fromCsv(final String testClassesDirsCsv) {
        if (testClassesDirsCsv == null || testClassesDirsCsv.trim().isEmpty()) {
            return disabled();
        }
        return forDirs(Arrays.asList(testClassesDirsCsv.split(",")));
    }

    /**
     * Build a classifier over the given test-classes dirs. Dirs that do not exist are dropped; if
     * none exists, every verdict is {@link Verdict#UNDETERMINED}, so a wrong or not-yet-created path
     * can never make every suite look deleted.
     *
     * @param testClassesDirs the dirs to read class files from; blank entries are ignored
     * @return the classifier
     */
    public static TestSuiteRunnabilityClassifier forDirs(final Collection<String> testClassesDirs) {
        List<Path> existing = new ArrayList<>();
        for (String dir : testClassesDirs) {
            if (dir == null || dir.trim().isEmpty()) {
                continue;
            }
            Path path = Paths.get(dir.trim());
            if (Files.isDirectory(path)) {
                existing.add(path);
            } else {
                log.debug("Test classes dir does not exist, not classifying against it: {}", path);
            }
        }
        return new TestSuiteRunnabilityClassifier(existing);
    }

    /**
     * Build a classifier that classifies nothing, for callers with no compiled test classes.
     *
     * @return a classifier whose every verdict is {@link Verdict#UNDETERMINED}
     */
    public static TestSuiteRunnabilityClassifier disabled() {
        return new TestSuiteRunnabilityClassifier(Collections.<Path>emptyList());
    }

    /**
     * Classify one suite by its binary class name: missing, an interface, abstract, or else by
     * whether anything in its hierarchy carries a test marker.
     *
     * @param suiteName the suite's binary name, e.g. {@code com.example.Outer$Inner}
     * @return the verdict; {@link Verdict#UNDETERMINED} whenever it cannot be proven either way
     * @throws IllegalStateException if a class file exists but cannot be read or parsed - a corrupt
     *                               file must never be excused as missing
     */
    public Verdict classify(final String suiteName) {
        if (dirs.isEmpty()) {
            return Verdict.UNDETERMINED;
        }

        ClassHeader suite = header(suiteName.replace('.', '/'));
        if (suite == null) {
            return Verdict.NO_CLASS_FILE;
        }
        if ((suite.access & Opcodes.ACC_INTERFACE) != 0) {
            return Verdict.INTERFACE;
        }
        if ((suite.access & Opcodes.ACC_ABSTRACT) != 0) {
            return Verdict.ABSTRACT;
        }
        return classifyHierarchy(suite);
    }

    /**
     * Walk the suite's superclasses, and the interfaces that are in the test dirs, looking for a
     * test marker. A superclass that is neither in the test dirs nor in the JDK could carry tests
     * the walk cannot see, so it makes the answer undetermined rather than "no test methods".
     *
     * @param suite the suite's own header
     * @return {@link Verdict#RUNNABLE}, {@link Verdict#UNDETERMINED} or
     *         {@link Verdict#NO_TEST_METHODS}
     */
    private Verdict classifyHierarchy(final ClassHeader suite) {
        Deque<ClassHeader> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(suite);
        visited.add(suite.name);
        boolean undetermined = false;

        while (!pending.isEmpty()) {
            ClassHeader current = pending.poll();
            if (hasTestMarker(current, new HashSet<String>())) {
                return Verdict.RUNNABLE;
            }

            String superName = current.superName;
            if (superName != null && !OBJECT.equals(superName) && visited.add(superName)) {
                if (SPOCK_SPECIFICATION.equals(superName)) {
                    return Verdict.RUNNABLE;
                }
                ClassHeader superHeader = header(superName);
                if (superHeader != null) {
                    pending.add(superHeader);
                } else if (!startsWithAny(superName, JDK_PREFIXES)) {
                    undetermined = true;
                }
            }

            for (String interfaceName : current.interfaces) {
                if (visited.add(interfaceName)) {
                    ClassHeader interfaceHeader = header(interfaceName);
                    if (interfaceHeader != null) {
                        pending.add(interfaceHeader);
                    }
                }
            }
        }

        return undetermined ? Verdict.UNDETERMINED : Verdict.NO_TEST_METHODS;
    }

    /**
     * Report whether a class carries a test marker: a class or method annotation from a known test
     * package, or one whose annotation type is in the test dirs and is itself (recursively)
     * annotated with one.
     *
     * @param header the class to inspect
     * @param visitingAnnotations annotation types already inspected on this walk, so annotations
     *                            that annotate each other cannot recurse forever
     * @return true if the class carries a test marker
     */
    private boolean hasTestMarker(final ClassHeader header, final Set<String> visitingAnnotations) {
        for (String descriptor : header.annotationDescriptors) {
            if (startsWithAny(descriptor, TEST_ANNOTATION_PREFIXES)) {
                return true;
            }
            String annotationName = descriptor.substring(1, descriptor.length() - 1);
            if (visitingAnnotations.add(annotationName)) {
                ClassHeader annotationHeader = header(annotationName);
                if (annotationHeader != null && hasTestMarker(annotationHeader, visitingAnnotations)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Read, and cache, a class's header from the first test dir that holds its class file. A miss
     * is cached too, so a JDK or library supertype shared by many suites is looked up once.
     *
     * @param internalName the class's internal name, e.g. {@code com/example/Foo}
     * @return the header, or null when no test dir holds the class
     */
    private ClassHeader header(final String internalName) {
        if (headers.containsKey(internalName)) {
            return headers.get(internalName);
        }
        ClassHeader header = null;
        for (Path dir : dirs) {
            Path classFile = dir.resolve(internalName + CLASS_FILE_SUFFIX);
            if (Files.isRegularFile(classFile)) {
                header = readHeader(classFile);
                break;
            }
        }
        headers.put(internalName, header);
        return header;
    }

    /**
     * Parse a class file's access flags, supertypes and every class and method annotation
     * (runtime-visible or not), skipping code, debug info and frames.
     *
     * @param classFile the class file to read
     * @return the parsed header
     * @throws IllegalStateException if the file cannot be read or is not a valid class file
     */
    private static ClassHeader readHeader(final Path classFile) {
        final ClassHeader header = new ClassHeader();
        try (InputStream in = Files.newInputStream(classFile)) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(final int version, final int access, final String name,
                                  final String signature, final String superName,
                                  final String[] interfaces) {
                    header.name = name;
                    header.access = access;
                    header.superName = superName;
                    header.interfaces = interfaces == null ? new String[0] : interfaces;
                }

                @Override
                public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
                    header.annotationDescriptors.add(descriptor);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(final int access, final String name,
                                                 final String descriptor, final String signature,
                                                 final String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(final String annotationDescriptor,
                                                                 final boolean visible) {
                            header.annotationDescriptors.add(annotationDescriptor);
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read class file " + classFile, e);
        }
        return header;
    }

    /**
     * Report whether a string starts with any of several prefixes.
     *
     * @param value the string to test
     * @param prefixes the candidate prefixes
     * @return true if {@code value} starts with any of {@code prefixes}
     */
    private static boolean startsWithAny(final String value, final List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The parts of a class file the classifier needs. */
    private static final class ClassHeader {
        private String name;
        private int access;
        private String superName;
        private String[] interfaces = new String[0];
        private final Set<String> annotationDescriptors = new HashSet<>();
    }
}
