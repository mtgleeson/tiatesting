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
 * Scans compiled test-class directories into the top-level test-suite names a distributed seed
 * run splits across its groups.
 *
 * <p>This exists for the seed run only: the first distributed build on a branch has no stored
 * mapping to enumerate suites from, so the plan reads the suite universe off disk instead. It is
 * deliberately separate from {@code TestRunnerService.getTestClassesFromDirs}, whose contract for
 * deleted-suite detection is the opposite one - an all-absent directory list there must throw,
 * because an empty scan would be read as "every tracked suite was deleted" and wipe the mapping.
 * Here an empty scan means "nothing found to split", and the planner falls back to a single group,
 * so this returns an empty set rather than throwing.
 *
 * <p>The result is a <b>superset</b> of the suites the test framework actually runs: every
 * top-level compiled class is included, whether or not it is a runnable test. Over-inclusion is
 * safe - a name the framework never runs just sits in other runners' ignore lists and is never
 * executed - while under-inclusion would leave a real suite on nobody's ignore list and run it on
 * every runner. Inner classes (names containing {@code $}) are dropped: their enclosing top-level
 * class always compiles to its own {@code .class} file, so dropping them keeps the superset
 * property while stopping the count-based split from being skewed by inner classes.
 */
public final class TestClassScanner {

    private static final Logger log = LoggerFactory.getLogger(TestClassScanner.class);

    private TestClassScanner() { }

    /**
     * Scan the given compiled test-class directories into top-level FQN suite names.
     *
     * @param testClassesDirsCsv comma-separated directories holding the compiled test classes; may
     *                           be null or blank, in which case an empty set is returned
     * @return the top-level test-class names found across the existing directories, as dotted
     *         FQNs with inner ({@code $}) classes removed; empty when nothing usable is found,
     *         never null
     */
    public static Set<String> scanTopLevelTestSuiteNames(final String testClassesDirsCsv) {
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
                        .filter(name -> name.indexOf('$') < 0)
                        .forEach(suiteNames::add);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        log.debug("Seed-run test classes found on disk: {}", suiteNames);
        return suiteNames;
    }
}
