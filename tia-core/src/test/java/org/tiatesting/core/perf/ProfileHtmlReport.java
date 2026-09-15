package org.tiatesting.core.perf;

import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.report.html.HtmlReportGenerator;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Times each phase of the {@code tia-html-report} render path against an existing H2 DB.
 * Designed for attaching async-profiler / JFR - keeps the JVM lifetime short and runs each
 * phase in isolation so the flame graph cleanly attributes time.
 *
 * <p>Phases reported separately:
 * <ol>
 *     <li>{@code Phase A} - {@code dataStore.getTiaData(true)}, the legacy bulk load of every
 *         suite + class + method edge (on {@link JdbcDataStore} this is {@code readTiaDataFromDB}).
 *         This is the same full-mapping read the {@code tia-html-report} task performs before
 *         rendering, and the read path already optimised for {@code select-tests} via
 *         diff-sized targeted queries.</li>
 *     <li>{@code Phase B} - the whole render, reported as the sum of the individual sub-report
 *         steps below (running the steps in sequence on one generator is equivalent to
 *         {@link HtmlReportGenerator#generateReports}).</li>
 *     <li>{@code Phase B1..B7} - each step of {@code generateReports} timed on its own:
 *         copy-static-assets, summary, test-suite, source-method, source-code-landing, library
 *         and history. The source-method report is the suspected render hotspot - it builds an
 *         inverted method-to-suites index by iterating every mapping edge.</li>
 * </ol>
 *
 * <p>Embedded mode (default) opens the DB file directly via {@code -PoutDb}. Server mode is
 * selected by passing {@code -Purl=jdbc:h2:tcp://host:port/path} (plus {@code -Puser}/
 * {@code -Ppassword} if they differ from the embedded defaults) and exercises the H2 remote
 * protocol.
 *
 * <p>Report output is written under {@code -PoutDir} (default: a {@code tia-html-report-profile}
 * directory in the JVM temp dir). It is safe to delete between runs.
 *
 * <p>Invocation:
 * <pre>
 *   ./gradlew :tia-core:profileHtmlReport \
 *       -PoutDb=/tmp/tia-html-perf -Pbranch=main -Piterations=3
 *
 *   # server mode against a running H2 TCP server:
 *   ./gradlew :tia-core:profileHtmlReport \
 *       -Purl=jdbc:h2:tcp://localhost:9092/tia-html-perf/tiadb -Pbranch=main
 * </pre>
 *
 * <p>For a flame graph: download async-profiler, then run with the JVM agent attached. Example:
 * <pre>
 *   ./gradlew :tia-core:profileHtmlReport \
 *       -PoutDb=/tmp/tia-html-perf \
 *       -PjvmArgs="-agentpath:/path/to/libasyncProfiler.so=start,event=cpu,file=/tmp/tia-html-cpu.html"
 * </pre>
 * Or with JFR (built into the JDK, no install required):
 * <pre>
 *   ./gradlew :tia-core:profileHtmlReport -PoutDb=/tmp/tia-html-perf \
 *       -PjvmArgs="-XX:StartFlightRecording=duration=120s,filename=/tmp/tia-html.jfr,settings=profile"
 * </pre>
 * Open the .jfr file in JDK Mission Control, or summarise from the CLI with
 * {@code jfr print --events jdk.ExecutionSample /tmp/tia-html.jfr}.
 */
public final class ProfileHtmlReport {

    private ProfileHtmlReport() {
    }

    /**
     * Entry point: parse the key=value args, run the timed phases for the requested number of
     * iterations, and print the per-iteration breakdown plus an averaged summary.
     *
     * @param args key=value pairs - see {@link Args} for the supported keys
     */
    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        System.out.println("ProfileHtmlReport starting with " + parsed);

        Timings timings = new Timings();
        for (int i = 0; i < parsed.iterations; i++) {
            System.out.println();
            System.out.println("=== Iteration " + (i + 1) + " of " + parsed.iterations + " ===");
            runOnce(parsed, timings);
        }

        System.out.println();
        System.out.println("=== Averages over " + parsed.iterations + " iteration(s) ===");
        for (Map.Entry<String, Double> entry : timings.averages().entrySet()) {
            System.out.printf("  %-52s %8.1f ms%n", entry.getKey(), entry.getValue());
        }
    }

    /**
     * Run one timed pass: construct the datastore (embedded or server per the args), run the
     * Phase A full load, then time each step of the HTML render individually and record every
     * phase duration into the shared {@link Timings} accumulator.
     *
     * @param args the parsed harness arguments
     * @param timings the accumulator the phase durations for this iteration are recorded into
     */
    private static void runOnce(Args args, Timings timings) {
        H2ConnectionSettings settings = args.url == null
                ? H2ConnectionSettings.embedded(args.outDb)
                : H2ConnectionSettings.server(args.url, args.user, args.password);
        JdbcDataStore dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings),
                BranchSchema.schemaName(args.branch, null));

        // Phase A - the legacy bulk mapping load the tia-html-report task runs before rendering.
        long tLoadStart = System.nanoTime();
        TiaData tiaData = dataStore.getTiaData(true);
        long tLoadEnd = System.nanoTime();
        long loadMs = printPhase("Phase A - getTiaData(true) full load", tLoadStart, tLoadEnd);
        timings.record("Phase A - getTiaData(true) full load", loadMs);
        System.out.println("  suitesTracked=" + tiaData.getTestSuitesTracked().size()
                + " sourceMethods=" + tiaData.getMethodsTracked().size());

        // Phase B - the render, broken into its individual steps. Running the steps in sequence
        // on one generator is equivalent to HtmlReportGenerator.generateReports, so their sum is
        // the whole-render number and each step is attributable on its own.
        File reportOutputDir = new File(args.outDir);
        HtmlReportGenerator generator = new HtmlReportGenerator(args.branch, reportOutputDir);

        long renderTotalMs = 0;
        renderTotalMs += timeStep(timings, "Phase B1 - copyStaticAssets", generator::copyStaticAssets);
        renderTotalMs += timeStep(timings, "Phase B2 - generateSummaryReport",
                () -> generator.generateSummaryReport(tiaData));
        renderTotalMs += timeStep(timings, "Phase B3 - generateTestSuiteReport",
                () -> generator.generateTestSuiteReport(tiaData));
        renderTotalMs += timeStep(timings, "Phase B4 - generateSourceMethodReport",
                () -> generator.generateSourceMethodReport(tiaData));
        renderTotalMs += timeStep(timings, "Phase B5 - generateSourceCodeLandingReport",
                () -> generator.generateSourceCodeLandingReport(tiaData));
        renderTotalMs += timeStep(timings, "Phase B6 - generateLibraryReport",
                () -> generator.generateLibraryReport(tiaData));
        renderTotalMs += timeStep(timings, "Phase B7 - generateHistoryReport",
                () -> generator.generateHistoryReport(tiaData));

        System.out.printf("  %-52s %8d ms%n", "Phase B - generateReports (sum B1-B7)", renderTotalMs);
        timings.record("Phase B - generateReports (sum B1-B7)", renderTotalMs);

        System.out.printf("  %-52s %8d ms%n", "TOTAL (A + B)", loadMs + renderTotalMs);
        timings.record("TOTAL (A + B)", loadMs + renderTotalMs);
    }

    /**
     * Time one render step, print its elapsed milliseconds, record it into the accumulator and
     * return the elapsed time so the caller can sum the render total.
     *
     * @param timings the accumulator the step duration is recorded into
     * @param label the phase label to print and record under
     * @param step the render step to run and time
     * @return the elapsed milliseconds for the step
     */
    private static long timeStep(Timings timings, String label, Runnable step) {
        long start = System.nanoTime();
        step.run();
        long end = System.nanoTime();
        long ms = printPhase(label, start, end);
        timings.record(label, ms);
        return ms;
    }

    /**
     * Print one timed phase as a fixed-width label and elapsed milliseconds.
     *
     * @param label the phase label
     * @param startNanos phase start (from {@link System#nanoTime()})
     * @param endNanos phase end
     * @return the elapsed milliseconds for the phase
     */
    private static long printPhase(String label, long startNanos, long endNanos) {
        long ms = (endNanos - startNanos) / 1_000_000;
        System.out.printf("  %-52s %8d ms%n", label, ms);
        return ms;
    }

    /**
     * Accumulates per-phase durations across iterations so an averaged summary can be printed at
     * the end. Insertion order of the phase labels is preserved so the summary reads in the same
     * order the phases run.
     */
    static final class Timings {
        private final Map<String, List<Long>> byPhase = new LinkedHashMap<>();

        /**
         * Record one phase duration, appending it to the list for that phase label.
         *
         * @param label the phase label
         * @param ms the measured duration in milliseconds
         */
        void record(String label, long ms) {
            byPhase.computeIfAbsent(label, k -> new ArrayList<>()).add(ms);
        }

        /**
         * Compute the mean duration for each recorded phase, preserving the order phases were
         * first recorded in.
         *
         * @return an ordered map of phase label to mean duration in milliseconds
         */
        Map<String, Double> averages() {
            Map<String, Double> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<Long>> entry : byPhase.entrySet()) {
                List<Long> values = entry.getValue();
                double sum = 0;
                for (long v : values) {
                    sum += v;
                }
                result.put(entry.getKey(), sum / values.size());
            }
            return result;
        }
    }

    /**
     * Parsed key=value harness arguments with the profiler's defaults.
     */
    static final class Args {
        String outDb = "/tmp/tia-html-perf";
        String branch = "main";
        int iterations = 1;
        String url;
        String user = "tia";
        String password = "1234";
        String outDir = new File(System.getProperty("java.io.tmpdir"), "tia-html-report-profile").getAbsolutePath();

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
                    case "outDir": a.outDir = value; break;
                    default: throw new IllegalArgumentException("Unknown arg: " + key);
                }
            }
            return a;
        }

        @Override public String toString() {
            return "Args{outDb=" + outDb + ", branch=" + branch + ", iterations=" + iterations
                    + ", url=" + url + ", outDir=" + outDir + "}";
        }
    }
}
