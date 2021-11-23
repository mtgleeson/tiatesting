package org.tiatesting.vcs.git;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class GitReader {

    private static Log log = LogFactory.getLog(GitReader.class);

    private final Repository repository;
    private final Git git;
    private final String branchName;
    private final ObjectId headObjectId;

    public GitReader(String gitPath) throws IOException {
        repository = new FileRepositoryBuilder()
                .setGitDir(new File(gitPath)) //"my_repo/.git"
                .build();

        git = new Git(repository);
        branchName = readBranchName();
        headObjectId = readHeadObjectId();
    }

    public Iterable<RevCommit> getCommitsSince(String sinceCommitName) throws IOException, GitAPIException {
        return getCommitsSince(sinceCommitName, headObjectId.getName());
    }

    public Iterable<RevCommit> getCommitsSince(String sinceCommitName, String toCommitName) throws IOException, GitAPIException {
        //String treeName = "refs/heads/master"; // tag or branch
        ObjectId sinceCommitObjectId = repository.resolve(sinceCommitName);
        ObjectId toCommitObjectId = repository.resolve(toCommitName);

        for (RevCommit commit : git.log().addRange(sinceCommitObjectId, headObjectId).call()) {
            System.out.println("commit: " + commit.getName());
        }

        return git.log().addRange(sinceCommitObjectId, toCommitObjectId).call();
    }

    public String getBranchName(){
        return this.branchName;
    }

    private String readBranchName() throws IOException {
        return repository.getFullBranch().replaceAll("/", ".");
    }

    private ObjectId readHeadObjectId() throws IOException {
        return repository.resolve(Constants.HEAD);
    }

    public String getHeadCommit() {
        return headObjectId.getName();
    }

}
