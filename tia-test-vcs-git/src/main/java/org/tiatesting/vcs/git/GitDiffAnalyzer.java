package org.tiatesting.vcs.git;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSAnalyzerException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Build the list of source files that have been changed since the previously analyzed commit.
 */
public class GitDiffAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GitDiffAnalyzer.class);
    private static final String JAVA_FILE_EXTENSION = ".java";

    /**
     * Find all the source code files in a list of revisions from a given commit value to the head of the VCS.
     * For each impacted source code file load the file content from the starting revision and the head revision.
     *
     * @param gitContext
     * @param commitFrom
     * @return
     */
    protected List<SourceFileDiffContext> buildDiffFilesContext(GitContext gitContext, String commitFrom) {
        ObjectId commitFromObjectId = getObjectId(gitContext, commitFrom);
        ObjectId commitToObjectId = gitContext.getHeadObjectId();

        Map<String, SourceFileDiffContext> sourceFileDiffContexts = getSourceFilesImpacted(gitContext.getRepository(),
                commitFromObjectId, commitToObjectId);
        log.info(String.format("Source files found in the commit range: %s", sourceFileDiffContexts.keySet()));

        readFileContentForVersion(gitContext, commitFromObjectId, sourceFileDiffContexts, true);
        readFileContentForVersion(gitContext, commitToObjectId, sourceFileDiffContexts, false);

        return new ArrayList<>(sourceFileDiffContexts.values());
    }

    /**
     * Scan the repository for all source files in the given range of commit ids.
     * Load the source files into a map to be used for reading the content of the files at the given versions.
     *
     * @param repository
     * @param commitFrom
     * @param commitTo
     * @return
     */
    private Map<String, SourceFileDiffContext> getSourceFilesImpacted(Repository repository, ObjectId commitFrom, ObjectId commitTo) {
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        log.info(String.format("Finding the impacted sources code files in Git for the commit range from %s to %s",
                commitFrom.getName(), commitTo.getName()));

        try (DiffFormatter diffFormatter = new DiffFormatter(null)) {
            diffFormatter.setRepository(repository);

            for (DiffEntry diffEntry : diffFormatter.scan(commitFrom, commitTo)) {
                if (diffEntry.getOldPath().toLowerCase().endsWith(JAVA_FILE_EXTENSION)){
                    SourceFileDiffContext diffContext = new SourceFileDiffContext(diffEntry.getOldPath(),
                            diffEntry.getNewPath(), convertGitChangeType(diffEntry.getChangeType()));
                    sourceFileDiffContexts.put(diffEntry.getOldPath(), diffContext);
                }
            }
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceFileDiffContexts;
    }

    /**
     * Read the content of a list of source files at a given version.
     * Use a TreeWalk to recursively walk through the repository tree for a version. Read the content for each of the
     * given source files and load it into a map to be used later for diffing.
     *
     * @param gitContext
     * @param commitObjectId
     * @param sourceFilesImpacted
     * @param forOriginal
     */
    private void readFileContentForVersion(GitContext gitContext, ObjectId commitObjectId,
                                           Map<String, SourceFileDiffContext> sourceFilesImpacted, boolean forOriginal) {
        try (RevWalk revWalk = new RevWalk(gitContext.getRepository())) {
            RevCommit commit = revWalk.parseCommit(commitObjectId);
            RevTree tree = commit.getTree();

            try (TreeWalk treeWalk = new TreeWalk(gitContext.getRepository())) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                while(treeWalk.next()){
                    if (sourceFilesImpacted.containsKey(treeWalk.getPathString())){
                        ObjectId objectId = treeWalk.getObjectId(0);
                        ObjectLoader loader = gitContext.getRepository().open(objectId);
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
}
