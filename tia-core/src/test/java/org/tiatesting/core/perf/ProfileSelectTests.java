package org.tiatesting.core.perf;

import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.vcs.VCSReader;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Times each phase of the {@code select-tests} read path against an existing H2 DB.
 * Designed for attaching async-profiler / JFR - keeps the JVM lifetime short, runs
 * each phase in isolation so the flame graph cleanly attributes time.
 *
 * <p>Phases reported separately:
 * <ol>
 *     <li>{@code H2DataStore} construction (cheap; just opens the URL)</li>
 *     <li>(optional, {@code fullLoad=true}) {@code dataStore.getTiaData(true)} - the legacy
 *         bulk load of suites + classes + methods + libraries. Kept as the baseline number;
 *         the targeted select path below no longer performs it.</li>
 *     <li>{@code TestSelector.selectTestsToIgnore} with a stub VCS reader - the targeted
 *         read path (suite metadata + Phase A/B diff-slice queries). {@code diffFiles=N}
 *         simulates a diff touching N tracked source files (full-file modifications, so
 *         every tracked method of those files is impacted - the per-file worst case);
 *         {@code diffFiles=0} measures the no-changes floor.</li>
 * </ol>
 *
 * <p>Embedded mode (default) opens the DB file directly. Server mode is selected by passing
 * {@code url=jdbc:h2:tcp://host:port/path} (plus {@code user=}/{@code password=} if they
 * differ from the embedded defaults) and exercises the H2 remote protocol - the wire tax
 * that motivated the targeted-query work.
 *
 * <p>Invocation:
 * <pre>
 *   ./gradlew :tia-core:profileSelectTests \
 *       -PoutDb=/tmp/tia-perf -Pbranch=main -Piterations=3 -PdiffFiles=20
 *
 *   # server mode against a running H2 TCP server:
 *   ./gradlew :tia-core:profileSelectTests \
 *       -Purl=jdbc:h2:tcp://localhost:9092/tia-perf/tiadb-main -Pbranch=main -PdiffFiles=20
 * </pre>
 *
 * <p>For a flame graph: download async-profiler, then run with the JVM agent attached. Example:
 * <pre>
 *   ./gradlew :tia-core:profileSelectTests \
 *       -PoutDb=/tmp/tia-perf \
 *       -PjvmArgs="-agentpath:/path/to/libasyncProfiler.so=start,event=cpu,file=/tmp/tia-cpu.html"
 * </pre>
 * Or with JFR (no install required):
 * <pre>
 *   ./gradlew :tia-core:profileSelectTests -PoutDb=/tmp/tia-perf \
 *       -PjvmArgs="-XX:StartFlightRecording=duration=60s,filename=/tmp/tia.jfr,settings=profile"
 * </pre>
 * Open the .jfr file in JDK Mission Control to inspect.
 */
public final class ProfileSelectTests {

    private ProfileSelectTests() {
    }

    /**
     * Entry point: parse the key=value args and run the timed phases for the requested
     * number of iterations.
     *
     * @param args key=value pairs - see {@link Args} for the supported keys
     */
    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        System.out.println("ProfileSelectTests starting with " + parsed);

