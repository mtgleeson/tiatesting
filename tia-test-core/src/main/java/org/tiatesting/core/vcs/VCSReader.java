package org.tiatesting.core.vcs;

import org.tiatesting.core.diff.SourceFileDiffContext;

import java.util.List;

/**
 * Interface used to define the common version control system read operations used by Tia.
 */
public interface VCSReader {

    /**
     * Find the name of the current branch used by local version control system.
     *
     * @return
     */
    String getBranchName();

    /**
     * Get the head commit value for the VCS repository.
     *
     * @return
     */
    String getHeadCommit();

    /**
     * Find all the source code files in a list of revisions from a given commit value to the head of the VCS.
     * For each impacted source code file load the file content from both the starting revision and the head revision.
     *
     * @param commitFrom
     * @return
     */
    List<SourceFileDiffContext> buildDiffFilesContext(final String commitFrom);
}
