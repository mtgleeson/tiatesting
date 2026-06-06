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
     * Get the repo-relative, forward-slash-normalised paths of every file changed in the
     * commit range from {@code baseChangeNum} to the current head (or the local workspace
     * when {@code checkLocalChanges} is {@code true}). No file content is loaded and no
     * file-type filter is applied — paths for source, test, and arbitrary non-Java files
     * (e.g. {@code .sql}, {@code .properties}) are all returned. Deletes and renames are
     * included.
     *
     * <p>Intended for static test selection rules that match changed file paths against
     * user-supplied regexes; the existing {@link #buildDiffFilesContext} path remains the
     * input to method-level impact analysis and is unaffected.
     *
     * @param baseChangeNum the current commit number stored in the mapping.
     * @param checkLocalChanges when {@code true}, return paths for files modified in the
     *                          local workspace rather than the commit range.
     * @return the set of changed file paths; never {@code null}, may be empty.
     */
    Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges);

    /**
     * Do any clean up action when no further interactions with the VCS are needed.
     */
    void close();
}
