package org.tiatesting.plugin.coverage.client;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JacocoClient {

    private static final Log log = LogFactory.getLog(JacocoClient.class);
    //private static final String DESTFILE = "jacoco-client-testrunner.exec";
    private static final String ADDRESS = "localhost";
    private static final int PORT = 6300;

    private final List<File> classfiles;
    private final List<File> sourcefiles;
    private String name = "TIA Client Coverage Bundle";

    public JacocoClient(){
        this.classfiles = loadClasses();
        this.sourcefiles = loadSourceFiles();
        log.debug("classes size: " + this.classfiles.size());
        log.debug("sources size: " + this.sourcefiles.size());

        try {
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
    public Set<String> collectCoverage() throws IOException {
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
        Set<String> methodsCalled = collectMethodsCalled(bundleCoverage);

        socket.close();
        log.debug("Time to collect coverage (ms): " + (System.currentTimeMillis() - startTime));
        return methodsCalled;
    }

    private Set<String> collectMethodsCalled(IBundleCoverage bundleCoverage){
        Set<String> methodsCalled = new HashSet<>();

        bundleCoverage.getPackages().forEach( bundlePackage -> {
            if (containsLineCoverage(bundlePackage)){
                bundlePackage.getClasses().forEach( bundleClass -> {
                    if (containsLineCoverage(bundleClass)){
                        bundleClass.getMethods().forEach( method -> {
                            if (containsLineCoverage(method)){
                                methodsCalled.add(bundleClass.getName() + "." + method.getName() + "." + method.getDesc());
                            }
                        });
                    }
                });
            }
        });

        return methodsCalled;
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

    private void analyzeClientExecutionData(){

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

    private List<File> loadClasses(){
        String classesDir = System.getProperty("tiaClassFiles");
        String classExtension = ".class";
        return loadFiles(classesDir, classExtension);
    }

    private List<File> loadSourceFiles(){
        String sourceFilesDir = System.getProperty("tiaSourceFiles");
        String sourceExtension = ".java";
        return loadFiles(sourceFilesDir, sourceExtension);
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

