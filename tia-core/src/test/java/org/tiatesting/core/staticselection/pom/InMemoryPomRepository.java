package org.tiatesting.core.staticselection.pom;

import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.VCSReader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test VCS reader holding a repository's files on both sides of a diff in memory, recording each
 * content request. Files are added with {@link #unchanged}, {@link #changed}, {@link #added} and
 * {@link #deleted}; a path never added does not exist on either side.
 */
final class InMemoryPomRepository implements VCSReader {

    private final Map<String, String> oldFiles = new HashMap<>();
    private final Map<String, String> newFiles = new HashMap<>();
    private final List<Set<String>> requests = new ArrayList<>();

    /**
     * Add a file with the same content on both sides.
     *
     * @param path the repo-relative path.
     * @param content the content.
     * @return this repository.
     */
    InMemoryPomRepository unchanged(final String path, final String content) {
        return changed(path, content, content);
    }

    /**
     * Add a file whose content changed.
     *
     * @param path the repo-relative path.
     * @param oldContent the content before.
     * @param newContent the content after.
     * @return this repository.
     */
    InMemoryPomRepository changed(final String path, final String oldContent, final String newContent) {
        oldFiles.put(path, oldContent);
        newFiles.put(path, newContent);
        return this;
    }

    /**
     * Add a file that exists only after the change.
     *
     * @param path the repo-relative path.
     * @param content the content after.
     * @return this repository.
     */
    InMemoryPomRepository added(final String path, final String content) {
        newFiles.put(path, content);
        return this;
    }

    /**
     * Add a file that exists only before the change.
     *
     * @param path the repo-relative path.
     * @param content the content before.
     * @return this repository.
     */
    InMemoryPomRepository deleted(final String path, final String content) {
        oldFiles.put(path, content);
        return this;
    }

    /**
     * @return every content request made, in order.
     */
    List<Set<String>> getRequests() {
        return requests;
    }

    /**
     * @return a fixed branch name.
     */
    @Override
    public String getBranchName() {
        return "main";
    }

    /**
     * @return a fixed head commit.
     */
    @Override
    public String getHeadCommit() {
        return "head";
    }

    /**
     * @param baseChangeNum unused.
     * @param sourceFilesDirs unused.
     * @param testFilesDirs unused.
     * @param checkLocalChanges unused.
     * @return never returns.
     */
    @Override
    public Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum, final List<String> sourceFilesDirs,
                                                   final List<String> testFilesDirs, final boolean checkLocalChanges) {
        throw new UnsupportedOperationException("not used by POM tests");
    }

    /**
     * @param diffs unused.
     * @param baseChangeNum unused.
     * @param checkLocalChanges unused.
     */
    @Override
    public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs, final String baseChangeNum,
                                    final boolean checkLocalChanges) {
        throw new UnsupportedOperationException("not used by POM tests");
    }

    /**
     * @param baseChangeNum unused.
     * @param checkLocalChanges unused.
     * @return never returns.
     */
    @Override
    public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
        throw new UnsupportedOperationException("not used by POM tests");
    }

    /**
     * Serve both sides of each requested path and record the request.
     *
     * @param paths the requested paths.
     * @param baseChangeNum unused.
     * @param checkLocalChanges unused.
     * @return one entry per requested path; a side is {@code null} when the file does not exist there.
     */
    @Override
    public Map<String, FileContentPair> loadFileContents(final Set<String> paths, final String baseChangeNum,
                                                         final boolean checkLocalChanges) {
        requests.add(new LinkedHashSet<>(paths));
        Map<String, FileContentPair> loaded = new HashMap<>();
        for (String path : paths) {
            loaded.put(path, new FileContentPair(oldFiles.get(path), newFiles.get(path)));
        }
        return loaded;
    }

    /**
     * Nothing to release.
     */
    @Override
    public void close() {
    }
}
