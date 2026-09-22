package org.tiatesting.core.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.sourcefile.FileExtensions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scans compiled test-class directories into the test-suite names a distributed seed run splits
 * across its groups.
 *
 * <p>This exists for the seed run only: the first distributed build on a branch has no stored
 * mapping to enumerate suites from, so the plan reads the suite universe off disk instead. It is
 * deliberately separate from {@code TestRunnerService.getTestClassesFromDirs}, whose contract for
 * deleted-suite detection is the opposite one - an all-absent directory list there must throw,
 * because an empty scan would be read as "every tracked suite was deleted" and wipe the mapping.
 * Here an empty scan means "nothing found to split", and the planner falls back to a single group,
 * so this returns an empty set rather than throwing.
 *
 * <p>The result is a <b>superset</b> of the suite names Tia tracks - the test framework's binary
 * class names, which for a JUnit5 {@code @Nested} class is the enclosing-and-nested form
 * {@code Outer$Nested}. Every compiled class is included, whether or not it is itself a runnable
 * test. Over-inclusion is safe - a name that never runs just sits unexecuted in some group's
 * ignore list - while under-inclusion is dangerous: a tracked suite missing from every group's
 * assignment would run on every runner at once, and would also make the seal miscount
 * {@code ignoredSuiteCount} and report a false {@code allTestsRun}.
 */
public final class TestClassScanner {

    private static final Logger log = LoggerFactory.getLogger(TestClassScanner.class);

    private TestClassScanner() { }

    /**
     * Scan the given compiled test-class directories into binary-name FQN suite names.
     *
     * @param testClassesDirsCsv comma-separated directories holding the compiled test classes; may
     *                           be null or blank, in which case an empty set is returned
     * @return every compiled class name found across the existing directories, as dotted binary
     *         FQNs (so a nested class keeps its {@code Outer$Nested} form); empty when nothing
     *         usable is found, never null
     */
    public static Set<String> scanTestSuiteNames(final String testClassesDirsCsv) {
        Set<String> suiteNames = new HashSet<>();
        if (testClassesDirsCsv == null || testClassesDirsCsv.trim().isEmpty()) {
            return suiteNames;
        }
        String classFileExt = "." + FileExtensions.CLASS_FILE_EXT;

        for (String dir : testClassesDirsCsv.split(",")) {
            String testClassesDir = dir.trim();
            if (testClassesDir.isEmpty()) {
                continue;
            }
            Path path = Paths.get(testClassesDir);
            if (!Files.isDirectory(path)) {
                log.debug("Skipping test classes directory that does not exist: {}", testClassesDir);
                continue;
            }
            try (Stream<Path> walk = Files.walk(path)) {
                walk.filter(p -> !Files.isDirectory(p))
                        .map(Path::toString)
                        .filter(f -> f.toLowerCase().endsWith(classFileExt))
                        .map(p -> p.replace(testClassesDir, "").replace(classFileExt, ""))
                        .map(p -> p.substring(p.startsWith(File.separator) ? 1 : 0)
                                .replace(File.separator, "."))
                        .forEach(suiteNames::add);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        log.debug("Seed-run test classes found on disk: {}", suiteNames);
        return suiteNames;
    }
}
