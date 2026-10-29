package org.tiatesting.core.diff.diffanalyze.selector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.tiatesting.core.sourcefile.FileExtensions.GROOVY_FILE_EXT;
import static org.tiatesting.core.sourcefile.FileExtensions.JAVA_FILE_EXT;

/**
 * A reverse dependency index over the test source files: for each test class, the test classes
 * whose source refers to it. Used to find the tracked suites that depend on a changed test support
 * class - a base class, helper, fixture or API client that lives under a test source dir but isn't
 * a suite itself. The coverage mapping can't answer that, since it only records source methods,
 * and test code never appears in it.
 *
 * <p>The index reads the {@code .java} and {@code .groovy} sources rather than compiled classes,
 * because selection often runs before the tests are compiled (Maven selects at {@code initialize},
 * and a distributed plan step may run on its own), and the sources are always there. A test source
 * file refers to a test class {@code p.X} when it:
 * <ul>
 *   <li>imports {@code p.X}, a member or nested class of it ({@code import p.X.Inner},
 *       {@code import static p.X.build}), or {@code p.X.*};</li>
 *   <li>is in package {@code p}, or imports {@code p.*}, and has {@code X} as a whole word anywhere
 *       in its text;</li>
 *   <li>has the qualified name {@code p.X} anywhere in its text.</li>
 * </ul>
 * Comments and string literals aren't stripped, so a class named only in a comment counts too. That
 * can only select extra suites, never miss one. A class declared as a second top-level class in
 * another class's file isn't known by its own name, so references to it aren't followed.
 *
 * <p>Each file counts as its top-level class, named from its path under the test dir, as the
 * changed test files are. Nested classes are folded to their top-level class: {@code Outer$Inner}
 * counts as {@code Outer}, since they come from the same source file. Only references to test
 * classes are kept, including the changed classes the index was built for, so a deleted support
 * class (with no source file left) is still found through the files that still refer to it.
 *
 * <p>The index is built once per selection, only when a changed test file isn't a tracked suite, so
 * builds that don't change test support code never read the test sources. See the "Changed test
 * support files" chapter of {@code WIKI.md}.
 */
final class TestSourceDependencyIndex {

    private static final Logger log = LoggerFactory.getLogger(TestSourceDependencyIndex.class);

