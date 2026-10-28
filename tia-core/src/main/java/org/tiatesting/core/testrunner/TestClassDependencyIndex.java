package org.tiatesting.core.testrunner;

import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A reverse dependency index over the compiled test classes: for each class in the test dirs, the
 * test classes that reference it. Used to find the tracked suites that depend on a changed test
 * support class - a base class, helper, fixture or API client that lives under a test source dir
 * but isn't a suite itself. The coverage mapping can't answer that, since it only records source
 * methods, and test code never appears in it.
 *
 * <p>Each class file is read with ASM without visiting any method code. The classes it references
 * are taken from its constant pool: every class entry (superclass, interfaces, method and field
 * owners, {@code new}, casts and class literals) plus every {@code L<name>;} type named in a UTF8
 * entry (field types, method parameter and return types, generic signatures and annotation types,
 * which have no class entry of their own). A compile-time constant is inlined by javac and leaves
 * no reference, so a change to one isn't followed.
 *
 * <p>Classes are folded to their top-level class: {@code Outer$Inner} and {@code Outer$1} count as
 * {@code Outer}, since they come from the same source file. Only references to classes in the test
 * dirs are kept, plus references to the changed classes the index was built for, so a deleted
 * support class (with no class file left) is still found through the classes that reference it.
 *
 * <p>The index is built once per selection, only when a changed test file isn't a tracked suite, so
 * builds that don't change test support code never read the test dirs. See the test selection
 * chapter of {@code WIKI.md}.
 */
public final class TestClassDependencyIndex {

    private static final Logger log = LoggerFactory.getLogger(TestClassDependencyIndex.class);

    private static final String CLASS_FILE_SUFFIX = ".class";
    // Constant pool tags, JVMS 4.4.
    private static final int CONSTANT_UTF8 = 1;
    private static final int CONSTANT_CLASS = 7;

    private final boolean hasClassDirs;
    private final Set<String> indexedClasses;
    private final Map<String, Set<String>> referencedBy;
    private final Map<String, Set<String>> trackedSuitesByTopLevelClass;

    /**
     * Hold a built index.
     *
     * @param hasClassDirs whether at least one of the test-classes dirs exists
     * @param indexedClasses the top-level classes with a class file in the test dirs, as binary names
     * @param referencedBy for each kept top-level class, the top-level classes that reference it
     * @param trackedSuitesByTopLevelClass the tracked suites grouped by their top-level class
     */
    private TestClassDependencyIndex(final boolean hasClassDirs, final Set<String> indexedClasses,
                                     final Map<String, Set<String>> referencedBy,
                                     final Map<String, Set<String>> trackedSuitesByTopLevelClass) {
        this.hasClassDirs = hasClassDirs;
        this.indexedClasses = indexedClasses;
        this.referencedBy = referencedBy;
        this.trackedSuitesByTopLevelClass = trackedSuitesByTopLevelClass;
    }

    /**
     * Read every class file in the test-classes dirs and build the reverse dependency index. Dirs
     * that don't exist are skipped; if none exists, the index is empty and {@link #hasClassDirs()}
     * is false, so the caller can tell "nothing references it" apart from "nothing was compiled". A
     * class file that can't be read is logged at WARN and contributes no references.
     *
     * @param testClassesDirs the dirs holding the compiled test classes; blank entries are ignored
     * @param changedClasses the changed test support classes the index is queried for, as binary
     *                       names; references to them are kept even when they have no class file
     * @param trackedSuiteNames the tracked suite names, as binary names (a JUnit 5 {@code @Nested}
     *                          suite keeps its {@code Outer$Nested} form)
     * @return the index
     */
    public static TestClassDependencyIndex build(final Collection<String> testClassesDirs,
                                                 final Collection<String> changedClasses,
                                                 final Collection<String> trackedSuiteNames) {
        List<Path> dirs = existingDirs(testClassesDirs);
        Map<String, Set<String>> referencesByClass = new HashMap<>();
        for (Path dir : dirs) {
            readDir(dir, referencesByClass);
        }

        Set<String> keptTargets = new HashSet<>(referencesByClass.keySet());
        for (String changedClass : changedClasses) {
            keptTargets.add(topLevelClass(changedClass));
        }
        Map<String, Set<String>> referencedBy = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : referencesByClass.entrySet()) {
            for (String referenced : entry.getValue()) {
                if (keptTargets.contains(referenced)) {
                    referencedBy.computeIfAbsent(referenced, key -> new HashSet<>()).add(entry.getKey());
                }
            }
        }

