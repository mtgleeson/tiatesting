package org.tiatesting.vcs.git;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

import static org.tiatesting.core.sourcefile.FileExtensions.JAVA_FILE_EXT;
import static org.tiatesting.core.sourcefile.FileExtensions.GROOVY_FILE_EXT;

/**
 * Build the list of source files that have been changed since the previously analyzed commit.
 */
public class GitDiffAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GitDiffAnalyzer.class);

    /**
     * Build the list of files that have changes either since the previously stored commit, or from local uncommited changes.
     * Return the list of SourceFileDiffContext.
     *
     * @param gitContext object used to hold data about a Git repository being analysed by Tia.
     * @param commitFrom the oldest commit number in the range being analysed
     * @param checkLocalChanges should the staged changes
     * @return list of SourceFileDiffContext for the files impacted in the given commit range to head
     */
    protected Set<SourceFileDiffContext> buildDiffFilesContext(GitContext gitContext, String commitFrom,
                                                               boolean checkLocalChanges) {
        ObjectId commitFromObjectId = getObjectId(gitContext, commitFrom);
        ObjectId commitToObjectId = gitContext.getHeadObjectId();

        // get changes from the previously stored commit to HEAD
        Set<SourceFileDiffContext> sourceFileDiffContexts;
        sourceFileDiffContexts = getChangesFromPreviousCommit(gitContext, commitFromObjectId, commitToObjectId);

        if (checkLocalChanges){
            // get the local staged changes compared to local HEAD
            sourceFileDiffContexts.addAll(getStagedChanges(gitContext));
        }

        return sourceFileDiffContexts;
    }

    /**
     * Find all the source code files in a list of revisions from a given commit value to the head of the VCS.
     * For each impacted source code file, load the file content from the starting revision and the head revision.
     *
     * @param gitContext object used to hold data about a Git repository being analysed by Tia.
     * @param commitFrom the oldest commit number in the range being analysed
     * @param commitTo the newest commit number in the range being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Set<SourceFileDiffContext> getChangesFromPreviousCommit(GitContext gitContext,
                                                                     ObjectId commitFrom,
                                                                     ObjectId commitTo){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts;
        sourceFileDiffContexts = getSourceFilesImpactedFromPreviousCommit(gitContext.getRepository(), commitFrom, commitTo);
        log.info("Source files found in the commit range: {}", sourceFileDiffContexts.keySet());

        readFileContentForVersion(gitContext.getRepository(), commitFrom, true, sourceFileDiffContexts);
        readFileContentForVersion(gitContext.getRepository(), commitTo, false, sourceFileDiffContexts);

        return new HashSet<>(sourceFileDiffContexts.values());
    }

    /**
     * Get all source files impacted by changes in a given commit range - commitFrom -> commitTo.
     *
     * @param repository the Git repository being analysed
     * @param commitFrom the oldest commit number in the range being analysed
     * @param commitTo the newest commit number in the range being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Map<String, SourceFileDiffContext> getSourceFilesImpactedFromPreviousCommit(Repository repository,
                                                                                        ObjectId commitFrom,
                                                                                        ObjectId commitTo){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        log.info("Finding the impacted sources code files in Git for the commit range from {} to {}",
                commitFrom.getName(), commitTo.getName());

        try (DiffFormatter diffFormatter = new DiffFormatter(null)) {
            diffFormatter.setRepository(repository);

            for (DiffEntry diffEntry : diffFormatter.scan(commitFrom, commitTo)) {
                buildDiffContextsFromDiffEntries(diffEntry, sourceFileDiffContexts);
            }
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceFileDiffContexts;
    }

    /**
     * Find all the source code files that have been changed locally & staged compared to HEAD.
     * For each impacted source code file, load the file content from the starting revision and the head revision.
     *
     * @param gitContext object used to hold data about a Git repository being analysed by Tia.
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Set<SourceFileDiffContext> getStagedChanges(GitContext gitContext){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts;
        sourceFileDiffContexts = getSourceFilesImpactedFromStagedChanges(gitContext.getRepository());
        log.info("Source files found with staged changes: {}", sourceFileDiffContexts.keySet());

        readFileContentForVersion(gitContext.getRepository(), gitContext.getHeadObjectId(), true, sourceFileDiffContexts);
        readStagedFileContent(gitContext.getRepository(),false, sourceFileDiffContexts);

        return new HashSet<>(sourceFileDiffContexts.values());
    }

    /**
     * Get all source files impacted by local uncommitted changes compared to local HEAD.
     *
     * @param repository the Git repository being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private  Map<String, SourceFileDiffContext> getSourceFilesImpactedFromStagedChanges(Repository repository){
        log.info("Finding the impacted sources code files in Git for local uncommited changes to HEAD");
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        AbstractTreeIterator commitTreeIterator = prepareTreeParser( repository,  Constants.HEAD );
        FileTreeIterator workTreeIterator = new FileTreeIterator( repository );

        try (DiffFormatter diffFormatter = new DiffFormatter(null)) {
            diffFormatter.setRepository(repository);
            for (DiffEntry diffEntry : diffFormatter.scan( commitTreeIterator, workTreeIterator )){
                buildDiffContextsFromDiffEntries(diffEntry, sourceFileDiffContexts);
            }
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceFileDiffContexts;
    }

    private void buildDiffContextsFromDiffEntries(DiffEntry diffEntry, Map<String, SourceFileDiffContext> sourceFileDiffContexts) {
        String diffOldPath = diffEntry.getOldPath();
        String diffNewPath = diffEntry.getNewPath();
        DiffEntry.ChangeType changeType = diffEntry.getChangeType();
        log.trace("Found diff entry: old path: {}, new path: {}, change type: {}", diffOldPath, diffNewPath, changeType);

        if (isFileSourceCode(diffOldPath)){
            // file has been modified or deleted
            buildDiffContext(diffOldPath, diffNewPath, changeType, sourceFileDiffContexts, diffOldPath);
        } else if (isFileSourceCode(diffNewPath)){
            // if the old path doesn't exist but the new path is source code, a new source file has been added
            buildDiffContext(diffOldPath, diffNewPath, changeType, sourceFileDiffContexts, diffNewPath);
        }
    }

    private void buildDiffContext(String diffOldPath, String diffNewPath, DiffEntry.ChangeType changeType,
                                  Map<String, SourceFileDiffContext> sourceFileDiffContexts, String pathForTracking) {
        SourceFileDiffContext diffContext = new SourceFileDiffContext(diffOldPath, diffNewPath,
                convertGitChangeType(changeType));
        sourceFileDiffContexts.put(pathForTracking, diffContext);
    }

    /**
     * Prepare a Git Tree Parser that can be used for scanning for differences
     *
     * @param repository the Git repository being analysed
     * @param ref the commit id reference
     * @return AbstractTreeIterator
     */
    private AbstractTreeIterator prepareTreeParser(Repository repository, String ref) {
        RevTree tree;

        try {
            Ref head = repository.getRefDatabase().findRef(ref);
            RevWalk walk = new RevWalk(repository);
            RevCommit commit = walk.parseCommit(head.getObjectId());
            tree = walk.parseTree(commit.getTree().getId());
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }

        CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
        ObjectReader oldReader = repository.newObjectReader();
        try {
            oldTreeParser.reset(oldReader, tree.getId());
        } catch (IncorrectObjectTypeException e) {
            throw new VCSAnalyzerException(e);
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        } finally {
            oldReader.close();
        }
        return oldTreeParser;
    }

    /**
     * Read the content of a list of source files at a given version.
     * Use a TreeWalk to recursively walk through the repository tree for a version. Read the content for each of the
     * given source files and load it into a map to be used later for diffing.
     *
     * @param repository the Git repository being analysed
     * @param commitObjectId the id of the commit revision we are reading the file content for
     * @param forOriginal is the file content being read used as the 'before' in the diff?
     * @param sourceFilesImpacted the map of source files that were impacted in the diff range
     */
    private void readFileContentForVersion(Repository repository, ObjectId commitObjectId, boolean forOriginal,
                                           Map<String, SourceFileDiffContext> sourceFilesImpacted) {
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(commitObjectId);
            RevTree tree = commit.getTree();

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                while(treeWalk.next()){
                    if (sourceFilesImpacted.containsKey(treeWalk.getPathString())){
                        ObjectId objectId = treeWalk.getObjectId(0);
                        ObjectLoader loader = repository.open(objectId);
                        loader.copyTo(baos);

                        if (forOriginal){
                            sourceFilesImpacted.get(treeWalk.getPathString()).setSourceContentOriginal(baos.toString());
                        } else {
                            sourceFilesImpacted.get(treeWalk.getPathString()).setSourceContentNew(baos.toString());
                        }

                        baos.reset();
                    }
                }
            }

            revWalk.dispose();
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    /**
     * Read the content of a list of source files from the staged changes.
     * Use a TreeWalk to recursively walk through the repository tree for the staged changes.
     * Read the content for each of the given source files and load it into a map to be used later for diffing.
     *
     * @param repository the Git repository being analysed
     * @param forOriginal is the file content being read used as the 'before' in the diff?
     * @param sourceFilesImpacted the map of source files that were impacted in the diff range
     */
    private void readStagedFileContent(Repository repository, boolean forOriginal,
                                       Map<String, SourceFileDiffContext> sourceFilesImpacted) {
        try{
            DirCache index = repository.lockDirCache();

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree( new DirCacheIterator(index));
                treeWalk.setRecursive(true);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                while(treeWalk.next()){
                    if (sourceFilesImpacted.containsKey(treeWalk.getPathString())){
                        ObjectId objectId = treeWalk.getObjectId(0);
                        ObjectLoader loader = repository.open(objectId);
                        loader.copyTo(baos);

                        if (forOriginal){
                            sourceFilesImpacted.get(treeWalk.getPathString()).setSourceContentOriginal(baos.toString());
                        } else {
                            sourceFilesImpacted.get(treeWalk.getPathString()).setSourceContentNew(baos.toString());
                        }

                        baos.reset();
                    }
                }
            }finally {
                index.unlock();
            }
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private ObjectId getObjectId(GitContext gitContext, String commit){
        try {
            return gitContext.getRepository().resolve(commit);
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private ChangeType convertGitChangeType(DiffEntry.ChangeType gitChangeType){
        switch(gitChangeType){
            case ADD:
                return ChangeType.ADD;
            case MODIFY:
                return ChangeType.MODIFY;
            case DELETE:
                return ChangeType.DELETE;
            case RENAME:
                return ChangeType.RENAME;
            case COPY:
                return ChangeType.COPY;
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
