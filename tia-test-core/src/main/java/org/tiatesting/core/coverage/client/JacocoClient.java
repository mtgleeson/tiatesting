package org.tiatesting.core.coverage.client;

import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JacocoClient {

    private static final Logger log = LoggerFactory.getLogger(JacocoClient.class);
    //private static final String DESTFILE = "jacoco-client-testrunner.exec";
    private static final String ADDRESS = "localhost";
    private static final int PORT = 6300;

    private final List<File> classfiles = new ArrayList<>();
    private String name = "TIA Client Coverage Bundle";

    public JacocoClient(){
    }

    public void initialize(){
        loadClasses();
        log.debug("classes size: " + this.classfiles.size());

        try {
            // collect & dump any existing coverage metrics
            collectCoverage();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts the execution data request.
     *
     * @throws IOException
     */
    public List<ClassImpactTracker> collectCoverage() throws IOException {
        long startTime = System.currentTimeMillis();

        // Open a socket to the coverage agent:
        final Socket socket = new Socket(InetAddress.getByName(ADDRESS), PORT);
        final RemoteControlReader reader = new RemoteControlReader(socket.getInputStream());

        SessionInfoStore sessionInfoStore = new SessionInfoStore();
        ExecutionDataStore executionDataStore = new ExecutionDataStore();
        reader.setSessionInfoVisitor(sessionInfoStore);
        reader.setExecutionDataVisitor(executionDataStore);

        final RemoteControlWriter writer = new RemoteControlWriter(socket.getOutputStream());
        // Send a dump coverage command, reset the coverage on the server agent and read the response:
        writer.visitDumpCommand(true, true);

        // execute read logic
        if (!reader.read()) {
            throw new IOException("Socket closed unexpectedly.");
        }

        // session and execution info have been read to our local objects
        IBundleCoverage bundleCoverage = analyze(executionDataStore);
        List<ClassImpactTracker> classesInvoked = collectMethodsCalled(bundleCoverage);

        socket.close();
        log.debug("Time to collect coverage (ms): " + (System.currentTimeMillis() - startTime));
        return classesInvoked;
    }

    private List<ClassImpactTracker> collectMethodsCalled(IBundleCoverage bundleCoverage){
        List<ClassImpactTracker> classesInvoked = new ArrayList<>();

        bundleCoverage.getPackages().forEach( bundlePackage -> {

            if (containsLineCoverage(bundlePackage)){
                bundlePackage.getClasses().forEach( bundleClass -> {

                    if (containsLineCoverage(bundleClass)){
                        String sourceFilename = bundlePackage.getName() + "/" + bundleClass.getSourceFileName();
                        log.trace("Class {} contains line coverage from source file {}", bundleClass.getName(), sourceFilename);
                        List<MethodImpactTracker> methodsImpactedForClass = new ArrayList<>();
                        classesInvoked.add(new ClassImpactTracker(sourceFilename, methodsImpactedForClass));

                        bundleClass.getMethods().forEach( method -> {
                            if (containsLineCoverage(method)){
                                String methodName = bundleClass.getName() + "." + method.getName() + "." + method.getDesc();
                                log.trace("Method contains line coverage {} first: {} last: {}", method.getName(),
                                        method.getFirstLine(), method.getLastLine());
                                methodsImpactedForClass.add(new MethodImpactTracker(methodName,  method.getFirstLine(), method.getLastLine()));
                            }
                        });
                    }
                });
            }
        });

        return classesInvoked;
    }

    private boolean containsLineCoverage(ICoverageNode coverageNode){
        return coverageNode.getLineCounter().getMissedCount() < coverageNode.getLineCounter().getTotalCount();
    }

    private IBundleCoverage analyze(final ExecutionDataStore data) throws IOException {
        final CoverageBuilder builder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(data, builder);
        for (final File f : classfiles) {
            analyzer.analyzeAll(f);
        }
        printNoMatchWarning(builder.getNoMatchClasses());
        return builder.getBundle(name);
    }

    private void printNoMatchWarning(final Collection<IClassCoverage> nomatch) {
        if (!nomatch.isEmpty()) {
            log.error(
                    "[WARN] Some classes do not match with execution data.");
            log.error(
                    "[WARN] For report generation the same class files must be used as at runtime.");
            for (final IClassCoverage c : nomatch) {
                log.error(String.format("[WARN] Execution data for class %s does not match.%n", c.getName()));
            }
        }
    }

    private int getHitCount(final boolean[] data) {
        int count = 0;
        for (final boolean hit : data) {
            if (hit) {
                count++;
            }
        }
        return count;
    }

    private void loadClasses(){
        String classesDirsStr = System.getProperty("tiaClassFilesDirs");
        List<String> classesDirs = classesDirsStr != null ? Arrays.asList(classesDirsStr.split(",")) : null;
        String classExtension = ".class";

        for (String classesDir: classesDirs){
            classesDir = getProjectDir() + classesDir;
            List<File> classFiles = loadFiles(classesDir, classExtension);
            this.classfiles.addAll(classFiles);
        }
    }

    private String getProjectDir(){
        return System.getProperty("tiaProjectDir");
    }

    private List<File> loadFiles(String systemPropertyDirectory, String fileExtension){
        List<File> filteredFiles = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(Paths.get(systemPropertyDirectory))) {
            filteredFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(fileExtension))
                    .map( path -> path.toFile())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return filteredFiles;
    }

}

