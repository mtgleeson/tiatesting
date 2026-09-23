package org.tiatesting.core.coverage.client;

import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.sourcefile.FileExtensions;
import org.tiatesting.core.util.StringUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class JacocoClient {

    private static final Logger log = LoggerFactory.getLogger(JacocoClient.class);
    //private static final String DESTFILE = "jacoco-client-testrunner.exec";
    private static final String ADDRESS = "localhost";
    private static final int PORT = 6300;

    /**
     * Reads the bytecode for one class on demand. Backed either by a {@code .class} file on disk or
     * a class entry inside a library jar. Kept lazy so that per-suite coverage collection only reads
     * the handful of classes a suite actually executed, rather than holding every class' bytes in
     * memory for the JVM's lifetime.
     */
    @FunctionalInterface
    private interface ClassBytesSource {
        /**
         * Reads and returns this class' raw bytecode.
         *
         * @return the class file bytes
         * @throws IOException if the underlying file or jar entry cannot be read
         */
        byte[] read() throws IOException;
    }

    /**
     * Index from VM class name (e.g. {@code org/foo/Bar}) to a lazy source for that class' bytecode.
     * Built once in {@link #initialize()} from the configured class dirs and library jars, then used
     * by {@link #analyze(ExecutionDataStore)} to look up only the classes that were executed.
     */
    private final Map<String, ClassBytesSource> classBytesByVmName = new HashMap<>();
    private String name = "TIA Client Coverage Bundle";

    public JacocoClient(){
    }

    public void initialize(){
        loadClasses();
        log.debug("classes size: " + this.classBytesByVmName.size());

        try {
            // collect & dump any existing coverage metrics
            collectCoverage();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts the execution data request.
     * @return CoverageResult
     * @throws IOException IO Exception
     */
    public CoverageResult collectCoverage() throws IOException {
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
        CoverageResult coverageResult = collectMethodsCalled(bundleCoverage);

        socket.close();
        log.debug("Time to collect coverage (ms): " + (System.currentTimeMillis() - startTime));
        return coverageResult;
    }

    /**
     * Walks a coverage bundle and builds the {@link CoverageResult} for a suite: the per-source-file
     * {@link ClassImpactTracker}s (with the ids of the methods that have line coverage) and the
     * catalogue of every method in the covered classes. Only classes and methods with line coverage
     * are recorded, so a bundle built from the scoped {@link #analyze(ExecutionDataStore)} yields the
     * same result as one built from a full-classpath analysis.
     *
     * @param bundleCoverage the coverage bundle for the executed classes
     * @return the coverage result for the suite
     */
    CoverageResult collectMethodsCalled(IBundleCoverage bundleCoverage){
        CoverageResult coverageResult = new CoverageResult();
        // track classes by source name - we could have multiple coverage results for the same class when there are nested and inner classes.
        // we want to combine these into one class impact tracker for the source file.
        Map<String, ClassImpactTracker> classImpactTrackers = new HashMap<>();

        bundleCoverage.getPackages().forEach( bundlePackage -> {

            if (containsLineCoverage(bundlePackage.getLineCounter())){
                bundlePackage.getClasses().forEach( bundleClass -> {

                    if (containsLineCoverage(bundleClass.getLineCounter())){
                        String sourceFilename = bundlePackage.getName() + "/" + bundleClass.getSourceFileName();
                        log.trace("Class {} contains line coverage from source file {}", bundleClass.getName(), sourceFilename);

                        ClassImpactTracker classImpactTracker = classImpactTrackers.get(sourceFilename);
                        MethodIdSet methodsImpactedForClass = classImpactTracker == null
                                ? new MethodIdSet()
                                : classImpactTracker.getMethodsImpacted();

                        if (classImpactTracker == null){
                            classImpactTrackers.put(sourceFilename, new ClassImpactTracker(sourceFilename, methodsImpactedForClass));
                        }

                        bundleClass.getMethods().forEach( method -> {
                            String methodName = bundleClass.getName() + "." + method.getName() + "." + method.getDesc();
                            MethodImpactTracker methodTracker = new MethodImpactTracker(methodName,  method.getFirstLine(), method.getLastLine());
                            coverageResult.getAllMethodsClassesInvoked().put(methodTracker.hashCode(), methodTracker);

                            if (containsLineCoverage(method.getLineCounter())){
                                methodsImpactedForClass.add(methodTracker.hashCode());
                                log.trace("Method contains line coverage {} first: {} last: {}", method.getName(),
                                        method.getFirstLine(), method.getLastLine());
                            }
                        });
                    }
                });
            }
        });

        coverageResult.getClassesInvoked().addAll(classImpactTrackers.values());

        return coverageResult;
    }

    private boolean containsLineCoverage(ICounter counter){
        return counter.getMissedCount() < counter.getTotalCount();
    }

    /**
     * Builds the coverage bundle for a suite by analyzing only the classes that were actually
     * executed. The dumped {@code ExecutionDataStore} holds one {@link ExecutionData} per executed
     * class (the dump command resets the agent, so this is the delta since the previous suite), so
     * we look each executed class' bytecode up in {@link #classBytesByVmName} and analyze just those
     * rather than re-parsing every class on the classpath. Classes with no execution data produce no
     * coverage and were discarded downstream anyway, so the resulting bundle is equivalent to a
     * full-classpath analysis - it just skips the parsing work for classes the suite never touched.
     *
     * @param data the execution data dumped from the coverage agent for the finished suite
     * @return the coverage bundle covering the executed classes
     * @throws IOException if an executed class' bytecode cannot be read
     */
    IBundleCoverage analyze(final ExecutionDataStore data) throws IOException {
        final CoverageBuilder builder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(data, builder);
        for (final ExecutionData executionData : data.getContents()) {
            final ClassBytesSource source = classBytesByVmName.get(executionData.getName());
            if (source == null) {
                log.trace("Executed class {} not found in the configured class dirs/jars; skipping",
                        executionData.getName());
                continue;
            }
            // Analyzer correlates by the class id (CRC of these bytes), so passing the executed
            // class' current bytecode reproduces today's name+id matching; a stale class still
            // surfaces via getNoMatchClasses() exactly as before.
            analyzer.analyzeClass(source.read(), executionData.getName());
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

    /**
     * Builds the {@link #classBytesByVmName} index from the configured class dirs
     * ({@code tiaClassFilesDirs}) and any library jars ({@code tiaLibraryJars}). Only class names and
     * their byte sources are recorded here; the bytecode itself is read lazily per suite, so this is
     * cheap regardless of how many classes are on the classpath.
     */
    void loadClasses(){
        String classesDirsStr = System.getProperty("tiaClassFilesDirs");
        List<String> classesDirs = classesDirsStr != null ? new ArrayList<>(Arrays.asList(classesDirsStr.split(","))) : null;
        StringUtil.sanitizeInputArray(classesDirs);

        for (String classesDir: classesDirs){
            indexClassDir(getProjectDir() + classesDir);
        }

        loadLibraryJars();
    }

    /**
     * Indexes every {@code .class} file under a directory into {@link #classBytesByVmName}, keyed by
     * VM class name (the path relative to the directory root with the {@code .class} suffix removed
     * and file separators normalised to {@code /}). Each entry reads its bytes lazily from disk.
     *
     * @param classesDir the compiled-classes directory to walk
     */
    private void indexClassDir(final String classesDir){
        final String classExtension = "." + FileExtensions.CLASS_FILE_EXT;
        final Path root = Paths.get(classesDir);

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(classExtension))
                    .forEach(p -> {
                        final File file = p.toFile();
                        String relative = root.relativize(p).toString().replace(File.separatorChar, '/');
                        if (!isIndexableClassEntry(relative)){
                            return;
                        }
                        String vmName = relative.substring(0, relative.length() - classExtension.length());
                        classBytesByVmName.put(vmName, () -> Files.readAllBytes(file.toPath()));
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Decides whether a {@code .class} resource path is worth indexing. A dumped
     * {@code ExecutionDataStore} only ever names ordinary classes, so entries that can never match -
     * the versioned copies a multi-release jar nests under {@code META-INF/versions/<n>/...} and the
     * {@code module-info} descriptor - are excluded. This keeps the index (and the logged class
     * count) to names a suite's execution data can actually resolve.
     *
     * @param resourcePath the class entry path with {@code /} separators (a jar entry name or a
     *                     directory-relative path), including the {@code .class} suffix
     * @return {@code true} if the entry should be indexed, {@code false} to skip it
     */
    private boolean isIndexableClassEntry(final String resourcePath){
        if (resourcePath.startsWith("META-INF/")){
            return false;
        }
        String simpleName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        return !simpleName.equals("module-info." + FileExtensions.CLASS_FILE_EXT);
    }

    /**
     * Load any library JARs declared via the {@code tiaLibraryJars} system property (comma-separated
     * absolute paths). Each jar's {@code .class} entries are indexed into {@link #classBytesByVmName}
     * by VM class name, reading the entry bytes lazily when the class is actually executed. The paths
     * are not prefixed with the TIA project dir because they are expected to be absolute paths into
     * the local Maven/Gradle artifact cache.
     */
    private void loadLibraryJars(){
        String libraryJarsStr = System.getProperty("tiaLibraryJars");
        if (libraryJarsStr == null || libraryJarsStr.isEmpty()){
            return;
        }

        List<String> libraryJars = new ArrayList<>(Arrays.asList(libraryJarsStr.split(",")));
        StringUtil.sanitizeInputArray(libraryJars);

        for (String jarPath : libraryJars){
            if (jarPath.isEmpty()){
                continue;
            }
            File jarFile = new File(jarPath);
            if (jarFile.isFile()){
                indexJar(jarFile);
                log.debug("Indexing library JAR for the JacocoClient: " + jarPath);
            } else {
                log.warn("tiaLibraryJars entry not found, skipping: " + jarPath);
            }
        }
    }

    /**
     * Indexes every {@code .class} entry in a jar into {@link #classBytesByVmName}, keyed by VM class
     * name (the entry name with the {@code .class} suffix removed). Each entry reads its bytes lazily
     * from the jar when the class is executed, so touched library classes are read on demand rather
     * than eagerly loading the whole jar into memory.
     *
     * @param jar the library jar to index
     */
    private void indexJar(final File jar){
        final String classExtension = "." + FileExtensions.CLASS_FILE_EXT;

        try (JarFile jarFile = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()){
                JarEntry entry = entries.nextElement();
                final String entryName = entry.getName();
                if (entry.isDirectory() || !entryName.toLowerCase().endsWith(classExtension)
                        || !isIndexableClassEntry(entryName)){
                    continue;
                }
                String vmName = entryName.substring(0, entryName.length() - classExtension.length());
                classBytesByVmName.put(vmName, () -> readJarEntry(jar, entryName));
            }
        } catch (IOException e) {
            log.warn("Failed to index library JAR, skipping: " + jar.getAbsolutePath(), e);
        }
    }

    /**
     * Reads the bytecode for a single class entry from a jar. Opened per read; only the few library
     * classes a suite executes are ever read, so this stays off the bulk hot path.
     *
     * @param jar the jar containing the entry
     * @param entryName the full entry name (e.g. {@code org/foo/Bar.class})
     * @return the entry's bytes
     * @throws IOException if the jar or entry cannot be read, or the entry is missing
     */
    private byte[] readJarEntry(final File jar, final String entryName) throws IOException {
        try (JarFile jarFile = new JarFile(jar)) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null){
                throw new IOException("Entry " + entryName + " not found in jar " + jar.getAbsolutePath());
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                return readAllBytes(in);
            }
        }
    }

    /**
     * Reads an input stream fully into a byte array.
     *
     * @param in the stream to drain
     * @return the stream's bytes
     * @throws IOException if reading fails
     */
    private byte[] readAllBytes(final InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1){
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private String getProjectDir(){
        return System.getProperty("tiaProjectDir");
    }

    /**
     * Exposes the VM class names currently indexed for coverage analysis. Intended for tests that
     * assert which classes were picked up from the configured class dirs and library jars.
     *
     * @return an unmodifiable view of the indexed VM class names
     */
    Set<String> indexedClassNames(){
        return Collections.unmodifiableSet(classBytesByVmName.keySet());
    }

}