        for (int i = 0; i < parsed.iterations; i++) {
            System.out.println();
            System.out.println("=== Iteration " + (i + 1) + " of " + parsed.iterations + " ===");
            runOnce(parsed);
        }
    }

    /**
     * Run one timed pass: construct the datastore (embedded or server per the args), run the
     * optional legacy full load, then the targeted {@code selectTestsToIgnore} path with a
     * synthetic diff of {@code diffFiles} modified tracked files.
     *
     * @param args the parsed harness arguments
     */
    private static void runOnce(Args args) {
        long t0 = System.nanoTime();
        H2ConnectionSettings settings = args.url == null
                ? H2ConnectionSettings.embedded(args.outDb, args.branch)
                : H2ConnectionSettings.server(args.url, args.user, args.password, args.branch);
        H2DataStore dataStore = new H2DataStore(settings);
        long tConstruct = System.nanoTime();
        printPhase("Phase 1 - H2DataStore construction", t0, tConstruct);

        // Phase 2 (optional) - the legacy bulk load, kept as the before-number. The targeted
        // select path no longer performs this; skip it with fullLoad=false to time the new
        // path in isolation (e.g. when profiling with JFR).
        if (args.fullLoad) {
            long tLoadStart = System.nanoTime();
            dataStore.getTiaData(true);
            long tLoadEnd = System.nanoTime();
            printPhase("Phase 2 - getTiaData(true) legacy full load", tLoadStart, tLoadEnd);
        }

        // Phase 3 - selectTestsToIgnore with a synthetic-diff stub VCS reader. This drives
        // the targeted path end to end: core read, suite metadata, Phase A (changed files ->
        // tracked methods), line-range intersection, Phase B (methods -> suites), failed
        // tests, ignore-set construction and the run-time estimate.
        TestSelector selector = new TestSelector(dataStore);
        VCSReader stubVcs = new SyntheticDiffVCSReader(args.branch, args.diffFiles);

        long tSelectStart = System.nanoTime();
        TestSelectorResult result = selector.selectTestsToIgnore(stubVcs,
                Collections.emptyList(),
                Collections.emptyList(),
                /* checkLocalChanges */ false,
                /* libraryConfig */ null,
                /* staticMappingConfig */ null,
                /* updateDBMapping */ false);
        long tSelectEnd = System.nanoTime();
        printPhase("Phase 3 - selectTestsToIgnore (diffFiles=" + args.diffFiles + ")", tSelectStart, tSelectEnd);

        System.out.println("  result.testsToRun=" + (result.getTestsToRun() == null ? 0 : result.getTestsToRun().size())
                + " result.testsToIgnore=" + (result.getTestsToIgnore() == null ? 0 : result.getTestsToIgnore().size()));

        long tEnd = System.nanoTime();
        printPhase("TOTAL", t0, tEnd);
    }

    /**
     * Print one timed phase as a fixed-width label and elapsed milliseconds.
     *
     * @param label the phase label
     * @param startNanos phase start (from {@link System#nanoTime()})
     * @param endNanos phase end
     */
    private static void printPhase(String label, long startNanos, long endNanos) {
        long ms = (endNanos - startNanos) / 1_000_000;
        System.out.printf("  %-50s %6d ms%n", label, ms);
    }

    /**
     * VCS stub that simulates a diff touching {@code diffFiles} tracked source files. Each
     * simulated file is a full-file modification (every line changed), so the diff hunk covers
     * every tracked method of that file - the per-file worst case for Phase A/B result size.
     * File paths reuse {@link GenerateLargeTiaDb#classFilename(int)} so the mapping keys match
     * the rows the generator wrote. With {@code diffFiles=0} this behaves like the previous
     * empty-diff stub.
     */
    private static final class SyntheticDiffVCSReader implements VCSReader {
        private final String branchName;
        private final int diffFiles;

        /**
         * @param branchName the branch name to report
         * @param diffFiles the number of tracked source files the simulated diff touches
         */
        SyntheticDiffVCSReader(String branchName, int diffFiles) {
            this.branchName = branchName;
            this.diffFiles = diffFiles;
        }

        @Override public String getBranchName() { return branchName; }
        @Override public String getHeadCommit() { return "synthetic-head"; }

        /**
         * Build {@code diffFiles} modified-source diff contexts with synthetic before/after
         * content. The content is 900 numbered lines with every line changed, producing one
         * hunk spanning the whole file - it intersects every stored method line range
         * (the generator assigns ranges within lines 1-862).
         *
         * @param baseChangeNum ignored (no real VCS)
         * @param sourceFilesDirs ignored (paths are already mapping-key shaped)
         * @param testFilesDirs ignored
         * @param checkLocalChanges ignored
         * @return the synthetic diff contexts
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            Set<SourceFileDiffContext> diffs = new HashSet<>();
            StringBuilder original = new StringBuilder();
            StringBuilder changed = new StringBuilder();
            for (int line = 1; line <= 900; line++) {
                original.append("int value").append(line).append(" = ").append(line).append(";\n");
                changed.append("int value").append(line).append(" = ").append(line + 1).append(";\n");
            }

            for (int i = 0; i < diffFiles; i++) {
                // Leading slash: the selector normalizes diff paths by stripping source dirs
                // then dropping the leading separator, yielding the stored mapping key.
                String path = "/" + GenerateLargeTiaDb.classFilename(i);
                SourceFileDiffContext diff = new SourceFileDiffContext(path, path, ChangeType.MODIFY);
                diff.setSourceContentOriginal(original.toString());
                diff.setSourceContentNew(changed.toString());
                diffs.add(diff);
            }
            return diffs;
        }

        @Override
        public void loadContentForDiffs(java.util.Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                        boolean checkLocalChanges) {
            // no-op: synthetic content is baked into the contexts by getDiffFiles
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        @Override public void close() { }
    }

    private static final class Args {
        String outDb = "/tmp/tia-perf";
        String branch = "main";
        int iterations = 3;
        String url;
        String user = "sa";
        String password = "1234";
        int diffFiles = 0;
        boolean fullLoad = true;

        /**
         * Parse key=value harness arguments.
         *
         * @param argv the raw program arguments
         * @return the populated args holder
         */
        static Args parse(String[] argv) {
            Args a = new Args();
            for (String raw : argv) {
                int eq = raw.indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException("Expected key=value, got: " + raw);
                }
                String key = raw.substring(0, eq);
                String value = raw.substring(eq + 1);
                switch (key) {
                    case "out": a.outDb = value; break;
                    case "branch": a.branch = value; break;
                    case "iterations": a.iterations = Integer.parseInt(value); break;
                    case "url": a.url = value; break;
                    case "user": a.user = value; break;
                    case "password": a.password = value; break;
                    case "diffFiles": a.diffFiles = Integer.parseInt(value); break;
                    case "fullLoad": a.fullLoad = Boolean.parseBoolean(value); break;
                    default: throw new IllegalArgumentException("Unknown arg: " + key);
                }
            }
            return a;
        }

        @Override public String toString() {
            return "Args{outDb=" + outDb + ", branch=" + branch + ", iterations=" + iterations
                    + ", url=" + url + ", diffFiles=" + diffFiles + ", fullLoad=" + fullLoad + "}";
        }
    }
}
