package org.tiatesting.core.vcs;

import org.tiatesting.core.diff.SourceFileDiffContext;

import java.util.List;
import java.util.Set;

/**
 * Interface used to define the common version control system read operations used by Tia.
 */
public interface VCSReader {

    /**
     * Find the name of the current branch used by local version control system.
     *
     * @return the VCS branch name
     */
    String getBranchName();

    /**
     * Get the head commit value for the VCS repository.
     *
     * @return the head commit value
     */
    String getHeadCommit();

    /**
     * Find all the source code files in a list of revisions from a given commit value to the head of the VCS.
     * For each impacted source code file load the file content from both the starting revision and the head revision.
     *
     * @param baseChangeNum the current commit number stored in the mapping
     * @param sourceFilesDirs the list of source code directories for the source project being analysed
     * @param testFilesDirs the list of test file directories for the project being analysed
     * @param checkLocalChanges should local changes be checked by Tia.
     * @return the set of diff file contexts
     */
    Set<SourceFileDiffContext> buildDiffFilesContext(final String baseChangeNum, final List<String> sourceFilesDirs,
                                                     final List<String> testFilesDirs, final boolean checkLocalChanges);

    /**
     * Do any clean up action when no further interactions with the VCS are needed.
     */
    void close();
}
