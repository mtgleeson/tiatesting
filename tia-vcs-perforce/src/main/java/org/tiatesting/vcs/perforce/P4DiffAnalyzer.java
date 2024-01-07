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
import java.util.stream.Collectors;

import static org.tiatesting.core.sourcefile.FileExtensions.GROOVY_FILE_EXT;
import static org.tiatesting.core.sourcefile.FileExtensions.JAVA_FILE_EXT;

/**
 * Build the list of source files that have been changed since the previously analyzed commit.
 */
public class P4DiffAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(P4DiffAnalyzer.class);

    /**
     * Build the list of files that have changes either since the previously stored submit, or from shelved changes.
     * Return the list of SourceFileDiffContext.
     *
     * @param p4Context object used to hold data about a P4 repository being analysed by Tia.
     * @param clFrom the oldest changelist number in the range being analysed
     * @param sourceFilesDirs the list of source directories for the source project being analysed
     * @param checkUnsubmittedChanges should shelved changes be analyzed for test selection
     * @return list of SourceFileDiffContext for the files impacted in the given commit range to head
     */
    protected Set<SourceFileDiffContext> buildDiffFilesContext(P4Context p4Context, String clFrom, final List<String> sourceFilesDirs,
                                                               boolean checkUnsubmittedChanges) {
        String clTo = p4Context.getHeadCL();

        // get changes from the previously stored CL number to HEAD
        Set<SourceFileDiffContext> sourceFileDiffContexts;
        sourceFileDiffContexts = getChangesFromPreviousSubmit(p4Context, clFrom, clTo, sourceFilesDirs);

        if (checkUnsubmittedChanges){
            // get the local shelved changes compared to local HEAD
            sourceFileDiffContexts.addAll(getShelvedChanges(p4Context, sourceFilesDirs));
        }

        return sourceFileDiffContexts;
    }

    /**
     * Find all the source code files in a list of revisions from a given submit number to the head of the VCS.
     * For each impacted source code file, load the file content from the starting revision and the head revision.
     *
     * @param p4Context object used to hold data about a Perforce server being analysed by Tia.
     * @param clFrom the oldest changelist number in the range being analysed
     * @param clTo the newest changelist number in the range being analysed
     * @param sourceFilesDirs the list of source directories for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Set<SourceFileDiffContext> getChangesFromPreviousSubmit(P4Context p4Context, String clFrom, String clTo,
                                                                    List<String> sourceFilesDirs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts;

        if (clFrom.equals(clTo)){
            log.info("Workspace Head is at the same version tracked by Tia, no submitted differences to check for.");
            return new HashSet<>();
        }

        sourceFileDiffContexts = getSourceFilesImpactedFromPreviousSubmit(p4Context.getP4Connection(), clFrom, clTo, sourceFilesDirs);
        log.info("Source files found in the commit range: {}", sourceFileDiffContexts.keySet().stream().map( key -> convertDepotPathToTiaPath(key, sourceFilesDirs)).collect(Collectors.toList()));

        readFileContentForVersion(p4Context.getP4Connection(), clFrom, true, sourceFileDiffContexts);
        readFileContentForVersion(p4Context.getP4Connection(), clTo, false, sourceFileDiffContexts);

        return new HashSet<>(sourceFileDiffContexts.values());
    }

    /**
     * Get all source files impacted by changes in a given commit range - commitFrom -> commitTo.
     *
     * @param p4Connection the Perforce connection being used for the analysis.
     * @param clFrom the oldest changelist number in the range being analysed
     * @param clTo the newest changelist number in the range being analysed
     * @param sourceFilesDirs the list of source directories for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Map<String, SourceFileDiffContext> getSourceFilesImpactedFromPreviousSubmit(P4Connection p4Connection,
                                                                                        String clFrom, String clTo,
                                                                                        List<String> sourceFilesDirs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        log.info("Finding the impacted sources code files in P4 for the changelist range from {} to {}", clFrom, clTo);

        GetChangelistsOptions options = new GetChangelistsOptions();
        String streamName = p4Connection.getClient().getStream();
        String filePaths = streamName + "/...@" + clFrom + "," + clTo;

        try {
            List<IChangelistSummary> changeLists = p4Connection.getServer().getChangelists(
                    FileSpecBuilder.makeFileSpecList(filePaths), options);
            if (changeLists.isEmpty()){
                log.warn("Couldn't find any changelists for the P4 file paths {}", filePaths);
            }

            for (IChangelistSummary changelistSummary : changeLists) {
                buildDiffContextsForFilesInCL(p4Connection, changelistSummary, sourceFileDiffContexts, sourceFilesDirs);
            }
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceFileDiffContexts;
    }

    /**
     * Find all the source code files that have been changed locally & staged compared to HEAD.
     * For each impacted source code file, load the file content from the starting revision and the head revision.
     *
     * @param p4Context object used to hold data about a Perforce server being analysed by Tia.
     * @param sourceFilesDirs the list of source directories for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Set<SourceFileDiffContext> getShelvedChanges(P4Context p4Context, List<String> sourceFilesDirs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        List<IExtendedFileSpec> changedLocalFiles = getSourceFilesImpactedFromLocalChanges(p4Context.getP4Connection(),
                sourceFileDiffContexts, sourceFilesDirs);
        log.info("Source files found with staged changes: {}", sourceFileDiffContexts.keySet().stream().map( key -> convertDepotPathToTiaPath(key, sourceFilesDirs)).collect(Collectors.toList()));

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
     * @param sourceFilesDirs the list of source directories for the source project being analysed
     * @return list of files that have been changes in the local workspace.
     */
    private List<IExtendedFileSpec> getSourceFilesImpactedFromLocalChanges(P4Connection p4Connection,
                                                                           Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                                           List<String> sourceFilesDirs){
        log.info("Finding the impacted sources code files in P4 for local shelved changes compared to HEAD");
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
                sourceCodeFiles = (List<IExtendedFileSpec>) buildDiffContextsForFileSpecs(openedFiles, sourceFileDiffContexts, sourceFilesDirs);
            }

        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceCodeFiles;
    }

    private void buildDiffContextsForFilesInCL(P4Connection p4Connection, IChangelistSummary changelistSummary,
                                               Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                               List<String> sourceFilesDirs) {
        try {
            IChangelist changelist = p4Connection.getServer().getChangelist(changelistSummary.getId());
            List<IFileSpec> fileSpecs = changelist.getFiles(false);
            buildDiffContextsForFileSpecs(fileSpecs, sourceFileDiffContexts, sourceFilesDirs);
        } catch (ConnectionException | RequestException | AccessException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private List<? extends IFileSpec> buildDiffContextsForFileSpecs(List<? extends IFileSpec> fileSpecs,
                                                          Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                          List<String> sourceFilesDirs){
        List<IFileSpec> sourceCodeFiles = new ArrayList<>();

        for (IFileSpec fileSpec: fileSpecs){
            String depotPath = fileSpec.getDepotPathString();
            FileAction changeType = fileSpec.getAction();
            String tiaPath = convertDepotPathToTiaPath(depotPath, sourceFilesDirs);

            if (!isFileSourceCode(tiaPath)){
                continue;
            }

            if (isFileAdded(changeType)){
                // if the old path doesn't exist but the new path is source code, a new source file has been added
                buildDiffContext(null, tiaPath, changeType, sourceFileDiffContexts, depotPath);
            } else {
                // file has been modified or deleted
                buildDiffContext(tiaPath, null, changeType, sourceFileDiffContexts, depotPath);
            }

            sourceCodeFiles.add(fileSpec);
        }

        return sourceCodeFiles;
    }

    /**
     * Convert the P4 depot path to the format needed for Tia mappings. Strip away the depot up to the src folder.
     * i.e. From:
     * //apps/example/tia-test-project/src/main/java/com/example/Car.java
     * to
     * src/main/java/com/example/Car.java
     *
     * @param depotPath
     * @param sourceFilesDirs
     * @return
     */
    private String convertDepotPathToTiaPath(String depotPath, List<String> sourceFilesDirs){
        String tiaMappingPath = depotPath;

        if (tiaMappingPath != null){
            for (String sourceFilesDir : sourceFilesDirs){
                if (tiaMappingPath.indexOf(sourceFilesDir) > -1){
                    // use +1 to remove the leading '/'
                    tiaMappingPath = tiaMappingPath.substring(tiaMappingPath.indexOf(sourceFilesDir) + 1);
                    break;
                }
            }
        }

        return tiaMappingPath;
    }

    private void buildDiffContext(String diffOldPath, String diffNewPath, FileAction changeType,
                                  Map<String, SourceFileDiffContext> sourceFileDiffContexts, String pathForTracking) {
        SourceFileDiffContext diffContext = new SourceFileDiffContext(diffOldPath, diffNewPath,
                convertP4ChangeType(changeType));
        sourceFileDiffContexts.put(pathForTracking, diffContext);
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
            for (String depotPaths : sourceFileDiffContexts.keySet()){
                filePaths.add(depotPaths + "@" + cl);
            }

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
                InputStream inputStream = fileSpec.getContents(true);
                if (inputStream == null) {
                    log.info("No input stream for {}", fileSpec.getDepotPathString());
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
        return changeType == FileAction.ADD || changeType == FileAction.MOVE_ADD || changeType == FileAction.ADDED;
    }

    private ChangeType convertP4ChangeType(FileAction p4ChangeType){
        switch(p4ChangeType){
            case ADD:
            case MOVE_ADD:
            case ADDED:
                return ChangeType.ADD;
            case EDIT:
            case INTEGRATE:
            case BRANCH:
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