    private static final String JAVA_SUFFIX = "." + JAVA_FILE_EXT;
    private static final String GROOVY_SUFFIX = "." + GROOVY_FILE_EXT;
    // An import's name, with optional static modifier and trailing wildcard; Groovy imports have no semicolon.
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
                    + "(?:\\s*\\.\\s*[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*)(\\s*\\.\\s*\\*)?",
            Pattern.MULTILINE);

    private final Map<String, Set<String>> referencedBy;
    private final Map<String, Set<String>> trackedSuitesByTopLevelClass;

    /**
     * Hold a built index.
     *
     * @param referencedBy for each test class, the test classes whose source refers to it
     * @param trackedSuitesByTopLevelClass the tracked suites grouped by their top-level class
     */
    private TestSourceDependencyIndex(final Map<String, Set<String>> referencedBy,
                                      final Map<String, Set<String>> trackedSuitesByTopLevelClass) {
        this.referencedBy = referencedBy;
        this.trackedSuitesByTopLevelClass = trackedSuitesByTopLevelClass;
    }

    /**
     * Read every test source file under the test dirs and build the reverse dependency index. Dirs
     * that don't exist are skipped. A file that can't be read is logged at WARN and refers to
     * nothing.
     *
     * @param testFilesDirs the test source dirs, as full paths
     * @param changedClasses the changed test support classes the index is queried for, as binary
     *                       names; references to them are kept even when their source file is gone
     * @param trackedSuiteNames the tracked suite names, as binary names (a JUnit 5 {@code @Nested}
     *                          suite keeps its {@code Outer$Nested} form)
     * @return the index
     */
    static TestSourceDependencyIndex build(final Collection<String> testFilesDirs,
                                           final Collection<String> changedClasses,
                                           final Collection<String> trackedSuiteNames) {
        Map<String, Path> sourceFilesByClass = new HashMap<>();
        for (String dir : testFilesDirs) {
            Path path = Paths.get(dir);
            if (Files.isDirectory(path)) {
                findSourceFiles(path, sourceFilesByClass);
            } else {
                log.debug("Test files dir does not exist, not indexing it: {}", path);
            }
        }

        Set<String> knownClasses = new HashSet<>(sourceFilesByClass.keySet());
        for (String changedClass : changedClasses) {
            knownClasses.add(topLevelClass(changedClass));
        }
        Map<String, List<String>> knownClassesBySimpleName = new HashMap<>();
        for (String knownClass : knownClasses) {
            knownClassesBySimpleName.computeIfAbsent(simpleName(knownClass), key -> new ArrayList<>()).add(knownClass);
        }

        Map<String, Set<String>> referencedBy = new HashMap<>();
        for (Map.Entry<String, Path> sourceFile : sourceFilesByClass.entrySet()) {
            String className = sourceFile.getKey();
            String text;
            try {
                text = new String(Files.readAllBytes(sourceFile.getValue()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Could not read test source file {} ({}), so Tia cannot tell which test classes "
                        + "it uses.", sourceFile.getValue(), e.toString());
                continue;
            }
            for (String referenced : findReferences(className, text, knownClasses, knownClassesBySimpleName)) {
                referencedBy.computeIfAbsent(referenced, key -> new HashSet<>()).add(className);
            }
        }

        Map<String, Set<String>> trackedSuitesByTopLevelClass = new HashMap<>();
        for (String suiteName : trackedSuiteNames) {
            trackedSuitesByTopLevelClass.computeIfAbsent(topLevelClass(suiteName), key -> new HashSet<>())
                    .add(suiteName);
        }

        log.debug("Indexed {} test source file(s) for test support dependencies", sourceFilesByClass.size());
        return new TestSourceDependencyIndex(referencedBy, trackedSuitesByTopLevelClass);
    }

    /**
     * Find the tracked suites that depend on a changed class, directly or through other test
     * classes: a breadth-first walk over the classes that refer to it, then the classes that refer
     * to those, and so on. A suite {@code A} extending {@code BaseTest}, which calls
     * {@code OrderApi}, depends on {@code OrderApi}. A tracked suite nested in a reached class
     * ({@code Outer$Nested} when {@code Outer} is reached) counts too, including one nested in the
     * changed class itself.
     *
     * @param changedClass the changed class's binary name; a nested class is folded to its
     *                     top-level class
     * @return the tracked suites that depend on the class, sorted; empty when none does
     */
    Set<String> dependantSuites(final String changedClass) {
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
     * Add every {@code .java} and {@code .groovy} file under a test dir, keyed by the class name
     * its path gives (e.g. {@code com/example/OrderTest.java} is {@code com.example.OrderTest}).
     * Files whose name isn't a class name, such as {@code package-info.java}, are skipped. A class
     * already found in an earlier dir keeps that file.
     *
     * @param dir the test source dir to walk
     * @param sourceFilesByClass accumulator: class name to its source file
     */
    private static void findSourceFiles(final Path dir, final Map<String, Path> sourceFilesByClass) {
        List<Path> sourceFiles;
        try (Stream<Path> walk = Files.walk(dir)) {
            sourceFiles = walk.filter(path -> isSourceFile(path) && Files.isRegularFile(path))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Could not list the test source files in " + dir, e);
        }

        for (Path sourceFile : sourceFiles) {
            String relative = dir.relativize(sourceFile).toString().replace('\\', '/');
            String className = relative.substring(0, relative.lastIndexOf('.')).replace('/', '.');
            if (isClassName(simpleName(className))) {
                sourceFilesByClass.putIfAbsent(className, sourceFile);
            }
        }
    }

    /**
     * Report whether a path is a Java or Groovy source file.
     *
     * @param path the path
     * @return true for a {@code .java} or {@code .groovy} file name, in any case
     */
    private static boolean isSourceFile(final Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(JAVA_SUFFIX) || name.endsWith(GROOVY_SUFFIX);
    }

    /**
     * Find the known test classes a source file refers to, by its imports, the whole words in its
     * text and the qualified names in its text (see the class javadoc).
     *
     * @param className the file's own class name
     * @param text the file's text
     * @param knownClasses every known test class, as top-level binary names
     * @param knownClassesBySimpleName the known test classes grouped by simple name
     * @return the known classes the file refers to, other than itself
     */
    private static Set<String> findReferences(final String className, final String text,
                                              final Set<String> knownClasses,
                                              final Map<String, List<String>> knownClassesBySimpleName) {
        Set<String> references = new HashSet<>();
        Set<String> visiblePackages = new HashSet<>();
        visiblePackages.add(packageName(className));

        Matcher imports = IMPORT_PATTERN.matcher(text);
        while (imports.find()) {
            String imported = imports.group(1).replaceAll("\\s", "");
            String importedClass = knownClassOrEnclosing(imported, knownClasses);
            if (importedClass != null) {
                references.add(importedClass);
            } else if (imports.group(2) != null) {
                visiblePackages.add(imported);
            }
        }

        for (String word : wordsIn(text)) {
            List<String> candidates = knownClassesBySimpleName.get(word);
            if (candidates == null) {
                continue;
            }
            for (String candidate : candidates) {
                if (visiblePackages.contains(packageName(candidate)) || text.contains(candidate)) {
                    references.add(candidate);
                }
            }
        }

        references.remove(className);
        return references;
    }

    /**
     * Resolve an imported name to the known test class it names or sits in: the name itself, or the
     * longest known class it starts with, for a nested class or a static member
     * ({@code com.example.Helper.Builder} and {@code com.example.Helper.build} both resolve to
     * {@code com.example.Helper}).
     *
     * @param imported the imported name, without a wildcard
     * @param knownClasses every known test class, as top-level binary names
     * @return the known class, or null when the import isn't of a test class
     */
    private static String knownClassOrEnclosing(final String imported, final Set<String> knownClasses) {
        String name = imported;
        while (true) {
            if (knownClasses.contains(name)) {
                return name;
            }
            int lastDot = name.lastIndexOf('.');
            if (lastDot < 0) {
                return null;
            }
            name = name.substring(0, lastDot);
        }
    }

    /**
     * Collect the distinct identifiers (whole words) in a text.
     *
     * @param text the text
     * @return the identifiers it contains
     */
    private static Set<String> wordsIn(final String text) {
        Set<String> words = new HashSet<>();
        int length = text.length();
        int index = 0;
        while (index < length) {
            if (Character.isJavaIdentifierStart(text.charAt(index))) {
                int start = index;
                index++;
                while (index < length && Character.isJavaIdentifierPart(text.charAt(index))) {
                    index++;
                }
                words.add(text.substring(start, index));
            } else {
                index++;
            }
        }
        return words;
    }

    /**
     * Report whether a simple name is a valid class name.
     *
     * @param name the simple name
     * @return true when it is a non-empty Java identifier
     */
    private static boolean isClassName(final String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Give a class's package.
     *
     * @param className a top-level class's binary name
     * @return its package, or the empty string for the default package
     */
    private static String packageName(final String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? "" : className.substring(0, lastDot);
    }

    /**
     * Give a class's simple name.
     *
     * @param className a top-level class's binary name
     * @return the part after the last dot
     */
    private static String simpleName(final String className) {
        return className.substring(className.lastIndexOf('.') + 1);
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
