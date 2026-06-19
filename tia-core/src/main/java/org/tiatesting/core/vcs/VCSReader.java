package org.tiatesting.core.vcs;

import org.tiatesting.core.diff.SourceFileDiffContext;

import java.util.Collection;
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
     * Find all the source and test files changed from a given commit value to the head of the VCS
     * (or in the local workspace), as diff contexts carrying their paths and change type but
     * <strong>no file content</strong>. Content is fetched separately via
     * {@link #loadContentForDiffs} so the caller can first decide which files actually need it
     * (e.g. only files tracked in the mapping) and avoid fetching content for the rest.
     *
     * <p>Each returned context has its {@code vcsFetchKey} populated - the VCS-native handle
     * {@link #loadContentForDiffs} fetches its content by.
     *
     * @param baseChangeNum the current commit number stored in the mapping
     * @param sourceFilesDirs the list of source code directories for the source project being analysed
     * @param testFilesDirs the list of test file directories for the project being analysed
     * @param checkLocalChanges should local changes be checked by Tia.
     * @return the set of diff file contexts, without content
     */
    Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum, final List<String> sourceFilesDirs,
                                            final List<String> testFilesDirs, final boolean checkLocalChanges);

    /**
     * Load the before/after file content onto the given diff contexts (produced by
     * {@link #getDiffFiles}), keyed by each context's {@code vcsFetchKey}. Only the contexts
     * passed in are fetched, so a caller can restrict the (potentially expensive) content fetch
     * to the files it actually needs.
     *
     * @param diffs the diff contexts to populate; may be empty
     * @param baseChangeNum the current commit number stored in the mapping (the "before" baseline)
     * @param checkLocalChanges whether the diffs are local-workspace changes
     */
    void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs, final String baseChangeNum,
                             final boolean checkLocalChanges);

    /**
     * Get the repo-relative, forward-slash-normalised paths of every file changed in the
     * commit range from {@code baseChangeNum} to the current head (or the local workspace
     * when {@code checkLocalChanges} is {@code true}). No file content is loaded and no
     * file-type filter is applied — paths for source, test, and arbitrary non-Java files
     * (e.g. {@code .sql}, {@code .properties}) are all returned. Deletes and renames are
     * included.
     *
     * <p>Intended for static test selection rules that match changed file paths against
     * user-supplied regexes; the existing {@link #getDiffFiles} path remains the
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