        Map<String, Set<String>> trackedSuitesByTopLevelClass = new HashMap<>();
        for (String suiteName : trackedSuiteNames) {
            trackedSuitesByTopLevelClass.computeIfAbsent(topLevelClass(suiteName), key -> new HashSet<>())
                    .add(suiteName);
        }

        log.debug("Indexed {} test class(es) from {} test classes dir(s) for test support dependencies",
                referencesByClass.size(), dirs.size());
        return new TestClassDependencyIndex(!dirs.isEmpty(), referencesByClass.keySet(), referencedBy,
                trackedSuitesByTopLevelClass);
    }

    /**
     * Report whether any of the test-classes dirs existed when the index was built. With none, no
     * dependency can be found, and the caller decides what to select instead.
     *
     * @return true when at least one test-classes dir exists
     */
    public boolean hasClassDirs() {
        return hasClassDirs;
    }

    /**
     * Report whether a class, folded to its top-level class, has a class file in the test dirs. A
     * changed support class that wasn't deleted but has no class file means the test classes are
     * missing or out of date, so its dependants can't be trusted.
     *
     * @param className the class's binary name, e.g. {@code com.example.OrderTestHelper}
     * @return true when the top-level class has a class file
     */
    public boolean hasClassFile(final String className) {
        return indexedClasses.contains(topLevelClass(className));
    }

    /**
     * Find the tracked suites that depend on a changed class, directly or through other test
     * classes: a breadth-first walk over the classes that reference it, then the classes that
     * reference those, and so on. A suite {@code A} extending {@code BaseTest}, which calls
     * {@code OrderApi}, depends on {@code OrderApi}. A tracked suite nested in a reached class
     * ({@code Outer$Nested} when {@code Outer} is reached) counts too, including one nested in the
     * changed class itself.
     *
     * @param changedClass the changed class's binary name; a nested class is folded to its
     *                     top-level class
     * @return the tracked suites that depend on the class, sorted; empty when none does
     */
    public Set<String> dependantSuites(final String changedClass) {
        String start = topLevelClass(changedClass);
        Set<String> reached = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        reached.add(start);
        pending.add(start);
        while (!pending.isEmpty()) {
            Set<String> referencing = referencedBy.get(pending.poll());
            if (referencing == null) {
                continue;
            }
            for (String referencingClass : referencing) {
                if (reached.add(referencingClass)) {
                    pending.add(referencingClass);
                }
            }
        }

        Set<String> suites = new TreeSet<>();
        for (String reachedClass : reached) {
            Set<String> trackedSuites = trackedSuitesByTopLevelClass.get(reachedClass);
            if (trackedSuites != null) {
                suites.addAll(trackedSuites);
            }
        }
        return suites;
    }

    /**
     * Keep the test-classes dirs that exist, skipping blank entries.
     *
     * @param testClassesDirs the configured dirs
     * @return the existing dirs
     */
    private static List<Path> existingDirs(final Collection<String> testClassesDirs) {
        List<Path> existing = new ArrayList<>();
        for (String dir : testClassesDirs) {
            if (dir == null || dir.trim().isEmpty()) {
                continue;
            }
            Path path = Paths.get(dir.trim());
            if (Files.isDirectory(path)) {
                existing.add(path);
            } else {
                log.debug("Test classes dir does not exist, not indexing it: {}", path);
            }
        }
        return existing;
    }

    /**
     * Read every class file under a dir and add each top-level class's references to the map. A
     * class already read from an earlier dir is added to, not replaced, so nested classes split
     * across dirs still fold together.
     *
     * @param dir the test-classes dir to walk
     * @param referencesByClass accumulator: top-level class to the top-level classes it references
     */
    private static void readDir(final Path dir, final Map<String, Set<String>> referencesByClass) {
        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(dir)) {
            classFiles = walk.filter(path -> path.toString().endsWith(CLASS_FILE_SUFFIX) && Files.isRegularFile(path))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Could not list the test classes in " + dir, e);
        }

        for (Path classFile : classFiles) {
            String relative = dir.relativize(classFile).toString().replace('\\', '/');
            String className = topLevelClass(relative.substring(0, relative.length() - CLASS_FILE_SUFFIX.length())
                    .replace('/', '.'));
            Set<String> references = referencesByClass.computeIfAbsent(className, key -> new HashSet<>());
            try {
                addReferences(Files.readAllBytes(classFile), className, references);
            } catch (IOException | RuntimeException e) {
                // A truncated file, or one compiled for a Java version newer than this ASM reads.
                log.warn("Could not read test class file {} ({}), so Tia cannot tell which test classes "
                        + "depend on it.", classFile, e.toString());
            }
        }
    }

    /**
     * Collect the classes a class file references from its constant pool, without visiting code:
     * each class entry, and each {@code L<name>;} type named in a UTF8 entry. References are folded
     * to their top-level class; the class's own (and its nested classes') are dropped.
     *
     * @param classBytes the class file's bytes
     * @param className the class's own top-level binary name
     * @param references accumulator for the referenced top-level binary names
     * @throws IOException if a UTF8 entry can't be decoded
     */
    private static void addReferences(final byte[] classBytes, final String className,
                                      final Set<String> references) throws IOException {
        ClassReader reader = new ClassReader(classBytes);
        char[] buffer = new char[reader.getMaxStringLength()];
        Set<String> internalNames = new HashSet<>();
        for (int item = 1; item < reader.getItemCount(); item++) {
            // getItem gives the offset just past the entry's tag; 0 for the unused slot after a long or double
            int offset = reader.getItem(item);
            if (offset == 0) {
                continue;
            }
            int tag = reader.readByte(offset - 1);
            if (tag == CONSTANT_CLASS) {
                // a class entry holds the index of the UTF8 entry naming it, which readUTF8 resolves
                addClassEntry(reader.readUTF8(offset, buffer), internalNames);
            } else if (tag == CONSTANT_UTF8) {
                addTypesInDescriptor(readUtf8(classBytes, offset), internalNames);
            }
        }

        for (String internalName : internalNames) {
            String referenced = topLevelClass(internalName.replace('/', '.'));
            if (!referenced.equals(className)) {
                references.add(referenced);
            }
        }
    }

    /**
     * Add a class entry's name. An array class entry ({@code [Lcom/example/Foo;}) names its element
     * type in descriptor form; a primitive array names none.
     *
     * @param name the class entry's internal name or array descriptor
     * @param internalNames accumulator for internal names
     */
    private static void addClassEntry(final String name, final Set<String> internalNames) {
        if (name.startsWith("[")) {
            addTypesInDescriptor(name, internalNames);
        } else {
            internalNames.add(name);
        }
    }

    /**
     * Add every {@code L<name>;} type in a string that may be a field or method descriptor or a
     * generic signature, e.g. {@code (Lcom/example/OrderApi;)V} or
     * {@code Ljava/util/List<Lcom/example/Order;>;}. Other UTF8 entries (member names, string
     * literals) can yield stray names too, but only names of classes in the test dirs are kept, so
     * they cost nothing.
     *
     * @param value the UTF8 entry's value
     * @param internalNames accumulator for internal names
     */
    private static void addTypesInDescriptor(final String value, final Set<String> internalNames) {
        int length = value.length();
        for (int start = value.indexOf('L'); start >= 0; start = value.indexOf('L', start + 1)) {
            int end = start + 1;
            while (end < length && isNameChar(value.charAt(end))) {
                end++;
            }
            if (end > start + 1 && end < length && (value.charAt(end) == ';' || value.charAt(end) == '<')) {
                internalNames.add(value.substring(start + 1, end));
            }
        }
    }

    /**
     * Report whether a character can be part of an internal class name.
     *
     * @param c the character
     * @return true for a Java identifier part or the {@code /} package separator
     */
    private static boolean isNameChar(final char c) {
        return c == '/' || Character.isJavaIdentifierPart(c);
    }

    /**
     * Decode a UTF8 constant pool entry: a two-byte length followed by modified UTF-8, the format
     * {@link DataInputStream#readUTF()} reads.
     *
     * @param classBytes the class file's bytes
     * @param offset the offset of the entry's length, just past its tag
     * @return the entry's value
     * @throws IOException if the bytes aren't valid modified UTF-8
     */
    private static String readUtf8(final byte[] classBytes, final int offset) throws IOException {
        int length = ((classBytes[offset] & 0xFF) << 8) | (classBytes[offset + 1] & 0xFF);
        return new DataInputStream(new ByteArrayInputStream(classBytes, offset, length + 2)).readUTF();
    }

    /**
     * Fold a binary class name to its top-level class by cutting it at the first {@code $} of the
     * simple name: {@code com.example.Outer$Inner} and {@code com.example.Outer$1} become
     * {@code com.example.Outer}.
     *
     * @param className a binary class name
     * @return the top-level class's binary name
     */
    static String topLevelClass(final String className) {
        int simpleNameStart = className.lastIndexOf('.') + 1;
        int nested = className.indexOf('$', simpleNameStart);
        return nested < 0 ? className : className.substring(0, nested);
    }
}
