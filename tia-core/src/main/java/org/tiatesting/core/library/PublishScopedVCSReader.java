package org.tiatesting.core.library;

import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.core.vcs.VCSReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link VCSReader} for the length of one library publish that answers each repeated question
 * from memory. A publish stamps every consumer schema, and each stamp asks the VCS the same
 * questions - the source line, the diff from the mapping baseline, the diff from the previous
 * publish, the changed paths for the static rules - whose answers differ only when the schemas'
 * baselines or previous publishes differ. Schemas holding the same build share them, so the VCS
 * is asked once per distinct question rather than once per schema. See the consumer-discovery
 * section of the library publish-time stamping chapter in {@code WIKI.md}.
 *
 * <p>The diff objects a cached {@link #getDiffFiles} returns are shared by every schema that asks
 * for the same range. Only the VCS readers fill them in (their content), and
 * {@link #loadContentForDiffs} loads each one once, so a later schema reads the content the first
 * one loaded. The reader does not own the wrapped reader: {@link #close} leaves it open for its
 * owner to close.
 *
 * <p>Not thread-safe: the fan-out stamps the schemas one after another.
 */
final class PublishScopedVCSReader implements VCSReader {

    private final VCSReader delegate;
    private String headCommit;
    private final Map<List<Object>, Set<SourceFileDiffContext>> diffFiles = new HashMap<>();
    private final Map<List<Object>, Set<SourceFileDiffContext>> contentLoaded = new HashMap<>();
    private final Map<List<Object>, Set<String>> changedFilePaths = new HashMap<>();
    private final Map<List<Object>, Map<String, FileContentPair>> fileContents = new HashMap<>();
    private final Map<List<Object>, String> sourceLines = new HashMap<>();

    /**
     * Wrap the publishing build's reader for one publish.
     *
     * @param delegate the reader that answers each question the first time it is asked
     */
    PublishScopedVCSReader(final VCSReader delegate) {
        this.delegate = delegate;
    }

    /**
     * Return the wrapped reader's branch name.
     *
     * @return the branch name
     */
    @Override
    public String getBranchName() {
        return delegate.getBranchName();
    }

    /**
     * Return the head commit, read from the wrapped reader once.
     *
     * @return the head commit
     */
    @Override
    public String getHeadCommit() {
        if (headCommit == null) {
            headCommit = delegate.getHeadCommit();
        }
        return headCommit;
    }

    /**
     * Ask the wrapped reader whether the head is behind a commit; not cached, as a publish does not
     * ask it.
     *
     * @param commit the commit to compare the head with
     * @return the wrapped reader's answer
     */
    @Override
    public boolean isHeadBehind(final String commit) {
        return delegate.isHeadBehind(commit);
    }

    /**
     * Return the diff for a range and set of directories, asking the wrapped reader once per
     * distinct question. The returned set is shared and read-only.
     *
     * @param baseChangeNum the commit the diff starts from
     * @param sourceFilesDirs the source directories to diff
     * @param testFilesDirs the test directories to diff
     * @param checkLocalChanges whether to diff the local workspace
     * @return the changed files, shared with every caller asking the same question
     */
    @Override
    public Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum, final List<String> sourceFilesDirs,
                                                   final List<String> testFilesDirs,
                                                   final boolean checkLocalChanges) {
        List<Object> key = key(baseChangeNum, copy(sourceFilesDirs), copy(testFilesDirs), checkLocalChanges);
        Set<SourceFileDiffContext> diffs = diffFiles.get(key);
        if (diffs == null) {
            diffs = Collections.unmodifiableSet(delegate.getDiffFiles(baseChangeNum, sourceFilesDirs,
                    testFilesDirs, checkLocalChanges));
            diffFiles.put(key, diffs);
        }
        return diffs;
    }

    /**
     * Load the content of the given diffs, passing to the wrapped reader only those whose content
     * was not already loaded against the same base. A diff from a cached {@link #getDiffFiles} is
     * the same object for every schema, so its content is fetched once.
     *
     * @param diffs the diffs whose content to load
     * @param baseChangeNum the commit the content's "before" side is read at
     * @param checkLocalChanges whether the diffs are local-workspace changes
     */
    @Override
    public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs, final String baseChangeNum,
                                    final boolean checkLocalChanges) {
        List<Object> key = key(baseChangeNum, checkLocalChanges);
        Set<SourceFileDiffContext> loaded = contentLoaded.get(key);
        if (loaded == null) {
            loaded = Collections.newSetFromMap(new IdentityHashMap<SourceFileDiffContext, Boolean>());
            contentLoaded.put(key, loaded);
        }
        List<SourceFileDiffContext> toLoad = new ArrayList<>();
        for (SourceFileDiffContext diff : diffs) {
            if (!loaded.contains(diff)) {
                toLoad.add(diff);
            }
        }
        if (!toLoad.isEmpty()) {
            delegate.loadContentForDiffs(toLoad, baseChangeNum, checkLocalChanges);
            loaded.addAll(toLoad);
        }
    }

    /**
     * Return the paths changed since a commit, asking the wrapped reader once per commit. The
     * returned set is shared and read-only.
     *
     * @param baseChangeNum the commit the range starts from
     * @param checkLocalChanges whether to read the local workspace
     * @return the changed paths
     */
    @Override
    public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
        List<Object> key = key(baseChangeNum, checkLocalChanges);
        Set<String> paths = changedFilePaths.get(key);
        if (paths == null) {
            paths = Collections.unmodifiableSet(delegate.getChangedFilePaths(baseChangeNum, checkLocalChanges));
            changedFilePaths.put(key, paths);
        }
        return paths;
    }

    /**
     * Return the before and after content of the given paths, asking the wrapped reader once per
     * distinct request. The returned map is shared and read-only.
     *
     * @param paths the paths to load
     * @param baseChangeNum the commit the "before" side is read at
     * @param checkLocalChanges whether the "after" side is the local workspace
     * @return the content per path
     */
    @Override
    public Map<String, FileContentPair> loadFileContents(final Set<String> paths, final String baseChangeNum,
                                                         final boolean checkLocalChanges) {
        List<Object> key = key(new HashSet<>(paths), baseChangeNum, checkLocalChanges);
        Map<String, FileContentPair> contents = fileContents.get(key);
        if (contents == null) {
            contents = Collections.unmodifiableMap(delegate.loadFileContents(paths, baseChangeNum,
                    checkLocalChanges));
            fileContents.put(key, contents);
        }
        return contents;
    }

    /**
     * Ask the wrapped reader for the mapping drift; not cached, as a publish does not ask it.
     *
     * @param baseChangeNum the stored mapping commit
     * @param localDiffs the locally changed files
     * @return the wrapped reader's answer
     */
    @Override
    public MappingDrift findMappingDrift(final String baseChangeNum,
                                         final Collection<SourceFileDiffContext> localDiffs) {
        return delegate.findMappingDrift(baseChangeNum, localDiffs);
    }

    /**
     * Return the source line of the given directories, asking the wrapped reader once per distinct
     * question. Every schema's stamp passes the publishing build's own source roots, so the line
     * is resolved once per publish. A null line is remembered too.
     *
     * @param sourceDirs the source directories of the module being published
     * @param workspaceBranch the branch the publishing workspace identifies as, or null
     * @return the line, or null when it cannot be identified
     */
    @Override
    public String getSourceLine(final List<String> sourceDirs, final String workspaceBranch) {
        List<Object> key = key(copy(sourceDirs), workspaceBranch);
        if (!sourceLines.containsKey(key)) {
            sourceLines.put(key, delegate.getSourceLine(sourceDirs, workspaceBranch));
        }
        return sourceLines.get(key);
    }

    /**
     * Return the wrapped reader's workspace root.
     *
     * @return the workspace root, or null when unknown
     */
    @Override
    public String getWorkspaceRoot() {
        return delegate.getWorkspaceRoot();
    }

    /**
     * Do nothing: the wrapped reader belongs to the publishing build, which closes it.
     */
    @Override
    public void close() {
    }

    /**
     * Build a cache key from the arguments of one question.
     *
     * @param parts the arguments; nulls allowed
     * @return a list comparing equal for equal arguments
     */
    private static List<Object> key(final Object... parts) {
        return Arrays.asList(parts);
    }

    /**
     * Copy a list for use in a key, so a caller changing its list later cannot change the key.
     *
     * @param list the list, or null
     * @return an independent copy, or null
     */
    private static List<String> copy(final List<String> list) {
        return list == null ? null : new ArrayList<>(list);
    }
}
