package org.tiatesting.core.perf;

import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.vcs.VCSReader;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Times each phase of the {@code select-tests} read path against an existing H2 DB.
 * Designed for attaching async-profiler / JFR — keeps the JVM lifetime short, runs
 * each phase in isolation so the flame graph cleanly attributes time.
 *
 * <p>Phases reported separately:
 * <ol>
 *     <li>{@code H2DataStore} construction (cheap; just opens the URL)</li>
 *     <li>{@code dataStore.getTiaData(true)} — reads test suites + classes + methods + libraries</li>
 *     <li>{@code TestSelector.selectTestsToIgnore} with an empty-diff VCS stub — exercises the
 *         post-load selection logic on top of the loaded data</li>
 * </ol>
 *
 * <p>Invocation:
 * <pre>
 *   ./gradlew :tia-core:profileSelectTests \
 *       -PoutDb=/tmp/tia-perf -Pbranch=main -Piterations=3
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

    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        System.out.println("ProfileSelectTests starting with " + parsed);

        for (int i = 0; i < parsed.iterations; i++) {
            System.out.println();
            System.out.println("=== Iteration " + (i + 1) + " of " + parsed.iterations + " ===");
            runOnce(parsed);
        }
    }

    private static void runOnce(Args args) {
        long t0 = System.nanoTime();
        H2DataStore dataStore = new H2DataStore(args.outDb, args.branch);
        long tConstruct = System.nanoTime();
        printPhase("Phase 1 — H2DataStore construction", t0, tConstruct);

        // Phase 2 — full DB load. This is the suspected hotspot.
        long tLoadStart = System.nanoTime();
        dataStore.getTiaData(true);
        long tLoadEnd = System.nanoTime();
        printPhase("Phase 2 — getTiaData(true) full load", tLoadStart, tLoadEnd);

        // Phase 3 — selectTestsToIgnore with an empty-diff stub VCS reader.
        // This forces the selector through its end-to-end logic on the just-loaded data,
        // including drain analysis and ignore-set construction.
        TestSelector selector = new TestSelector(dataStore);
        VCSReader stubVcs = new EmptyDiffVCSReader(args.branch);

        long tSelectStart = System.nanoTime();
        TestSelectorResult result = selector.selectTestsToIgnore(stubVcs,
                Collections.emptyList(),
                Collections.emptyList(),
                /* checkLocalChanges */ false,
                /* libraryConfig */ null,
                /* updateDBMapping */ false);
        long tSelectEnd = System.nanoTime();
        printPhase("Phase 3 — selectTestsToIgnore (empty diff)", tSelectStart, tSelectEnd);

        System.out.println("  result.testsToRun=" + (result.getTestsToRun() == null ? 0 : result.getTestsToRun().size())
                + " result.testsToIgnore=" + (result.getTestsToIgnore() == null ? 0 : result.getTestsToIgnore().size()));

        long tEnd = System.nanoTime();
        printPhase("TOTAL", t0, tEnd);
    }

    private static void printPhase(String label, long startNanos, long endNanos) {
        long ms = (endNanos - startNanos) / 1_000_000;
        System.out.printf("  %-50s %6d ms%n", label, ms);
    }

    /**
     * VCS stub that pretends nothing changed since the previous Tia run. Lets us measure the
     * selector path without involving git/perforce I/O.
     */
    private static final class EmptyDiffVCSReader implements VCSReader {
        private final String branchName;

        EmptyDiffVCSReader(String branchName) {
            this.branchName = branchName;
        }

        @Override public String getBranchName() { return branchName; }
        @Override public String getHeadCommit() { return "synthetic-head"; }

        @Override
        public Set<SourceFileDiffContext> buildDiffFilesContext(String baseChangeNum, List<String> sourceFilesDirs,
                                                                 List<String> testFilesDirs, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        @Override public void close() { }
    }

    private static final class Args {
        String outDb = "/tmp/tia-perf";
        String branch = "main";
        int iterations = 3;

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
                    default: throw new IllegalArgumentException("Unknown arg: " + key);
                }
            }
            return a;
        }

        @Override public String toString() {
            return "Args{outDb=" + outDb + ", branch=" + branch + ", iterations=" + iterations + "}";
        }
    }
}
