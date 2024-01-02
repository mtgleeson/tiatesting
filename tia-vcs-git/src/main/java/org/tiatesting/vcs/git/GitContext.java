package org.tiatesting.vcs.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.util.Objects;

/**
 * Object used to hold data about a Git repository being analysed by Tia.
 */
public class GitContext {

    private final Repository repository;
    private final Git git;
    private final String branchName;
    private final ObjectId headObjectId;

    public GitContext(Repository repository, String branchName, ObjectId headObjectId) {
        this.repository = repository;
        this.git = new Git(repository);
        this.branchName = branchName;
        this.headObjectId = headObjectId;
    }

    public Repository getRepository() {
        return repository;
    }

    public Git getGit() {
        return git;
    }

    public String getBranchName() {
        return branchName;
    }

    public ObjectId getHeadObjectId() {
        return headObjectId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GitContext that = (GitContext) o;
        return repository.equals(that.repository)
                && git.equals(that.git)
                && branchName.equals(that.branchName)
                && headObjectId.equals(that.headObjectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repository, git, branchName, headObjectId);
    }
}
