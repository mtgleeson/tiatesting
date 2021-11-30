package org.tiatesting.vcs.git;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.tiatesting.vcs.ChangeType;
import org.tiatesting.vcs.SourceFileDiffContext;
import org.tiatesting.vcs.VCSAnalyzerException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class GitReader {

    private static final Log log = LogFactory.getLog(GitReader.class);
    private static final String JAVA_FILE_EXTENSION = ".java";

    private final Repository repository;
    private final Git git;
    private final String branchName;
    private final ObjectId headObjectId;

    public GitReader(String gitPath) {
        repository = getFileRepository(gitPath);
        git = new Git(repository);
        branchName = readBranchName();
        headObjectId = readHeadObjectId();
    }

    /**
     * Find all the source code files in a list of revisions from a given commit value to the head of the VCS.
     * For each impacted source code file load the file content from the starting revision and the head revision.
     *
     * @param commitFrom
     * @return
     */
    public List<SourceFileDiffContext> buildDiffFilesContext(String commitFrom) {
        ObjectId commitFromObjectId = getObjectId(commitFrom);
        ObjectId commitToObjectId = headObjectId;

        Map<String, SourceFileDiffContext> sourceFileDiffContexts = getSourceFilesImpacted(repository, commitFromObjectId, commitToObjectId);
        System.out.println(sourceFileDiffContexts);

            // loop over each java file, get source file content at 'from' commit. get source file at 'to' commit.
            // do a diff and extract the line numbers changed.
            // read 'from' source content - load as java file and for each line number - get the method names.

        readFileContentForVersion(commitFromObjectId, sourceFileDiffContexts, true);
        readFileContentForVersion(commitToObjectId, sourceFileDiffContexts, false);

        return new ArrayList<SourceFileDiffContext>(sourceFileDiffContexts.values());
    }

    private Map<String, SourceFileDiffContext> getSourceFilesImpacted(Repository repository, ObjectId commitFrom, ObjectId commitTo) {
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        log.info(String.format("Finding the impacted sources code files in Git for the commit range from %s to %s",
                commitFrom.getName(), commitTo.getName()));

/*
        final List<DiffEntry> diffs = git.diff()
                .setOldTree(prepareTreeParser(repository, oldCommit))
                .setNewTree(prepareTreeParser(repository, commitTo))
                .call();

        System.out.println("Found: " + diffs.size() + " differences");
        for (DiffEntry diff : diffs) {
            System.out.println("Diff: " + diff.getChangeType() + ": " +
                    (diff.getOldPath().equals(diff.getNewPath()) ? diff.getNewPath() : diff.getOldPath() + " -> " + diff.getNewPath()));
        }
*/
        // -----


        //FileOutputStream stdout = new FileOutputStream(FileDescriptor.out);

        try (DiffFormatter diffFormatter = new DiffFormatter(null)) {
            diffFormatter.setRepository(repository);
            for (DiffEntry diffEntry : diffFormatter.scan(commitFrom, commitTo)) {
                if (diffEntry.getOldPath().toLowerCase().endsWith(JAVA_FILE_EXTENSION)){
                    sourceFileDiffContexts.put(diffEntry.getOldPath(),
                            new SourceFileDiffContext(diffEntry.getOldPath(), diffEntry.getNewPath(), convertGitChangeType(diffEntry.getChangeType())));
                    // diffFormatter.format(diffFormatter.toFileHeader(diffEntry));
                }
            }
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceFileDiffContexts;
    }

    /*
    public Iterable<RevCommit> getCommitsSince(String sinceCommitName) throws IOException, GitAPIException {
        return getCommitsSince(sinceCommitName, headObjectId.getName());
    }

    public Iterable<RevCommit> getCommitsSince(String sinceCommitName, String toCommitName) throws IOException, GitAPIException {
        //String treeName = "refs/heads/master"; // tag or branch
        ObjectId sinceCommitObjectId = repository.resolve(sinceCommitName);
        ObjectId toCommitObjectId = repository.resolve(toCommitName);

        //for (RevCommit commit : git.log().addRange(sinceCommitObjectId, toCommitObjectId).call()) {
        //    System.out.println("commit: " + commit.getName());
        //}

        return git.log().addRange(sinceCommitObjectId, toCommitObjectId).call();
    }
     */

    /*
    public void getJavaFilesForCommits(Iterable<RevCommit> commits) throws IOException {
        for (RevCommit commit : commits) {
            System.out.println("commit: " + commit.getName());

            ObjectId treeId = commit.getTree().getId();

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.reset(treeId);
                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    System.out.println("path: " + path);
                    // TODO check if path is a java file and if so add to a list.
                    // TODO do a diff on the file and extract method names
                }
            }
        }
    }
    */

    public void readFileContentForVersion(ObjectId commitObjectId, Map<String, SourceFileDiffContext> sourceFilesImpacted, boolean forOriginal) {
        //Map<String, List<String>> fileLinesForFiles = new HashMap<>();

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

    public String getBranchName(){
        return this.branchName;
    }

    private String readBranchName() {
        try {
            return repository.getFullBranch().replaceAll("/", ".");
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private Repository getFileRepository(String gitPath){
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(new File(gitPath)) //"my_repo/.git"
                    .build();
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private ObjectId readHeadObjectId() {
        try {
            return repository.resolve(Constants.HEAD);
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    public String getHeadCommit() {
        return headObjectId.getName();
    }

    private ObjectId getObjectId(String commit){
        try {
            return repository.resolve(commit);
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
