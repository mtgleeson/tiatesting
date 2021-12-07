package org.tiatesting.vcs.git;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.tiatesting.vcs.SourceFileDiffContext;
import org.tiatesting.vcs.VCSAnalyzerException;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class GitReader {

    private static final Log log = LogFactory.getLog(GitReader.class);

    private final GitDiffAnalyzer gitDiffAnalyzer;
    private final GitContext gitContext;
    private final GitCheckoutProcessor gitCheckoutProcessor;

    public GitReader(final String gitPath) {
        Repository repository = getFileRepository(gitPath);
        gitContext = new GitContext(repository, readBranchName(repository), readHeadObjectId(repository));
        gitDiffAnalyzer = new GitDiffAnalyzer();
        gitCheckoutProcessor = new GitCheckoutProcessor();
    }

    /**
     * Find all the source code files in a list of revisions from a given commit value to the head of the VCS.
     * For each impacted source code file load the file content from the starting revision and the head revision.
     *
     * @param commitFrom
     * @return
     */
    public List<SourceFileDiffContext> buildDiffFilesContext(final String commitFrom) {
        return gitDiffAnalyzer.buildDiffFilesContext(gitContext, commitFrom);
    }

    public File checkoutSourceAtVersion(String commit){
        return gitCheckoutProcessor.checkoutSourceAtVersion(gitContext, commit);
    }

    /**
     * Get the head commit value for the Git repository.
     *
     * @return
     */
    public String getHeadCommit(){
        return gitContext.getHeadObjectId().getName();
    }

    public String getBranchName(){
        return gitContext.getBranchName();
    }

    private String readBranchName(final Repository repository) {
        try {
            return repository.getFullBranch().replaceAll("/", ".");
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private Repository getFileRepository(final String gitPath){
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(new File(gitPath)) //"my_repo/.git"
                    .build();
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private ObjectId readHeadObjectId(final Repository repository) {
        try {
            return repository.resolve(Constants.HEAD);
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

}
