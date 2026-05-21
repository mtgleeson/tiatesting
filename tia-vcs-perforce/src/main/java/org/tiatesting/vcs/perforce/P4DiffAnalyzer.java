package org.tiatesting.vcs.perforce;

import com.perforce.p4java.core.file.*;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.ConnectionException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.option.server.GetExtendedFilesOptions;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.tiatesting.core.sourcefile.FileExtensions.GROOVY_FILE_EXT;
import static org.tiatesting.core.sourcefile.FileExtensions.JAVA_FILE_EXT;

/**
 * Build the list of source files that have been changed since the previously analyzed commit.
 */
public class P4DiffAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(P4DiffAnalyzer.class);

    /**
     * Build the list of files that have changes either since the previously stored submit, or from local changes.
     * Return the list of SourceFileDiffContext.
     *
     * @param p4Context object used to hold data about a P4 repository being analysed by Tia.
     * @param baseCl the current changelist stored in the mapping
     * @param sourceAndTestFiles the list of source code and test files for the source project being analysed
     * @param checkLocalChanges should local changes be analyzed for test selection
     * @return list of SourceFileDiffContext for the files impacted in the given commit range to head
     */
    protected Set<SourceFileDiffContext> buildDiffFilesContext(final P4Context p4Context, final String baseCl,
                                                               final List<String> sourceAndTestFiles,
                                                               final boolean checkLocalChanges) {
        String clTo = p4Context.getHeadCL();
        List<IFileSpec> sourceAndTestFilesSpecs = getSourceAndTestFilesSpecs(p4Context.getP4Connection(), sourceAndTestFiles);

        // get changes from the previously stored CL number to HEAD
        Set<SourceFileDiffContext> sourceFileDiffContexts = new HashSet<>();

        if (checkLocalChanges){
            // get the local changes compared to local HEAD
            sourceFileDiffContexts.addAll(getLocalChanges(p4Context, sourceAndTestFilesSpecs));
        } else {
            // get the changes since the CL that was last run with Tia
            sourceFileDiffContexts.addAll(getChangesSinceLastRunCL(p4Context, baseCl, clTo, sourceAndTestFilesSpecs));
        }

        return sourceFileDiffContexts;
    }

    /**
     * Find the P4 file spec for each source and test file directory. This gives us both the depot path and local path.
     *
     * @param p4Connection the Perforce connection being used for the analysis.
     * @param sourceAndTestFiles the list of source and test directories for the source project being analysed
     * @return list of p4 file specs representing the source and test directories
     */
    private List<IFileSpec> getSourceAndTestFilesSpecs(final P4Connection p4Connection, final List<String> sourceAndTestFiles){
        List<IFileSpec> fileSpecs;
        try {
            fileSpecs = p4Connection.getClient().where(FileSpecBuilder.makeFileSpecList(sourceAndTestFiles));
        } catch (ConnectionException | AccessException e) {
            throw new VCSAnalyzerException(e);
        }

        return fileSpecs;
    }

    /**
     * Find all the source code files in a list of revisions from a given submit number to the head of the VCS.
     * For each impacted source code file, load the file content from the starting revision and the head revision.
     *
     * @param p4Context object used to hold data about a Perforce server being analysed by Tia.
     * @param baseCl the current changelist stored in the mapping
     * @param clTo the newest changelist number in the range being analysed
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Set<SourceFileDiffContext> getChangesSinceLastRunCL(P4Context p4Context, String baseCl, String clTo,
                                                                List<IFileSpec> sourceAndTestFilesSpecs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts;

        if (baseCl.equals(clTo)){
            log.info("Workspace Head is at the same version tracked by Tia, no submitted differences to check for.");
            return new HashSet<>();
        }

        int clFrom = Integer.parseInt(baseCl) + 1; // don't include the current CL in the range to check - we've already analyzed this CL
        sourceFileDiffContexts = getSourceFilesImpactedFromPreviousSubmit(p4Context.getP4Connection(), Integer.toString(clFrom), clTo,
                sourceAndTestFilesSpecs);
        log.info("Source files found in the commit range: {}", sourceFileDiffContexts.keySet().stream().map( key ->
                convertDepotPathToTiaPath(key, sourceAndTestFilesSpecs)).collect(Collectors.toList()));

        if (!sourceFileDiffContexts.isEmpty()) {
            readFileContentForVersion(p4Context.getP4Connection(), baseCl, true, sourceFileDiffContexts);
            readFileContentForVersion(p4Context.getP4Connection(), clTo, false, sourceFileDiffContexts);
        }
        return new HashSet<>(sourceFileDiffContexts.values());
    }

    /**
     * Find every file changed in the changelist range {@code clFrom..clTo} and build the
     * corresponding {@link SourceFileDiffContext} entries.
     *
     * <p>Resolves the file changes in a single {@code p4 files -a //stream/...@clFrom,clTo}
     * round-trip via {@link com.perforce.p4java.server.IOptionsServer#getDepotFiles} with
     * {@code allRevs=true}. The {@code allRevs} flag tells Perforce to return <em>every</em>
     * revision of each file <strong>within the CL range</strong> (not every revision ever) -
     * so a file that was added and then edited in the range appears twice in the result, once
     * for the ADD CL and once for the EDIT CL. {@code sortFileChanges} (called inside
     * {@link #buildDiffContextsForFileSpecs}) orders these per-CL entries oldest-first so the
     * action-override logic in {@code buildDiffContext} can collapse ADD-then-EDIT to ADD
     * correctly.
     *
     * <p>The earlier {@code allRevs=false} form returned only the highest revision in the
     * range per file - so an ADD-then-EDIT file came back with action=EDIT alone and was
     * mis-classified as MODIFY. The forOriginal=true pass then tried to fetch the file at
     * baseCl where it didn't exist, producing "No file found in P4" log lines.
     *
     * @param p4Connection the Perforce connection being used for the analysis.
     * @param clFrom the oldest changelist number in the range being analysed
     * @param clTo the newest changelist number in the range being analysed
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the
     *                                source project being analysed
     * @return map keyed by depot path with the value being the matching {@link SourceFileDiffContext}
     */
    Map<String, SourceFileDiffContext> getSourceFilesImpactedFromPreviousSubmit(P4Connection p4Connection,
                                                                                        String clFrom, String clTo,
                                                                                        List<IFileSpec> sourceAndTestFilesSpecs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        log.info("Finding the impacted sources code files in P4 for the changelist range from {} to {}", clFrom, clTo);

        String streamName = p4Connection.getClient().getStream();
        String filePaths = streamName + "/...@" + clFrom + "," + clTo;

        try {
            // allRevs=true so files changed in multiple CLs in the range come back one entry
            // per (file, CL) - required for the action-override semantics in buildDiffContext
            // (ADD-then-EDIT in the range collapses to ADD).
            List<IFileSpec> changedFiles = p4Connection.getServer().getDepotFiles(
                    FileSpecBuilder.makeFileSpecList(filePaths), true);

            if (changedFiles == null || changedFiles.isEmpty()){
                log.warn("Couldn't find any file changes for the P4 file paths {}", filePaths);
                return sourceFileDiffContexts;
            }

            buildDiffContextsForFileSpecs(p4Connection, changedFiles, sourceFileDiffContexts, sourceAndTestFilesSpecs);
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceFileDiffContexts;
    }

    /**
     * Find all the source code files that have been changed locally compared to HEAD.
     * For each impacted source code file, load the file content from the starting revision and the head revision.
     *
     * @param p4Context object used to hold data about a Perforce server being analysed by Tia.
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Set<SourceFileDiffContext> getLocalChanges(P4Context p4Context, List<IFileSpec> sourceAndTestFilesSpecs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        List<IExtendedFileSpec> changedLocalFiles = getSourceFilesImpactedFromLocalChanges(p4Context.getP4Connection(),
                sourceFileDiffContexts, sourceAndTestFilesSpecs);

        log.info("Source files found with local changes: {}", sourceFileDiffContexts.keySet().stream().map( key ->
                convertDepotPathToTiaPath(key, sourceAndTestFilesSpecs)).collect(Collectors.toList()));

        if (!changedLocalFiles.isEmpty()){
            readFileContentForVersion(p4Context.getP4Connection(), p4Context.getHeadCL(), true, sourceFileDiffContexts);
            loadLocalFileContentIntoDiffContext(changedLocalFiles, sourceFileDiffContexts, false);
        }

        return new HashSet<>(sourceFileDiffContexts.values());
    }

    /**
     * Get all source files impacted by local submitted changes compareD.
     *
     * @param p4Connection the Perforce connection being used for the analysis.
     * @param sourceFileDiffContexts the map of source files that were impacted in the diff range
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return list of files that have been changes in the local workspace.
     */
    private List<IExtendedFileSpec> getSourceFilesImpactedFromLocalChanges(P4Connection p4Connection,
                                                                           Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                                           List<IFileSpec> sourceAndTestFilesSpecs){
        log.info("Finding the impacted sources code files in P4 for local changes compared to HEAD");
        List<IExtendedFileSpec> sourceCodeFiles = new ArrayList<>();

        try {
            GetExtendedFilesOptions options = new GetExtendedFilesOptions();
            FileStatOutputOptions fstatOutputOptions = new FileStatOutputOptions();
            fstatOutputOptions.setOpenedFiles(true);
            options.setOutputOptions(fstatOutputOptions);
            List<IExtendedFileSpec> openedFiles = p4Connection.getServer().getExtendedFiles(FileSpecBuilder.makeFileSpecList("//..."), options);

            if (openedFiles == null || openedFiles.isEmpty()){
                log.info("No local workspace changes found");
            } else {
                sourceCodeFiles = buildDiffContextsForFileSpecs(p4Connection, openedFiles, sourceFileDiffContexts,
                        sourceAndTestFilesSpecs);
            }

        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceCodeFiles;
    }

    private <T extends IFileSpec> List<T> buildDiffContextsForFileSpecs(P4Connection p4Connection,
                                                                        List<T> fileSpecs,
                                                                        Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                                        List<IFileSpec> sourceAndTestFilesSpecs){
        List<T> sourceCodeFiles = filterValidSourceOrTestFiles(fileSpecs, sourceAndTestFilesSpecs);
        List<String> sourceCodeFileDepotPaths = sourceCodeFiles.stream().map(IFileSpec::getDepotPathString).collect(Collectors.toList());
        Map<String, IFileSpec> localFileSpecs;

        try {
            // use P4 where command to find out the local paths for the changed files (local or submitted)
            List<IFileSpec> whereFileSpecs = p4Connection.getClient().where(FileSpecBuilder.makeFileSpecList(sourceCodeFileDepotPaths));
            localFileSpecs = whereFileSpecs.stream().collect(Collectors.toMap(IFileSpec::getDepotPathString, Function.identity()));
        } catch (ConnectionException | AccessException e) {
            throw new VCSAnalyzerException(e);
        }

        sortFileChanges(sourceCodeFiles);
        for (T fileSpec: sourceCodeFiles){
            String depotPath = fileSpec.getDepotPathString();
            FileAction changeType = fileSpec.getAction();
            String localPath = localFileSpecs.get(depotPath).getLocalPathString();

            if (isFileAdded(changeType)){
                // if the old path doesn't exist but the new path is source code, a new source file has been added
                addDiffContext(null, localPath, changeType, sourceFileDiffContexts, depotPath);
            } else {
                // file has been modified or deleted
                addDiffContext(localPath, null, changeType, sourceFileDiffContexts, depotPath);
            }
        }

        return sourceCodeFiles;
    }

    /**
     * Filter out any files that have changed that aren't source or test files.
     * We only track an analyze source and test files in Tia.
     *
     * @param allFileSpecs
     * @param sourceAndTestFilesSpecs
     * @return
     */
    private <T extends IFileSpec> List<T> filterValidSourceOrTestFiles(List<T> allFileSpecs, List<IFileSpec> sourceAndTestFilesSpecs){
        List<T> validSourceOrTestFiles = new ArrayList<>();

        for (T fileSpec: allFileSpecs) {
            String depotPath = fileSpec.getDepotPathString();

            if (isFileSourceCode(depotPath) && isFileInSourceOrTestDir(depotPath, sourceAndTestFilesSpecs)){
                validSourceOrTestFiles.add(fileSpec);
            }
        }

        return validSourceOrTestFiles;
    }

    /**
     * When we retrieve the list of changes from between points, it will return all the individual CLs.
     * To process the CL's and understand the file changes correctly, we need to process the files in order of change.
     * This allows us to understand the state of the file at the beginning of the range of CL's, and at the end.
     *
     * For example, if a file was added and then edited, the change type for this file for processing within Tia should
     * be added (not edited).
     *
     * @param sourceCodeFiles
     */
    private void sortFileChanges(List<? extends IFileSpec> sourceCodeFiles){
        sourceCodeFiles.sort(Comparator.comparing(IFileSpec::getChangelistId));
    }

    /**
     * Check if the file exists in one of the source code or test file directories. If not, we don't want to proces
     * the file. The file might below to another application outside the project being analyzed.
     *
     * @param depotPath the depot path of the file
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return does the file exist in one of the source code or test directories being analysed
     */
    private boolean isFileInSourceOrTestDir(String depotPath, List<IFileSpec> sourceAndTestFilesSpecs){
        for (IFileSpec sourceAndTestFilesSpec: sourceAndTestFilesSpecs){
            if (depotPath.contains(sourceAndTestFilesSpec.getDepotPathString())){
                return true;
            }
        }
        log.trace("Skipping file not found in a source or test directory being analysed: {}", depotPath);
        return false;
    }

    /**
     * Convert the P4 depot path to the format needed for Tia mappings. Strip away the depot up to the src folder.
     * i.e. From:
     * //apps/example/tia-test-project/src/main/java/com/example/Car.java
     * to
     * src/main/java/com/example/Car.java
     *
     * @param depotPath
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return
     */
    private String convertDepotPathToTiaPath(String depotPath, List<IFileSpec> sourceAndTestFilesSpecs){
        String tiaMappingPath = depotPath;

        if (tiaMappingPath != null){
            for (IFileSpec sourceAndTestFilesSpec : sourceAndTestFilesSpecs){
                if (tiaMappingPath.contains(sourceAndTestFilesSpec.getDepotPathString())){
                    // use +1 to remove the leading '/'
                    tiaMappingPath = tiaMappingPath.substring(sourceAndTestFilesSpec.getDepotPathString().length() + 1);
                    break;
                }
            }
        }

        return tiaMappingPath;
    }

    private void addDiffContext(String diffOldPath, String diffNewPath, FileAction changeType,
                                Map<String, SourceFileDiffContext> sourceFileDiffContexts, String pathForTracking) {
        SourceFileDiffContext diffContext = buildDiffContext(diffOldPath, diffNewPath, convertP4ChangeType(changeType),
                sourceFileDiffContexts, pathForTracking);

        if (diffContext != null){
            sourceFileDiffContexts.put(pathForTracking, diffContext);
        }
    }

    /**
     * Build the diff context for a source file that has changed. The diff context will be used to load the before and
     * after file content used to perform the diff and understand which methods have changed, and which tests to run.
     *
     * In some scenarios the change action is overridden. For Perforce we load all the individual CL's from the start of
     * the range to the end and then iterate over them sequentially from oldest to newest. In some cases a file can be
     * changed multiple times within the CL range. i.e. when Tia hasn't run for a while. If Tia is run after every commit
     * this situation shouldn't happen.
     *
     * We want the end change action to reflect the over change from the beginning as we only keep 1 diff context for the file.
     * i.e. if a file was added then edited in the CL range, we want the final action type for the diff context to be
     * 'added' as that's the final state when compared to the state at the beginning of the CL range.
     *
     * Override mapping:
     * 1st commit -> 2nd commit = overridden action
     * add -> edit = add
     * add -> delete = delete (treat the file like it never existed in the CL range)
     * edit -> edit = edit
     * edit -> delete = delete
     * delete -> add = edit
     *
     * @param diffOldPath
     * @param diffNewPath
     * @param currentChangeType
     * @param sourceFileDiffContexts
     * @param pathForTracking
     * @return the SourceFileDiffContext for the file change, or null if the file change should not be processed by Tia for selection.
     */
    private SourceFileDiffContext buildDiffContext(String diffOldPath, String diffNewPath, ChangeType currentChangeType,
                                                   Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                   String pathForTracking){

        SourceFileDiffContext newDiffContext = new SourceFileDiffContext(diffOldPath, diffNewPath, currentChangeType);
        SourceFileDiffContext previousDiffContext = sourceFileDiffContexts.get(pathForTracking);

        if (previousDiffContext != null){
            ChangeType previousChangeType = previousDiffContext.getChangeType();

            // if a file is both added and edited in the CL range, keep the action as add
            if (previousChangeType == ChangeType.ADD && currentChangeType == ChangeType.MODIFY){
                log.debug("Converting change type from MODIFY back to ADD given the file didn't exist at the beginning of the CL range: {}", pathForTracking);
                newDiffContext = previousDiffContext;
            }

            // if a file existed before, and was deleted and then added back in the CL range, treat the action as edit
            if (previousChangeType == ChangeType.DELETE && currentChangeType == ChangeType.ADD){
                log.debug("Converting change type from ADD back to MODIFY as the file previously existed at the start of the CL range: {}", pathForTracking);
                previousDiffContext.setChangeType(ChangeType.MODIFY);
                newDiffContext = previousDiffContext;
            }

            // delete file from list (never existed previously, doesn't exist now, no Tia tests to run)
            if (previousChangeType == ChangeType.ADD && currentChangeType == ChangeType.DELETE){
                log.debug("Not adding a file to the tracked source file diffs due to it being added and deleted in the CL range: {}", pathForTracking);
                newDiffContext = null;
            }
        }

        return newDiffContext;
    }

    /**
     * Read the content of a list of source files at a given version.
     * Read the content for each of the given source files and load it into a map to be used later for diffing.
     *
     * p4Connection the Perforce connection being used for the analysis.
     * @param cl the id of the changelist revision we are reading the file content for
     * @param forOriginal is the file content being read used as the 'before' in the diff?
     * @param sourceFileDiffContexts the map of source files that were impacted in the diff range
     */
    private void readFileContentForVersion(P4Connection p4Connection, String cl, boolean forOriginal,
                                           Map<String, SourceFileDiffContext> sourceFileDiffContexts) {
        try {
            List<String> filePaths = new ArrayList<>();
            sourceFileDiffContexts.forEach((depotPath, sourceFileDiffContext) -> {
                // don't try read the original content for files that were added
                if (forOriginal && sourceFileDiffContext.getChangeType() != ChangeType.ADD){
                    filePaths.add(depotPath + "@" + cl);
                }

                // don't try read the new content for files that were deleted
                if (!forOriginal && sourceFileDiffContext.getChangeType() != ChangeType.DELETE){
                    filePaths.add(depotPath + "@" + cl);
                }
            });

            List<IFileSpec> searchFileSpecs = FileSpecBuilder.makeFileSpecList(filePaths);
            List<IFileSpec> revisionFileSpecs = p4Connection.getServer().getDepotFiles(searchFileSpecs, false);

            if (revisionFileSpecs == null || revisionFileSpecs.isEmpty()){
                throw new VCSAnalyzerException("Couldn't find the source files in Perforce for revision " + cl);
            }

            loadFileSpecsContentIntoDiffContext(p4Connection, revisionFileSpecs, sourceFileDiffContexts, forOriginal, cl);
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private void loadLocalFileContentIntoDiffContext(List<IExtendedFileSpec> sourceCodeFiles,
                                                     Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                     boolean forOriginal){
        for (IExtendedFileSpec fileSpec : sourceCodeFiles){
            File file = new File(fileSpec.getOriginalPathString());
            try {
                String fileContent = FileUtils.readFileToString(file, "UTF-8");
                loadFileContentIntoDiffContext(sourceFileDiffContexts, forOriginal, fileSpec.getDepotPathString(), fileContent);
            } catch (IOException e) {
                throw new VCSAnalyzerException(e);
            }
        }
    }

    /**
     * Load content for every file in {@code revisionFileSpecs} at the given changelist into
     * the matching {@link SourceFileDiffContext}. Fetches all files in a single
     * {@code p4 print} round-trip per version - replaces the older per-file loop that made
     * one round-trip per file.
     *
     * <p>Implementation: call {@code IServer.execStreamCmd("print", argv)} directly with
     * a {@code String[]} of {@code //depot/path@<CL>} arguments. The higher-level
     * {@code getFileContents(List<IFileSpec>, opts)} API can't be used here because p4java's
     * internal {@code IFileSpec -> wire args} conversion drops the revision (it reads
     * {@code IFileSpec.getDepotPathString()}, which is {@code null} for specs constructed
     * from a {@code "//path#rev"} string) so the server always returns head. Going through
     * {@code execStreamCmd} with explicit argv puts the annotation on the wire verbatim -
     * the exact equivalent of running {@code p4 print //path1@1234 //path2@1234} from the
     * command line.
     *
     * <p>The {@code @CL} annotation (rather than {@code #rev}) is used because the file
     * specs returned by {@code getDepotFiles(...@CL, false)} don't reliably populate a
     * revision-number field across p4java versions and file-action types. The exact field
     * (e.g. {@code endRevision} vs {@code headRev}) that carries the resolved revision
     * varies, and for newly-added files at their first revision the populated field can be
     * {@code -1} or {@code 0} - both of which produce a bogus {@code #rev} suffix that
     * {@code p4 print} can't parse, dropping that file silently from the batched response.
     * Using {@code @CL} sidesteps the ambiguity entirely: Perforce resolves the per-file
     * revision at that CL on the server side, the same way {@code getDepotFiles} originally
     * resolved it.
     *
     * <p>The returned stream is the concatenation of every file's {@code p4 print} output:
     * each file is prefixed by a header line of the form
     * {@code //depot/path#rev - <action> change <CL> (<type>)} followed by the raw file
     * content bytes. {@link #parseBatchedPrintStream} locates the header line positions in
     * the byte buffer and slices the content for each file as the bytes between two
     * consecutive header lines (preserving line endings, leading blank lines and trailing
     * newlines verbatim - important for line-level diff accuracy).
     *
     * @param p4Connection the Perforce connection used to call {@code execStreamCmd}
     * @param revisionFileSpecs the file specs returned by {@code getDepotFiles} - each carries
     *                          a depot path for the file at the requested CL; specs with a
     *                          null depot path (file missing at this revision) are skipped
     * @param sourceFileDiffContexts the diff-context map keyed by depot path; each context's
     *                               {@code sourceContentOriginal} or {@code sourceContentNew}
     *                               is populated based on {@code forOriginal}
     * @param forOriginal {@code true} when fetching the "before" version, {@code false} for
     *                    the "after" version
     * @param cl the changelist id used as the {@code @CL} annotation in the argv (same id
     *           that was passed to {@code getDepotFiles} to resolve the specs)
     */
    private void loadFileSpecsContentIntoDiffContext(P4Connection p4Connection,
                                                     List<IFileSpec> revisionFileSpecs,
                                                     Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                     boolean forOriginal,
                                                     String cl) {
        List<String> expectedDepotPaths = new ArrayList<>(revisionFileSpecs.size());
        List<String> argvList = new ArrayList<>(revisionFileSpecs.size());
        for (IFileSpec fileSpec : revisionFileSpecs) {
            String depotPath = fileSpec.getDepotPathString();
            if (depotPath == null) {
                // The file doesn't exist in the CL. With allRevs=true on the range query in
                // getSourceFilesImpactedFromPreviousSubmit, this should be rare - the
                // action-override semantics in buildDiffContext correctly classify
                // ADD-then-EDIT-in-range as ADD, so the forOriginal=true pass skips it via
                // the changeType filter rather than reaching this branch. The remaining
                // legitimate case is merge-of-delete (file deleted in both the original CL
                // and the new CL).
                log.info("No file found in P4 for the CL {} (forOriginal={})",
                        fileSpec.getChangelistId(), forOriginal);
                continue;
            }
            expectedDepotPaths.add(depotPath);
            argvList.add(depotPath + "@" + cl);
        }
        if (argvList.isEmpty()) {
            return;
        }

        byte[] streamBytes;
        try {
            InputStream stream = p4Connection.getServer().execStreamCmd("print",
                    argvList.toArray(new String[0]));
            if (stream == null) {
                throw new VCSAnalyzerException("Batched p4 print returned a null stream for "
                        + argvList.size() + " files (forOriginal=" + forOriginal + ")");
            }
            try {
                streamBytes = readAllBytes(stream);
            } finally {
                stream.close();
            }
        } catch (P4JavaException | IOException e) {
            throw new VCSAnalyzerException(e);
        }

        Map<String, String> contentByDepotPath = parseBatchedPrintStream(streamBytes, expectedDepotPaths);

        for (String depotPath : expectedDepotPaths) {
            String content = contentByDepotPath.get(depotPath);
            if (content == null) {
                // We asked p4 print for this depot path but no header for it appeared in the
                // response. Either p4java returned a partial stream or the demux missed a
                // header - either way, silently leaving the diff context with null content
                // would mis-select tests, so fail fast.
                throw new VCSAnalyzerException("Batched p4 print returned no content for depot path "
                        + depotPath + " (forOriginal=" + forOriginal + ")");
            }
            loadFileContentIntoDiffContext(sourceFileDiffContexts, forOriginal, depotPath, content);
        }
    }

    /**
     * Read every byte of {@code stream} into an in-memory byte array. The batched
     * {@code p4 print} output is bounded by the total size of the changed source files in
     * the range - kilobytes to a few megabytes in normal use - so buffering in memory is
     * fine and keeps the parse loop simple.
     *
     * @param stream the input stream to drain (caller closes it)
     * @return the byte contents
     * @throws IOException if the underlying read fails
     */
    private byte[] readAllBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /**
     * Locate each {@code //depot/path#rev - ...} header line in {@code streamBytes} and slice
     * the content for each file as the verbatim bytes between consecutive header line
     * starts. Slicing by byte position (rather than reading line-by-line and rejoining)
     * preserves line endings, leading blank lines and trailing newlines exactly as the
     * server emitted them - critical so the line-level diff comparison in
     * {@code MethodImpactAnalyzer} sees the same byte shape that the serial per-file fetch
     * would have produced.
     *
     * @param streamBytes the raw stream contents
     * @param expectedDepotPaths depot paths the caller requested (used to validate header
     *                           candidates - prevents a content line that happens to look
     *                           like a header from being mistaken for one)
     * @return content keyed by depot path; files whose header was not found in the stream
     *         are absent from the map
     */
    /**
     * Matches the portion of a p4 print header that comes <em>after</em> {@code <path>#}, up
     * to the end of the header line. Shape: {@code <digits> - <whatever> (<type>)}. The
     * {@code <whatever>} segment varies by action ({@code edit change 1234},
     * {@code integrate change 1234 from //other#3}, etc.) so we don't pin its internal
     * structure; only the bookend digits, the {@code  - }, and the trailing parenthesised
     * file-type segment are load-bearing.
     */
    private static final Pattern P4_PRINT_HEADER_TAIL =
            Pattern.compile("\\d+ - .* \\([^)]+\\)");

    private Map<String, String> parseBatchedPrintStream(byte[] streamBytes,
                                                        List<String> expectedDepotPaths) {
        String stream = new String(streamBytes, StandardCharsets.UTF_8);

        // Phase 1: locate every expected file's header by direct substring search and
        // header-shape validation. We do NOT require the header to be preceded by '\n' -
        // a source file whose content lacks a trailing '\n' makes the next file's header
        // physically adjacent to the prior file's content tail in the stream bytes
        // ("<prior content tail>//nextFile#1 - add change 1234 (text)\n"). Java source
        // files commonly omit the trailing newline, so this case must be handled.
        //
        // Instead of a boundary check, we validate the full header-line shape after the
        // path's '#' (digits, ' - ', whatever, ' (<type>)'). The chance of source code
        // accidentally containing this exact substring sequence for one of THIS run's
        // expected depot paths is vanishingly small.
        List<int[]> headerSpans = new ArrayList<>();   // [headerStartIdx, contentStartIdx]
        List<String> headerDepotPaths = new ArrayList<>();
        for (String expectedPath : expectedDepotPaths) {
            String needle = expectedPath + "#";
            int searchFrom = 0;
            while (searchFrom <= stream.length() - needle.length()) {
                int idx = stream.indexOf(needle, searchFrom);
                if (idx < 0) {
                    break;
                }

                int afterHash = idx + needle.length();
                int nlIdx = stream.indexOf('\n', afterHash);
                int lineEnd = (nlIdx == -1) ? stream.length() : nlIdx;
                int lineDataEnd = lineEnd;
                if (lineDataEnd > afterHash && stream.charAt(lineDataEnd - 1) == '\r') {
                    lineDataEnd--; // ignore CR for header-shape validation
                }

                Matcher m = P4_PRINT_HEADER_TAIL.matcher(stream);
                m.region(afterHash, lineDataEnd);
                if (m.matches()) {
                    int contentStart = (nlIdx == -1) ? stream.length() : nlIdx + 1;
                    headerSpans.add(new int[]{idx, contentStart});
                    headerDepotPaths.add(expectedPath);
                    break;  // first valid header for this path wins; move to next path
                }
                searchFrom = idx + 1;
            }
        }

        // Phase 2: sort header positions in stream order (p4's response order isn't
        // guaranteed to match the order of expectedDepotPaths), then slice content for
        // each file as the bytes between its header's end and the next header's start.
        List<Integer> orderedIndices = new ArrayList<>(headerSpans.size());
        for (int i = 0; i < headerSpans.size(); i++) {
            orderedIndices.add(i);
        }
        orderedIndices.sort((a, b) -> Integer.compare(headerSpans.get(a)[0], headerSpans.get(b)[0]));

        Map<String, String> contentByDepotPath = new HashMap<>();
        for (int i = 0; i < orderedIndices.size(); i++) {
            int spanIdx = orderedIndices.get(i);
            int contentStart = headerSpans.get(spanIdx)[1];
            int contentEnd = (i < orderedIndices.size() - 1)
                    ? headerSpans.get(orderedIndices.get(i + 1))[0]
                    : stream.length();
            contentByDepotPath.put(headerDepotPaths.get(spanIdx), stream.substring(contentStart, contentEnd));
        }
        return contentByDepotPath;
    }

    private void loadFileContentIntoDiffContext(Map<String, SourceFileDiffContext> sourceFileDiffContexts, boolean forOriginal,
                                                String depotPath, String fileContent) {
        if (forOriginal) {
            sourceFileDiffContexts.get(depotPath).setSourceContentOriginal(fileContent);
        } else {
            sourceFileDiffContexts.get(depotPath).setSourceContentNew(fileContent);
        }
    }

    private boolean isFileAdded(FileAction changeType){
        return convertP4ChangeType(changeType) == ChangeType.ADD;
    }

    private ChangeType convertP4ChangeType(FileAction p4ChangeType){
        switch(p4ChangeType){
            case ADD:
            case MOVE_ADD:
            case ADDED:
            case BRANCH:
                return ChangeType.ADD;
            case EDIT:
            case INTEGRATE:
            case EDIT_FROM:
            case EDIT_IGNORED:
            case UPDATED:
            case REFRESHED:
            case RESOLVED:
            case REPLACED:
                return ChangeType.MODIFY;
            case DELETE:
            case MOVE_DELETE:
            case DELETED:
                return ChangeType.DELETE;
        }

        return null;
    }

    /**
     * Check if a file is a source code file.
     * i.e. Is it a Java  or Groovy file.
     *
     * @param fileName
     * @return
     */
    private boolean isFileSourceCode(String fileName) {
        return fileName.toLowerCase().endsWith("." + JAVA_FILE_EXT) ||
                fileName.toLowerCase().endsWith("." + GROOVY_FILE_EXT);
    }
}
