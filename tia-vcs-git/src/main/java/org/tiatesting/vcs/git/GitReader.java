package org.tiatesting.vcs.git;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class GitReader implements VCSReader {

    private static final Logger log = LoggerFactory.getLogger(GitReader.class);
    public static final String GIT_REPOSITORY_NAME = ".git";

    private final GitDiffAnalyzer gitDiffAnalyzer;
    private final GitContext gitContext;
    private final GitCheckoutProcessor gitCheckoutProcessor;

    public GitReader(final String gitProjectPath) {
        File gitDir = resolveGitDir(gitProjectPath);
        Repository repository = getFileRepository(gitDir);
        gitContext = new GitContext(repository, readBranchName(repository), readHeadObjectId(repository));
        gitDiffAnalyzer = new GitDiffAnalyzer();
        gitCheckoutProcessor = new GitCheckoutProcessor();
    }

    @Override
    public Set<SourceFileDiffContext> buildDiffFilesContext(final String baseChangeNum, final List<String> sourceFilesDirs,
                                                            final List<String> testFilesDirs, final boolean checkLocalChanges) {
        List<String> sourceAndTestFilesDir = new ArrayList<>(sourceFilesDirs);
        sourceAndTestFilesDir.addAll(testFilesDirs);
        return gitDiffAnalyzer.buildDiffFilesContext(gitContext, baseChangeNum, sourceAndTestFilesDir, checkLocalChanges);
    }

    @Override
    public void close() {
        log.debug("Closing the Git Repository resource");
        gitContext.getRepository().close();
    }

    @Override
    public String getHeadCommit(){
        return gitContext.getHeadObjectId().getName();
    }

    @Override
    public String getBranchName(){
        return gitContext.getBranchName();
    }

    private File resolveGitDir(String projectPath) {
        File currentDir;
        try {
            currentDir = new File(projectPath).getCanonicalFile();
        } catch (IOException e) {
            throw new VCSAnalyzerException("Failed to resolve project path: " + projectPath, e);
        }

        while (currentDir != null) {
            File gitDir = new File(currentDir, GIT_REPOSITORY_NAME);
            if (gitDir.exists() && gitDir.isDirectory()) {
                log.info("Found " + GIT_REPOSITORY_NAME + " directory in " + currentDir.getAbsolutePath());
                return gitDir;
            }
            currentDir = currentDir.getParentFile();
        }
        throw new VCSAnalyzerException("Could not find " + GIT_REPOSITORY_NAME
                + " directory in " + projectPath + " or any of its parent directories");
    }

    private String readBranchName(final Repository repository) {
        try {
            return repository.getFullBranch().replaceAll("/", ".");
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private Repository getFileRepository(final File gitDir){
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(gitDir) //"my_repo/.git"
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
