package org.tiatesting.vcs.perforce;

import com.perforce.p4java.core.IChangelist;
import com.perforce.p4java.core.IChangelistSummary;
import com.perforce.p4java.core.file.*;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.ConnectionException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.exception.RequestException;
import com.perforce.p4java.option.server.GetChangelistsOptions;
import com.perforce.p4java.option.server.GetExtendedFilesOptions;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
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

        Integer clFrom = Integer.valueOf(baseCl) + 1; // don't include the current CL in the range to check - we've already analyzed this CL
        sourceFileDiffContexts = getSourceFilesImpactedFromPreviousSubmit(p4Context.getP4Connection(), clFrom.toString(), clTo,
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
     * Get all source files impacted by changes in a given commit range - commitFrom -> commitTo.
     *
     * @param p4Connection the Perforce connection being used for the analysis.
     * @param clFrom the oldest changelist number in the range being analysed
     * @param clTo the newest changelist number in the range being analysed
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Map<String, SourceFileDiffContext> getSourceFilesImpactedFromPreviousSubmit(P4Connection p4Connection,
                                                                                        String clFrom, String clTo,
                                                                                        List<IFileSpec> sourceAndTestFilesSpecs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        log.info("Finding the impacted sources code files in P4 for the changelist range from {} to {}", clFrom, clTo);

        GetChangelistsOptions options = new GetChangelistsOptions();
        String streamName = p4Connection.getClient().getStream();
        String filePaths = streamName + "/...@" + clFrom + "," + clTo;

        try {
            List<IChangelistSummary> changeLists = p4Connection.getServer().getChangelists(
                    FileSpecBuilder.makeFileSpecList(filePaths), options);

            // The list of file changes should be ordered based on time the change was made, with the latest changes at the
            // end of the list (so the last change action takes precedence when a file is changed in multiple CLs).
            changeLists.sort(Comparator.comparing(IChangelistSummary::getId));

            if (changeLists.isEmpty()){
                log.warn("Couldn't find any changelists for the P4 file paths {}", filePaths);
            }

            for (IChangelistSummary changelistSummary : changeLists) {
                buildDiffContextsForFilesInCL(p4Connection, changelistSummary, sourceFileDiffContexts, sourceAndTestFilesSpecs);
            }
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
                sourceCodeFiles = (List<IExtendedFileSpec>) buildDiffContextsForFileSpecs(p4Connection, openedFiles, sourceFileDiffContexts,
                        sourceAndTestFilesSpecs);
            }

        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceCodeFiles;
    }

    private void buildDiffContextsForFilesInCL(P4Connection p4Connection, IChangelistSummary changelistSummary,
                                               Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                               List<IFileSpec> sourceAndTestFilesSpecs) {
        try {
            IChangelist changelist = p4Connection.getServer().getChangelist(changelistSummary.getId());
            List<IFileSpec> fileSpecs = changelist.getFiles(false);
            buildDiffContextsForFileSpecs(p4Connection, fileSpecs, sourceFileDiffContexts, sourceAndTestFilesSpecs);
        } catch (ConnectionException | RequestException | AccessException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private List<? extends IFileSpec> buildDiffContextsForFileSpecs(P4Connection p4Connection,
                                                                    List<? extends IFileSpec> fileSpecs,
                                                                    Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                                    List<IFileSpec> sourceAndTestFilesSpecs){
        List<IFileSpec> sourceCodeFiles = filterValidSourceOrTestFiles(fileSpecs, sourceAndTestFilesSpecs);
        List<String> sourceCodeFileDepotPaths = sourceCodeFiles.stream().map(file -> file.getDepotPathString()).collect(Collectors.toList());
        Map<String, IFileSpec> localFileSpecs;

        try {
            // use P4 where command to find out the local paths for the changed files (local or submitted)
            List<IFileSpec> whereFileSpecs = p4Connection.getClient().where(FileSpecBuilder.makeFileSpecList(sourceCodeFileDepotPaths));
            localFileSpecs = whereFileSpecs.stream().collect(Collectors.toMap( file -> file.getDepotPathString(), Function.identity()));
        } catch (ConnectionException | AccessException e) {
            throw new VCSAnalyzerException(e);
        }

        sortFileChanges(sourceCodeFiles);
        for (IFileSpec fileSpec: sourceCodeFiles){
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
    private List<IFileSpec> filterValidSourceOrTestFiles(List<? extends IFileSpec> allFileSpecs, List<IFileSpec> sourceAndTestFilesSpecs){
        List<IFileSpec> validSourceOrTestFiles = new ArrayList<>();

        for (IFileSpec fileSpec: allFileSpecs) {
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
    private void sortFileChanges(List<IFileSpec> sourceCodeFiles){
        Collections.sort(sourceCodeFiles, Comparator.comparing(IFileSpec::getChangelistId));
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
                if (tiaMappingPath.indexOf(sourceAndTestFilesSpec.getDepotPathString()) > -1){
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

            loadFileSpecsContentIntoDiffContext(revisionFileSpecs, sourceFileDiffContexts, forOriginal);
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

    private void loadFileSpecsContentIntoDiffContext(List<IFileSpec> revisionFileSpecs, Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                     boolean forOriginal) {
        try {
            for (IFileSpec fileSpec : revisionFileSpecs) {
                if (fileSpec.getDepotPathString() == null){
                    // The file doesn't exist in the CL. This could be due to the file being deleted in the original CL
                    // as well in the new CL (i.e. a merge CL bringing the delete action into the stream where it was already deleted).
                    log.info("No file found in P4 for the CL. Looking up the original:  {}", forOriginal);
                    continue;
                }

                InputStream inputStream = fileSpec.getContents(true);
                if (inputStream == null) {
                    log.warn("No input stream for {}", fileSpec.getDepotPathString());
                    continue;
                }

                String fileContent = IOUtils.toString(inputStream, StandardCharsets.UTF_8.name());
                loadFileContentIntoDiffContext(sourceFileDiffContexts, forOriginal, fileSpec.getDepotPathString(), fileContent);
            }
        } catch (P4JavaException | IOException e) {
            throw new VCSAnalyzerException(e);
        }
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
