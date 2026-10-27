package org.tiatesting.vcs.perforce;

import com.perforce.p4java.core.file.*;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.ConnectionException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.option.server.GetExtendedFilesOptions;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MissingUpstreamChanges;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.tiatesting.core.sourcefile.FileExtensions.GROOVY_FILE_EXT;
import static org.tiatesting.core.sourcefile.FileExtensions.JAVA_FILE_EXT;

/**
 * Build the list of source files that have been changed since the previously analyzed commit.
 */
public class P4DiffAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(P4DiffAnalyzer.class);

    /**
     * Revision specifier for the revision of a file the workspace is synced to. This is the base
     * an opened file's local edit sits on, so it is the "before" side of a local-changes diff.
     * Unlike {@code @<workspace head CL>}, it stays correct when files in the workspace are synced
     * to different changelists.
     */
    static final String HAVE_REVISION = "#have";

    /**
     * Build the list of files that have changes either since the previously stored submit, or from local changes.
     * Return the list of SourceFileDiffContext.
     *
     * @param p4Context object used to hold data about a P4 repository being analysed by Tia.
     * @param baseCl the current changelist stored in the mapping
     * @param sourceAndTestFiles the list of source code and test files for the source project being analysed
     * @param checkLocalChanges should local changes be analyzed for test selection
     * @return list of SourceFileDiffContext for the files impacted in the given commit range to head
     */
    protected Set<SourceFileDiffContext> getDiffFiles(final P4Context p4Context, final String baseCl,
                                                      final List<String> sourceAndTestFiles,
                                                      final boolean checkLocalChanges) {
        String clTo = p4Context.getHeadCL();
        List<IFileSpec> sourceAndTestFilesSpecs = getSourceAndTestFilesSpecs(p4Context.getP4Connection(), sourceAndTestFiles);

        // get changes from the previously stored CL number to HEAD - the diff list only, no content.
        // Content is loaded separately via loadContentForDiffContexts so the caller can restrict it
        // to a chosen subset of files.
        Set<SourceFileDiffContext> sourceFileDiffContexts = new HashSet<>();

        if (checkLocalChanges){
            // get the local changes compared to local HEAD
            sourceFileDiffContexts.addAll(getLocalChanges(p4Context, sourceAndTestFilesSpecs));
        } else {
            // get the changes since the CL that was last run with Tia
            sourceFileDiffContexts.addAll(getChangesSinceLastRunCL(p4Context, baseCl, clTo, sourceAndTestFilesSpecs));
        }

        return sourceFileDiffContexts;
    }

    /**
     * Load the before/after file content onto the given diff contexts, keyed by each context's
     * {@link SourceFileDiffContext#getVcsFetchKey() depot-path fetch key}. For the submitted-range
     * case the "before" is read at {@code baseCl} and the "after" at head; for local changes the
     * "before" is read at the workspace's {@link #HAVE_REVISION have revision} and the "after" is
     * the working-copy content from {@link SourceFileDiffContext#getLocalContentPath()}.
     *
     * @param p4Context the Perforce context (connection + head CL)
     * @param diffs the diff contexts to populate; may be empty
     * @param baseCl the stored changelist the mapping is valid for (the submitted-range "before")
     * @param checkLocalChanges whether the diffs are local-workspace changes
     */
    void loadContentForDiffContexts(P4Context p4Context, Collection<SourceFileDiffContext> diffs,
                                            String baseCl, boolean checkLocalChanges) {
        if (diffs.isEmpty()) {
            return;
        }

        // Rebuild the depot-path-keyed map the content readers expect from the contexts' fetch keys.
        Map<String, SourceFileDiffContext> diffsByDepotPath = new HashMap<>();
        for (SourceFileDiffContext diff : diffs) {
            diffsByDepotPath.put(diff.getVcsFetchKey(), diff);
        }

        P4Connection p4Connection = p4Context.getP4Connection();
        if (checkLocalChanges) {
            readFileContentForVersion(p4Connection, HAVE_REVISION, true, diffsByDepotPath);
            loadLocalContentFromDisk(diffs, false);
        } else {
            readFileContentForVersion(p4Connection, atChangelist(baseCl), true, diffsByDepotPath);
            readFileContentForVersion(p4Connection, atChangelist(p4Context.getHeadCL()), false, diffsByDepotPath);
        }
    }

    /**
     * Return the workspace-root-relative, forward-slash-normalised paths of every file changed
     * either in the CL range from {@code baseCl} to head or in the local workspace. No file
     * content is loaded and no file-type filter is applied (paths for all file types are
     * returned).
     *
     * <p>Paths are normalised through the client view: each depot path is resolved to its local
     * workspace path with {@code p4 where} and then relativized against the client's root
     * directory. Resolving through the view (rather than stripping a stream-path prefix) makes
     * files that reach the workspace via stream {@code import}/overlay paths visible - their
     * depot path lives outside the stream, but their local path is inside the workspace like
     * any other file. It also works for classic (non-stream) clients, which have no stream
     * path at all. For a stream client whose root maps the stream directly, the resulting
     * relative paths are identical to the previous stream-prefix behaviour.
     *
     * @param p4Context object used to hold data about a Perforce server being analysed by Tia.
     * @param baseCl the current changelist stored in the mapping; ignored when {@code checkLocalChanges} is {@code true}.
     * @param checkLocalChanges when {@code true}, return paths for files opened in the local
     *                          workspace rather than the CL range.
     * @return the set of changed file paths; never {@code null}, may be empty.
     */
    protected Set<String> getChangedFilePaths(final P4Context p4Context, final String baseCl,
                                              final boolean checkLocalChanges) {
        Set<String> changedPaths = new HashSet<>();

        try {
            List<? extends IFileSpec> rawChangedFiles = checkLocalChanges
                    ? getLocalChangedFileSpecs(p4Context.getP4Connection())
                    : getRangeChangedFileSpecs(p4Context.getP4Connection(), baseCl, p4Context.getHeadCL());

            List<String> depotPaths = rawChangedFiles.stream()
                    .map(IFileSpec::getDepotPathString)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (depotPaths.isEmpty()) {
                return changedPaths;
            }

            // Resolve depot paths to local workspace paths through the client view, then
            // relativize against the workspace root.
            List<IFileSpec> whereFileSpecs = p4Context.getP4Connection().getClient()
                    .where(FileSpecBuilder.makeFileSpecList(depotPaths));
            String workspaceRootPrefix = buildWorkspaceRootPrefix(p4Context.getP4Connection());

            for (IFileSpec whereFileSpec : whereFileSpecs) {
                String localPath = whereFileSpec.getLocalPathString();
                if (localPath == null) {
                    // not mapped in the client view (or a where info/error entry) - can't be
                    // expressed as a workspace-relative path.
                    log.trace("Skipping changed file with no local mapping in the client view: {}",
                            whereFileSpec.getDepotPathString());
                    continue;
                }
                String normalizedLocalPath = localPath.replace('\\', '/');
                if (normalizedLocalPath.startsWith(workspaceRootPrefix)) {
                    changedPaths.add(normalizedLocalPath.substring(workspaceRootPrefix.length()));
                } else {
                    log.trace("Skipping changed file outside the client workspace root: {}", normalizedLocalPath);
                }
            }
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        return changedPaths;
    }

    /**
     * Load the "before" and "after" content of the given workspace-root-relative paths (the
     * form {@link #getChangedFilePaths} returns). Each path is resolved to its depot path through
     * the client view with one {@code p4 where} call. For the CL range the "before" side is
     * printed at {@code baseCl} and the "after" side at head; for local changes the "before" side
     * is printed at the workspace's {@link #HAVE_REVISION have revision} and the "after" side is
     * the working-copy file on disk. Each printed side
     * is one {@code p4 files} existence query plus one batched {@code p4 print}, regardless of
     * how many paths are requested.
     *
     * @param p4Context the Perforce context (connection + head CL).
     * @param paths the workspace-root-relative, forward-slash paths to load.
     * @param baseCl the stored changelist the mapping is valid for (the CL-range "before");
     *               ignored when {@code checkLocalChanges} is {@code true}.
     * @param checkLocalChanges whether to compare head against the working copy.
     * @return one entry per requested path; a side is {@code null} when the file does not exist
     *         there. A path the client view does not map has no depot side, so only its
     *         working-copy side (local changes) can be non-null.
     */
    Map<String, FileContentPair> loadFileContents(final P4Context p4Context, final Set<String> paths,
                                                  final String baseCl, final boolean checkLocalChanges) {
        Map<String, FileContentPair> contents = new HashMap<>();
        if (paths.isEmpty()) {
            return contents;
        }

        P4Connection p4Connection = p4Context.getP4Connection();
        String workspaceRootPrefix = buildWorkspaceRootPrefix(p4Connection);
        Map<String, String> depotPathByPath = resolveDepotPaths(p4Connection, paths, workspaceRootPrefix);

        Map<String, String> oldByDepotPath;
        Map<String, String> newByDepotPath;
        if (checkLocalChanges) {
            oldByDepotPath = printExistingDepotFiles(p4Connection, depotPathByPath.values(), HAVE_REVISION);
            newByDepotPath = null;
        } else {
            oldByDepotPath = printExistingDepotFiles(p4Connection, depotPathByPath.values(), atChangelist(baseCl));
            newByDepotPath = printExistingDepotFiles(p4Connection, depotPathByPath.values(),
                    atChangelist(p4Context.getHeadCL()));
        }

        for (String path : paths) {
            String depotPath = depotPathByPath.get(path);
            String oldContent = depotPath != null ? oldByDepotPath.get(depotPath) : null;
            String newContent = checkLocalChanges
                    ? readWorkspaceFile(workspaceRootPrefix + path)
                    : (depotPath != null ? newByDepotPath.get(depotPath) : null);
            contents.put(path, new FileContentPair(oldContent, newContent));
        }
        return contents;
    }

    /**
     * Resolve workspace-root-relative paths to depot paths through the client view with a single
     * {@code p4 where} call. Each where-result is matched back to the requested path by
     * relativizing its local path against the workspace root. Entries with no depot path (the
     * overridden line of a layered client view) or no local path are ignored, so paths the view
     * does not map are absent from the result.
     *
     * @param p4Connection the Perforce connection.
     * @param paths the workspace-root-relative paths to resolve.
     * @param workspaceRootPrefix the normalised workspace root prefix (see {@link #buildWorkspaceRootPrefix}).
     * @return depot path keyed by requested path, for the paths the client view maps.
     */
    private Map<String, String> resolveDepotPaths(final P4Connection p4Connection, final Set<String> paths,
                                                  final String workspaceRootPrefix) {
        List<String> localPaths = new ArrayList<>(paths.size());
        for (String path : paths) {
            localPaths.add(workspaceRootPrefix + path);
        }

        Map<String, String> depotPathByPath = new HashMap<>();
        try {
            List<IFileSpec> whereFileSpecs = p4Connection.getClient().where(FileSpecBuilder.makeFileSpecList(localPaths));
            for (IFileSpec whereFileSpec : whereFileSpecs) {
                String depotPath = whereFileSpec.getDepotPathString();
                String localPath = whereFileSpec.getLocalPathString();
                if (depotPath == null || localPath == null) {
                    continue;
                }
                String normalizedLocalPath = localPath.replace('\\', '/');
                if (!normalizedLocalPath.startsWith(workspaceRootPrefix)) {
                    continue;
                }
                String path = normalizedLocalPath.substring(workspaceRootPrefix.length());
                if (paths.contains(path)) {
                    depotPathByPath.put(path, depotPath);
                }
            }
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        if (depotPathByPath.size() < paths.size()) {
            Set<String> unmapped = new HashSet<>(paths);
            unmapped.removeAll(depotPathByPath.keySet());
            log.debug("Paths not mapped in the client view, loaded with no content: {}", unmapped);
        }
        return depotPathByPath;
    }

    /**
     * Print the content of the given depot paths at a revision, skipping paths that do not
     * exist there. Existence is checked first with one {@code p4 files} query: a path missing at
     * the revision comes back with no depot path, and a path whose revision is a delete has no
     * content to print. The remaining paths are fetched in one batched {@code p4 print}.
     *
     * @param p4Connection the Perforce connection.
     * @param depotPaths the depot paths to print.
     * @param revision the revision specifier appended to each depot path, e.g. {@code @1234}
     *                 or {@link #HAVE_REVISION}.
     * @return content keyed by depot path, for the paths that exist at {@code revision}.
     */
    private Map<String, String> printExistingDepotFiles(final P4Connection p4Connection,
                                                        final Collection<String> depotPaths, final String revision) {
        if (depotPaths.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> annotatedPaths = new ArrayList<>(depotPaths.size());
        for (String depotPath : depotPaths) {
            annotatedPaths.add(depotPath + revision);
        }

        Set<String> requestedDepotPaths = new HashSet<>(depotPaths);
        List<String> existingDepotPaths = new ArrayList<>();
        try {
            List<IFileSpec> fileSpecs = p4Connection.getServer().getDepotFiles(
                    FileSpecBuilder.makeFileSpecList(annotatedPaths), false);
            if (fileSpecs != null) {
                for (IFileSpec fileSpec : fileSpecs) {
                    String depotPath = fileSpec.getDepotPathString();
                    if (depotPath != null && requestedDepotPaths.contains(depotPath) && !isDeletedRevision(fileSpec)) {
                        existingDepotPaths.add(depotPath);
                    }
                }
            }
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        if (existingDepotPaths.isEmpty()) {
            return Collections.emptyMap();
        }
        return printDepotFilesAtRevision(p4Connection, existingDepotPaths, revision, "content by path at " + revision);
    }

    /**
     * For locally opened files, find the submitted changelists each one is missing: revisions
     * after the one the workspace has synced ({@code #have}) up to the stored changelist. One
     * batched {@code p4 files -a <depotPath>#>have,@<baseCl>} call covers every file. The check
     * is per file rather than against the workspace head changelist, so it also catches a single
     * file synced to an older revision than the rest of the workspace.
     *
     * <p>The command is sent with {@code execMapCmdList} and explicit argv, as {@code p4 print}
     * is (see {@link #loadFileSpecsContentIntoDiffContext}), so the {@code #>have,@cl} range
     * reaches the server verbatim instead of being parsed into a p4java file spec.
     *
     * @param p4Context the Perforce context (connection + head CL).
     * @param baseCl the changelist the stored mapping was built at.
     * @param localDiffs the locally opened files to check; their fetch keys are depot paths.
     * @return the missing changelist numbers (oldest first) keyed by depot path.
     */
    MissingUpstreamChanges findMissingUpstreamChanges(final P4Context p4Context, final String baseCl,
                                                      final Collection<SourceFileDiffContext> localDiffs) {
        if (localDiffs.isEmpty()) {
            return MissingUpstreamChanges.none();
        }

        List<String> argv = new ArrayList<>(localDiffs.size() + 1);
        argv.add("-a");
        for (SourceFileDiffContext diff : localDiffs) {
            argv.add(diff.getVcsFetchKey() + "#>have," + atChangelist(baseCl));
        }

        List<Map<String, Object>> results;
        try {
            results = p4Context.getP4Connection().getServer().execMapCmdList("files",
                    argv.toArray(new String[0]), null);
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        // Files with no newer revision come back as "no revision(s) after that revision" entries,
        // which carry no depotFile/change and are skipped.
        Map<String, TreeSet<Integer>> changesByDepotPath = new LinkedHashMap<>();
        if (results != null) {
            for (Map<String, Object> result : results) {
                Object depotFile = result.get("depotFile");
                Object change = result.get("change");
                if (depotFile != null && change != null) {
                    changesByDepotPath.computeIfAbsent(depotFile.toString(), key -> new TreeSet<>())
                            .add(Integer.parseInt(change.toString()));
                }
            }
        }

        Map<String, List<String>> missingChanges = new LinkedHashMap<>();
        changesByDepotPath.forEach((depotPath, changes) -> missingChanges.put(depotPath,
                changes.stream().map(String::valueOf).collect(Collectors.toList())));
        return MissingUpstreamChanges.of(missingChanges);
    }

    /**
     * Build the revision specifier for a changelist, e.g. {@code @1234}, for appending to a
     * depot path.
     *
     * @param cl the changelist number.
     * @return the {@code @<cl>} revision specifier.
     */
    private static String atChangelist(final String cl) {
        return "@" + cl;
    }

    /**
     * Check whether a {@code p4 files} result names a revision with no content: the head action
     * at the queried changelist removed the file.
     *
     * @param fileSpec the file spec returned by {@code getDepotFiles}.
     * @return {@code true} when the revision is a delete, move/delete or purge.
     */
    private boolean isDeletedRevision(final IFileSpec fileSpec) {
        FileAction action = fileSpec.getAction();
        return action == FileAction.DELETE || action == FileAction.MOVE_DELETE || action == FileAction.PURGE;
    }

    /**
     * Read a working-copy file as UTF-8.
     *
     * @param localPath the absolute local path of the file.
     * @return the file content, or {@code null} when no file exists at that path.
     */
    private String readWorkspaceFile(final String localPath) {
        File file = new File(localPath);
        if (!file.isFile()) {
            return null;
        }
        try {
            return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    /**
     * Build the forward-slash, trailing-slash-terminated workspace root prefix used to
     * relativize local file paths, from the client's configured root directory.
     *
     * @param p4Connection the Perforce connection whose client root to use.
     * @return the normalised root prefix, e.g. {@code /Users/me/ws/} or {@code C:/ws/}.
     */
    private String buildWorkspaceRootPrefix(final P4Connection p4Connection) {
        String root = p4Connection.getClient().getRoot().replace('\\', '/');
        return root.endsWith("/") ? root : root + "/";
    }

    /**
     * Query Perforce for every file changed in the changelist range {@code baseCl+1..headCl}.
     * Mirrors the submit-range query in {@link #getSourceFilesImpactedFromPreviousSubmit} but
     * applies no file-type or source-dir filter. Returns an empty list when the workspace head
     * is already at {@code baseCl}.
     *
     * <p>The range is queried in client syntax ({@code //<client>/...}) rather than stream
     * syntax, so the server expands the whole client view - including stream
     * {@code import}/overlay paths whose depot location lives outside the stream. A
     * stream-scoped query would silently omit submitted changes to those files.
     *
     * @param p4Connection the Perforce connection.
     * @param baseCl the previously stored changelist value.
     * @param headCl the current head changelist value.
     * @return list of changed file specs (may contain duplicate depot paths for files changed
     *         in multiple CLs in the range; the caller deduplicates via {@link java.util.Set}).
     * @throws P4JavaException if the underlying P4 call fails.
     */
    private List<IFileSpec> getRangeChangedFileSpecs(final P4Connection p4Connection,
                                                     final String baseCl, final String headCl)
            throws P4JavaException {
        if (baseCl.equals(headCl)) {
            return Collections.emptyList();
        }
        int clFrom = Integer.parseInt(baseCl) + 1;
        String clientName = p4Connection.getClient().getName();
        String filePaths = "//" + clientName + "/...@" + clFrom + "," + headCl;
        List<IFileSpec> changedFiles = p4Connection.getServer().getDepotFiles(
                FileSpecBuilder.makeFileSpecList(filePaths), true);
        return (changedFiles != null) ? changedFiles : Collections.emptyList();
    }

    /**
     * Query Perforce for every file currently opened in the client's workspace. Mirrors the
     * local-changes query in {@link #getSourceFilesImpactedFromLocalChanges} but applies no
     * file-type or source-dir filter.
     *
     * @param p4Connection the Perforce connection.
     * @return list of opened file specs (extended file specs; depot path read via
     *         {@link IFileSpec#getDepotPathString()}).
     * @throws P4JavaException if the underlying P4 call fails.
     */
    private List<IExtendedFileSpec> getLocalChangedFileSpecs(final P4Connection p4Connection)
            throws P4JavaException {
        GetExtendedFilesOptions options = new GetExtendedFilesOptions();
        FileStatOutputOptions fstatOutputOptions = new FileStatOutputOptions();
        fstatOutputOptions.setOpenedFiles(true);
        options.setOutputOptions(fstatOutputOptions);
        List<IExtendedFileSpec> opened = p4Connection.getServer().getExtendedFiles(
                FileSpecBuilder.makeFileSpecList("//..."), options);
        return (opened != null) ? opened : Collections.emptyList();
    }

    /**
     * Find the P4 file spec for each source and test file directory. This gives us both the depot path and local path.
     * <p>{@code p4 where} returns one entry per client-view line that touches each queried path.
     * A path covered by overlapping view lines (a broad mapping re-sourced by a later, more
     * specific line) comes back with the overridden line flagged as unmapped - a leading {@code -}
     * in the CLI - which p4java represents with a {@code null} depot path. Layered client views
     * therefore routinely return a null-depot entry alongside the real winning mapping, so those
     * null entries must be filtered out before the downstream depot-path matching in
     * {@code isFileInSourceOrTestDir} and {@code convertDepotPathToTiaPath} dereferences them.
     *
     * <p>The entries are grouped by their resolved client-side path so the filtering can classify
     * each queried directory into one of three buckets:
     * <ul>
     *   <li>it has a valid winning mapping and is present in this workspace - keep it (silently
     *       dropping any override entries);</li>
     *   <li>it resolved to no valid mapping at all (not in the client view) - skip it and log a
     *       WARN, because this usually indicates a client-view or configuration problem worth
     *       fixing;</li>
     *   <li>it is mapped in the client view but not present in this workspace - skip it and log a
     *       WARN, because its content can't be read to diff. This happens either because the
     *       directory isn't synced (for example a restricted library the machine has lost access
     *       to) or because the configured sub-directory doesn't exist for that project (for
     *       example a DB project whose sources live under {@code src/main/resources} and which has
     *       no {@code src/main/java}).</li>
     * </ul>
     * Each skipped bucket is logged once naming every affected directory, rather than once per
     * directory, to keep the output readable when many libraries are unavailable.
     *
     * <p>Trailing path separators are trimmed from each queried directory before it is handed to
     * {@code p4 where}. A trailing {@code /} or {@code \} makes p4java's path parsing fail with a
     * "Null directory (//) not allowed" error, turning an otherwise resolvable directory into an
     * error spec (and emitting p4java's own error line). The tracked-library directories in
     * particular arrive with a trailing slash from their configured coordinates.
     *
     * @param p4Connection the Perforce connection being used for the analysis.
     * @param sourceAndTestFiles the list of source and test directories for the source project being analysed
     * @return list of p4 file specs representing the source and test directories, each with a non-null depot path
     */
    private List<IFileSpec> getSourceAndTestFilesSpecs(final P4Connection p4Connection, final List<String> sourceAndTestFiles){
        List<String> sanitizedPaths = new ArrayList<>();
        for (String path : sourceAndTestFiles){
            if (path == null){
                continue;
            }
            String trimmed = path.trim();
            while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))){
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (!trimmed.isEmpty()){
                sanitizedPaths.add(trimmed);
            }
        }

        List<IFileSpec> fileSpecs;
        try {
            fileSpecs = p4Connection.getClient().where(FileSpecBuilder.makeFileSpecList(sanitizedPaths));
        } catch (ConnectionException | AccessException e) {
            throw new VCSAnalyzerException(e);
        }

        // Group by the resolved client-side path so overlapping-view override entries (null depot)
        // are collapsed with their winning mapping for the same directory. preserve iteration order.
        Map<String, List<IFileSpec>> specsByPath = fileSpecs.stream()
                .collect(Collectors.groupingBy(P4DiffAnalyzer::whereResultKey, LinkedHashMap::new, Collectors.toList()));

        List<IFileSpec> mappedSpecs = new ArrayList<>();
        List<String> notMappedDirs = new ArrayList<>();
        List<String> notSyncedDirs = new ArrayList<>();

        for (Map.Entry<String, List<IFileSpec>> entry : specsByPath.entrySet()){
            List<IFileSpec> validSpecs = entry.getValue().stream()
                    .filter(fileSpec -> fileSpec.getDepotPathString() != null)
                    .collect(Collectors.toList());

            if (validSpecs.isEmpty()){
                // No client-view line maps this directory to a depot path - it's not in the client view.
                notMappedDirs.add(entry.getKey());
            } else if (!isMappedDirSyncedLocally(validSpecs)){
                // Mapped in the client view but absent from the local workspace (not synced). Its
                // content can't be read to diff, so drop it rather than fail downstream.
                notSyncedDirs.add(entry.getKey());
            } else {
                mappedSpecs.addAll(validSpecs);
            }
        }

        if (!notMappedDirs.isEmpty()){
            log.warn("Skipping {} configured source/test/library director(y/ies) not mapped in the P4 client " +
                    "view so their changes won't be tracked by Tia: {}", notMappedDirs.size(), notMappedDirs);
        }
        if (!notSyncedDirs.isEmpty()){
            // These directories ARE mapped in the client view but aren't present in the local
            // workspace, so their content can't be read to diff and they're skipped. This happens
            // when a directory isn't synced (e.g. a restricted library the machine has lost access
            // to - worth surfacing on a build machine) or when the configured sub-directory simply
            // doesn't exist for that project (e.g. a DB project whose sources are SQL under
            // src/main/resources and which has no src/main/java - a likely config issue). Both are
            // worth a warning; it's grouped into a single line to avoid one message per directory.
            log.warn("Skipping {} configured source/test/library director(y/ies) mapped in the P4 client view " +
                    "but not present in the local workspace (not synced, or the configured directory doesn't " +
                    "exist) so their changes won't be tracked by Tia: {}", notSyncedDirs.size(), notSyncedDirs);
        }

        return mappedSpecs;
    }

    /**
     * Decide whether a directory that has a valid client-view mapping is actually synced into the
     * local workspace, so that mapped-but-unavailable directories (for example restricted
     * libraries the user has no access to on this machine) can be skipped instead of failing when
     * their content is later read for diffing.
     *
     * <p>{@code p4 where} reports the workspace local path from the client view regardless of sync
     * state, so the on-disk presence of that local path is used as the signal. If a valid spec
     * reports a local path that exists on disk the directory is treated as synced. If local paths
     * are reported but none exist the directory is treated as not synced. If no local path is
     * reported at all the sync state can't be determined, so it is kept (treated as synced) to
     * avoid dropping a directory on incomplete information.
     *
     * @param validSpecs the {@code p4 where} specs for a directory that all have a non-null depot path
     * @return true if the directory is synced locally (or its sync state can't be determined), false if it is mapped but not synced
     */
    private static boolean isMappedDirSyncedLocally(final List<IFileSpec> validSpecs){
        boolean anyLocalPath = false;
        for (IFileSpec spec : validSpecs){
            String localPath = spec.getLocalPathString();
            if (localPath != null){
                anyLocalPath = true;
                if (new File(localPath).exists()){
                    return true;
                }
            }
        }
        return !anyLocalPath;
    }

    /**
     * Derive a stable grouping key for a {@code p4 where} result so all entries resolving to the
     * same workspace directory (the winning mapping plus any overridden/unmapped entries) are
     * grouped together, and so the WARN for a fully-unmapped directory names the directory.
     *
     * <p>Prefers the local path, then the client path, then the original queried path, then the
     * depot path. When {@code p4 where} can't resolve a directory at all (not under the client
     * root, or not in the client view) p4java returns an error/info spec with every path field
     * null but a descriptive {@link IFileSpec#getStatusMessage() status message} that names the
     * offending path - so that is used as the final fallback before {@code <unknown>}. Never
     * returns null so it is safe as a grouping classifier.
     *
     * @param fileSpec the {@code p4 where} result spec
     * @return a non-null key identifying the queried directory the spec resolves to
     */
    static String whereResultKey(IFileSpec fileSpec){
        if (fileSpec.getLocalPathString() != null){
            return fileSpec.getLocalPathString();
        }
        if (fileSpec.getClientPathString() != null){
            return fileSpec.getClientPathString();
        }
        if (fileSpec.getOriginalPathString() != null){
            return fileSpec.getOriginalPathString();
        }
        if (fileSpec.getDepotPathString() != null){
            return fileSpec.getDepotPathString();
        }
        if (fileSpec.getStatusMessage() != null && !fileSpec.getStatusMessage().trim().isEmpty()){
            return fileSpec.getStatusMessage().trim();
        }
        return "<unknown>";
    }

    /**
     * Find all the source code files in a list of revisions from a given submit number to the head of the VCS.
     * For each impacted source code file, load the file content from the starting revision and the head revision.
     *
     * @param p4Context object used to hold data about a Perforce server being analysed by Tia.
     * @param baseCl the current changelist stored in the mapping
     * @param clTo the newest changelist number in the range being analysed
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Set<SourceFileDiffContext> getChangesSinceLastRunCL(P4Context p4Context, String baseCl, String clTo,
                                                                List<IFileSpec> sourceAndTestFilesSpecs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts;

        if (baseCl.equals(clTo)){
            log.info("Workspace Head is at the same version tracked by Tia, no submitted differences to check for.");
            return new HashSet<>();
        }

        int clFrom = Integer.parseInt(baseCl) + 1; // don't include the current CL in the range to check - we've already analyzed this CL
        sourceFileDiffContexts = getSourceFilesImpactedFromPreviousSubmit(p4Context.getP4Connection(), Integer.toString(clFrom), clTo,
                sourceAndTestFilesSpecs);
        log.info("Source files found in the commit range: {}", sourceFileDiffContexts.keySet().stream().map( key ->
                convertDepotPathToTiaPath(key, sourceAndTestFilesSpecs)).collect(Collectors.toList()));

        // Content is loaded by the caller (loadContentForDiffContexts) so it can be deferred to a
        // chosen subset; this method returns the diff list only.
        return new HashSet<>(sourceFileDiffContexts.values());
    }

    /**
     * Find every file changed in the changelist range {@code clFrom..clTo} and build the
     * corresponding {@link SourceFileDiffContext} entries.
     *
     * <p>Resolves the file changes in a single {@code p4 files -a //<client>/...@clFrom,clTo}
     * round-trip via {@link com.perforce.p4java.server.IOptionsServer#getDepotFiles} with
     * {@code allRevs=true}. The {@code allRevs} flag tells Perforce to return <em>every</em>
     * revision of each file <strong>within the CL range</strong> (not every revision ever) -
     * so a file that was added and then edited in the range appears twice in the result, once
     * for the ADD CL and once for the EDIT CL. {@code sortFileChanges} (called inside
     * {@link #buildDiffContextsForFileSpecs}) orders these per-CL entries oldest-first so the
     * action-override logic in {@code buildDiffContext} can collapse ADD-then-EDIT to ADD
     * correctly.
     *
     * <p>The earlier {@code allRevs=false} form returned only the highest revision in the
     * range per file - so an ADD-then-EDIT file came back with action=EDIT alone and was
     * mis-classified as MODIFY. The forOriginal=true pass then tried to fetch the file at
     * baseCl where it didn't exist, producing "No file found in P4" log lines.
     *
     * @param p4Connection the Perforce connection being used for the analysis.
     * @param clFrom the oldest changelist number in the range being analysed
     * @param clTo the newest changelist number in the range being analysed
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the
     *                                source project being analysed
     * @return map keyed by depot path with the value being the matching {@link SourceFileDiffContext}
     */
    Map<String, SourceFileDiffContext> getSourceFilesImpactedFromPreviousSubmit(P4Connection p4Connection,
                                                                                        String clFrom, String clTo,
                                                                                        List<IFileSpec> sourceAndTestFilesSpecs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        log.info("Finding the impacted sources code files in P4 for the changelist range from {} to {}", clFrom, clTo);

        // Query in client syntax (not stream syntax) so the server expands the whole client
        // view - including stream import/overlay paths whose depot location lives outside the
        // stream. A stream-scoped query silently omits submitted changes to imported source
        // files, so no tests would be selected for them. The downstream source-dir filter
        // (isFileInSourceOrTestDir) already supports imported files: it compares against the
        // configured source dirs resolved through the client view via p4 where.
        String clientName = p4Connection.getClient().getName();
        String filePaths = "//" + clientName + "/...@" + clFrom + "," + clTo;

        try {
            // allRevs=true so files changed in multiple CLs in the range come back one entry
            // per (file, CL) - required for the action-override semantics in buildDiffContext
            // (ADD-then-EDIT in the range collapses to ADD).
            List<IFileSpec> changedFiles = p4Connection.getServer().getDepotFiles(
                    FileSpecBuilder.makeFileSpecList(filePaths), true);

            if (changedFiles == null || changedFiles.isEmpty()){
                log.warn("Couldn't find any file changes for the P4 file paths {}", filePaths);
                return sourceFileDiffContexts;
            }

            buildDiffContextsForFileSpecs(p4Connection, changedFiles, sourceFileDiffContexts, sourceAndTestFilesSpecs);
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceFileDiffContexts;
    }

    /**
     * Find all the source code files that have been changed locally compared to HEAD.
     * For each impacted source code file, load the file content from the starting revision and the head revision.
     *
     * @param p4Context object used to hold data about a Perforce server being analysed by Tia.
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Set<SourceFileDiffContext> getLocalChanges(P4Context p4Context, List<IFileSpec> sourceAndTestFilesSpecs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        List<IExtendedFileSpec> changedLocalFiles = getSourceFilesImpactedFromLocalChanges(p4Context.getP4Connection(),
                sourceFileDiffContexts, sourceAndTestFilesSpecs);

        log.info("Source files found with local changes: {}", sourceFileDiffContexts.keySet().stream().map( key ->
                convertDepotPathToTiaPath(key, sourceAndTestFilesSpecs)).collect(Collectors.toList()));

        // Capture each changed file's local workspace path on its diff context so the deferred
        // content load can read the working-copy "new" content without needing the live
        // IExtendedFileSpec.
        for (IExtendedFileSpec changedLocalFile : changedLocalFiles) {
            SourceFileDiffContext diffContext = sourceFileDiffContexts.get(changedLocalFile.getDepotPathString());
            if (diffContext != null) {
                diffContext.setLocalContentPath(changedLocalFile.getOriginalPathString());
            }
        }

        // Content is loaded by the caller (loadContentForDiffContexts); this method returns the
        // diff list only.
        return new HashSet<>(sourceFileDiffContexts.values());
    }

    /**
     * Get all source files impacted by local submitted changes compareD.
     *
     * @param p4Connection the Perforce connection being used for the analysis.
     * @param sourceFileDiffContexts the map of source files that were impacted in the diff range
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return list of files that have been changes in the local workspace.
     */
    private List<IExtendedFileSpec> getSourceFilesImpactedFromLocalChanges(P4Connection p4Connection,
                                                                           Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                                           List<IFileSpec> sourceAndTestFilesSpecs){
        log.info("Finding the impacted sources code files in P4 for local changes compared to HEAD");
        List<IExtendedFileSpec> sourceCodeFiles = new ArrayList<>();

        try {
            GetExtendedFilesOptions options = new GetExtendedFilesOptions();
            FileStatOutputOptions fstatOutputOptions = new FileStatOutputOptions();
            fstatOutputOptions.setOpenedFiles(true);
            options.setOutputOptions(fstatOutputOptions);
            List<IExtendedFileSpec> openedFiles = p4Connection.getServer().getExtendedFiles(FileSpecBuilder.makeFileSpecList("//..."), options);

            if (openedFiles == null || openedFiles.isEmpty()){
                log.info("No local workspace changes found");
            } else {
                sourceCodeFiles = buildDiffContextsForFileSpecs(p4Connection, openedFiles, sourceFileDiffContexts,
                        sourceAndTestFilesSpecs);
            }

        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceCodeFiles;
    }

    private <T extends IFileSpec> List<T> buildDiffContextsForFileSpecs(P4Connection p4Connection,
                                                                        List<T> fileSpecs,
                                                                        Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                                        List<IFileSpec> sourceAndTestFilesSpecs){
        List<T> sourceCodeFiles = filterValidSourceOrTestFiles(fileSpecs, sourceAndTestFilesSpecs);

        // Deduplicate depot paths for the `where` call. With allRevs=true on the upstream
        // range query, a file changed in multiple CLs returns one IFileSpec entry per (file, CL)
        // - so sourceCodeFiles can contain several specs sharing the same depot path. The
        // `where` resolution from depot path to local path is identical regardless of CL, so
        // we ask Perforce once per unique path and key the result map by depot path.
        // The downstream iteration over sourceCodeFiles still sees all per-CL entries (needed
        // for the action-override semantics in buildDiffContext).
        List<String> uniqueDepotPaths = sourceCodeFiles.stream()
                .map(IFileSpec::getDepotPathString)
                .distinct()
                .collect(Collectors.toList());
        Map<String, IFileSpec> localFileSpecs;

        try {
            // use P4 where command to find out the local paths for the changed files (local or submitted)
            List<IFileSpec> whereFileSpecs = p4Connection.getClient().where(FileSpecBuilder.makeFileSpecList(uniqueDepotPaths));
            // (existing, replacement) -> existing : where-results for the same depot path are
            // equivalent regardless of CL; pick the first to avoid throwing if p4 ever returns
            // duplicates on its own.
            localFileSpecs = whereFileSpecs.stream()
                    .collect(Collectors.toMap(IFileSpec::getDepotPathString, Function.identity(), (existing, replacement) -> existing));
        } catch (ConnectionException | AccessException e) {
            throw new VCSAnalyzerException(e);
        }

        sortFileChanges(sourceCodeFiles);
        for (T fileSpec: sourceCodeFiles){
            String depotPath = fileSpec.getDepotPathString();
            FileAction changeType = fileSpec.getAction();
            String localPath = localFileSpecs.get(depotPath).getLocalPathString();

            if (isFileAdded(changeType)){
                // if the old path doesn't exist but the new path is source code, a new source file has been added
                addDiffContext(null, localPath, changeType, sourceFileDiffContexts, depotPath);
            } else {
                // file has been modified or deleted
                addDiffContext(localPath, null, changeType, sourceFileDiffContexts, depotPath);
            }
        }

        return sourceCodeFiles;
    }

    /**
     * Filter out any files that have changed that aren't source or test files.
     * We only track an analyze source and test files in Tia.
     *
     * @param allFileSpecs
     * @param sourceAndTestFilesSpecs
     * @return
     */
    private <T extends IFileSpec> List<T> filterValidSourceOrTestFiles(List<T> allFileSpecs, List<IFileSpec> sourceAndTestFilesSpecs){
        List<T> validSourceOrTestFiles = new ArrayList<>();

        for (T fileSpec: allFileSpecs) {
            String depotPath = fileSpec.getDepotPathString();

            if (isFileSourceCode(depotPath) && isFileInSourceOrTestDir(depotPath, sourceAndTestFilesSpecs)){
                validSourceOrTestFiles.add(fileSpec);
            }
        }

        return validSourceOrTestFiles;
    }

    /**
     * When we retrieve the list of changes from between points, it will return all the individual CLs.
     * To process the CL's and understand the file changes correctly, we need to process the files in order of change.
     * This allows us to understand the state of the file at the beginning of the range of CL's, and at the end.
     *
     * For example, if a file was added and then edited, the change type for this file for processing within Tia should
     * be added (not edited).
     *
     * @param sourceCodeFiles
     */
    private void sortFileChanges(List<? extends IFileSpec> sourceCodeFiles){
        sourceCodeFiles.sort(Comparator.comparing(IFileSpec::getChangelistId));
    }

    /**
     * Check if the file exists in one of the source code or test file directories. If not, we don't want to proces
     * the file. The file might below to another application outside the project being analyzed.
     *
     * @param depotPath the depot path of the file
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return does the file exist in one of the source code or test directories being analysed
     */
    private boolean isFileInSourceOrTestDir(String depotPath, List<IFileSpec> sourceAndTestFilesSpecs){
        for (IFileSpec sourceAndTestFilesSpec: sourceAndTestFilesSpecs){
            if (depotPath.contains(sourceAndTestFilesSpec.getDepotPathString())){
                return true;
            }
        }
        log.trace("Skipping file not found in a source or test directory being analysed: {}", depotPath);
        return false;
    }

    /**
     * Convert the P4 depot path to the format needed for Tia mappings. Strip away the depot up to the src folder.
     * i.e. From:
     * //apps/example/tia-test-project/src/main/java/com/example/Car.java
     * to
     * src/main/java/com/example/Car.java
     *
     * @param depotPath
     * @param sourceAndTestFilesSpecs the list of source and test directory file specs for the source project being analysed
     * @return
     */
    private String convertDepotPathToTiaPath(String depotPath, List<IFileSpec> sourceAndTestFilesSpecs){
        String tiaMappingPath = depotPath;

        if (tiaMappingPath != null){
            for (IFileSpec sourceAndTestFilesSpec : sourceAndTestFilesSpecs){
                if (tiaMappingPath.contains(sourceAndTestFilesSpec.getDepotPathString())){
                    // use +1 to remove the leading '/'
                    tiaMappingPath = tiaMappingPath.substring(sourceAndTestFilesSpec.getDepotPathString().length() + 1);
                    break;
                }
            }
        }

        return tiaMappingPath;
    }

    private void addDiffContext(String diffOldPath, String diffNewPath, FileAction changeType,
                                Map<String, SourceFileDiffContext> sourceFileDiffContexts, String pathForTracking) {
        SourceFileDiffContext diffContext = buildDiffContext(diffOldPath, diffNewPath, convertP4ChangeType(changeType),
                sourceFileDiffContexts, pathForTracking);

        if (diffContext != null){
            // The depot path is the handle the content readers fetch by; record it so content can
            // be loaded in a later step keyed off the context alone.
            diffContext.setVcsFetchKey(pathForTracking);
            sourceFileDiffContexts.put(pathForTracking, diffContext);
        }
    }

    /**
     * Build the diff context for a source file that has changed. The diff context will be used to load the before and
     * after file content used to perform the diff and understand which methods have changed, and which tests to run.
     *
     * In some scenarios the change action is overridden. For Perforce we load all the individual CL's from the start of
     * the range to the end and then iterate over them sequentially from oldest to newest. In some cases a file can be
     * changed multiple times within the CL range. i.e. when Tia hasn't run for a while. If Tia is run after every commit
     * this situation shouldn't happen.
     *
     * We want the end change action to reflect the over change from the beginning as we only keep 1 diff context for the file.
     * i.e. if a file was added then edited in the CL range, we want the final action type for the diff context to be
     * 'added' as that's the final state when compared to the state at the beginning of the CL range.
     *
     * Override mapping:
     * 1st commit -> 2nd commit = overridden action
     * add -> edit = add
     * add -> delete = delete (treat the file like it never existed in the CL range)
     * edit -> edit = edit
     * edit -> delete = delete
     * delete -> add = edit
     *
     * @param diffOldPath
     * @param diffNewPath
     * @param currentChangeType
     * @param sourceFileDiffContexts
     * @param pathForTracking
     * @return the SourceFileDiffContext for the file change, or null if the file change should not be processed by Tia for selection.
     */
    private SourceFileDiffContext buildDiffContext(String diffOldPath, String diffNewPath, ChangeType currentChangeType,
                                                   Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                   String pathForTracking){

        SourceFileDiffContext newDiffContext = new SourceFileDiffContext(diffOldPath, diffNewPath, currentChangeType);
        SourceFileDiffContext previousDiffContext = sourceFileDiffContexts.get(pathForTracking);

        if (previousDiffContext != null){
            ChangeType previousChangeType = previousDiffContext.getChangeType();

            // if a file is both added and edited in the CL range, keep the action as add
            if (previousChangeType == ChangeType.ADD && currentChangeType == ChangeType.MODIFY){
                log.debug("Converting change type from MODIFY back to ADD given the file didn't exist at the beginning of the CL range: {}", pathForTracking);
                newDiffContext = previousDiffContext;
            }

            // if a file existed before, and was deleted and then added back in the CL range, treat the action as edit
            if (previousChangeType == ChangeType.DELETE && currentChangeType == ChangeType.ADD){
                log.debug("Converting change type from ADD back to MODIFY as the file previously existed at the start of the CL range: {}", pathForTracking);
                previousDiffContext.setChangeType(ChangeType.MODIFY);
                newDiffContext = previousDiffContext;
            }

            // delete file from list (never existed previously, doesn't exist now, no Tia tests to run)
            if (previousChangeType == ChangeType.ADD && currentChangeType == ChangeType.DELETE){
                log.debug("Not adding a file to the tracked source file diffs due to it being added and deleted in the CL range: {}", pathForTracking);
                newDiffContext = null;
            }
        }

        return newDiffContext;
    }

    /**
     * Read the content of a list of source files at a given version.
     * Read the content for each of the given source files and load it into a map to be used later for diffing.
     *
     * @param p4Connection the Perforce connection being used for the analysis.
     * @param revision the revision specifier appended to each depot path, e.g. {@code @1234}
     *                 or {@link #HAVE_REVISION}
     * @param forOriginal is the file content being read used as the 'before' in the diff?
     * @param sourceFileDiffContexts the map of source files that were impacted in the diff range
     */
    private void readFileContentForVersion(P4Connection p4Connection, String revision, boolean forOriginal,
                                           Map<String, SourceFileDiffContext> sourceFileDiffContexts) {
        try {
            List<String> filePaths = new ArrayList<>();
            sourceFileDiffContexts.forEach((depotPath, sourceFileDiffContext) -> {
                // don't try read the original content for files that were added
                if (forOriginal && sourceFileDiffContext.getChangeType() != ChangeType.ADD){
                    filePaths.add(depotPath + revision);
                }

                // don't try read the new content for files that were deleted
                if (!forOriginal && sourceFileDiffContext.getChangeType() != ChangeType.DELETE){
                    filePaths.add(depotPath + revision);
                }
            });

            List<IFileSpec> searchFileSpecs = FileSpecBuilder.makeFileSpecList(filePaths);
            List<IFileSpec> revisionFileSpecs = p4Connection.getServer().getDepotFiles(searchFileSpecs, false);

            if (revisionFileSpecs == null || revisionFileSpecs.isEmpty()){
                throw new VCSAnalyzerException("Couldn't find the source files in Perforce for revision " + revision);
            }

            loadFileSpecsContentIntoDiffContext(p4Connection, revisionFileSpecs, sourceFileDiffContexts, forOriginal, revision);
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    /**
     * Read each diff's working-copy content from disk (via its
     * {@link SourceFileDiffContext#getLocalContentPath() local content path}) and set it as the
     * original or new content. Used for the local-changes flow where the workspace files are the
     * "after" side of the diff. Diffs with no local content path (e.g. nothing opened on disk)
     * are skipped.
     *
     * @param diffs the diff contexts to populate
     * @param forOriginal {@code true} to set the content as the "before" side, {@code false} for "after"
     */
    private void loadLocalContentFromDisk(Collection<SourceFileDiffContext> diffs, boolean forOriginal){
        for (SourceFileDiffContext diff : diffs){
            String localContentPath = diff.getLocalContentPath();
            if (localContentPath == null){
                continue;
            }
            File file = new File(localContentPath);
            try {
                String fileContent = FileUtils.readFileToString(file, "UTF-8");
                if (forOriginal){
                    diff.setSourceContentOriginal(fileContent);
                } else {
                    diff.setSourceContentNew(fileContent);
                }
            } catch (IOException e) {
                throw new VCSAnalyzerException(e);
            }
        }
    }

    /**
     * Load content for every file in {@code revisionFileSpecs} at the given revision into
     * the matching {@link SourceFileDiffContext}. Fetches all files in a single
     * {@code p4 print} round-trip per version - replaces the older per-file loop that made
     * one round-trip per file.
     *
     * <p>Implementation: call {@code IServer.execStreamCmd("print", argv)} directly with
     * a {@code String[]} of {@code //depot/path@<CL>} arguments. The higher-level
     * {@code getFileContents(List<IFileSpec>, opts)} API can't be used here because p4java's
     * internal {@code IFileSpec -> wire args} conversion drops the revision (it reads
     * {@code IFileSpec.getDepotPathString()}, which is {@code null} for specs constructed
     * from a {@code "//path#rev"} string) so the server always returns head. Going through
     * {@code execStreamCmd} with explicit argv puts the annotation on the wire verbatim -
     * the exact equivalent of running {@code p4 print //path1@1234 //path2@1234} from the
     * command line.
     *
     * <p>The {@code @CL} annotation (rather than {@code #rev}) is used because the file
     * specs returned by {@code getDepotFiles(...@CL, false)} don't reliably populate a
     * revision-number field across p4java versions and file-action types. The exact field
     * (e.g. {@code endRevision} vs {@code headRev}) that carries the resolved revision
     * varies, and for newly-added files at their first revision the populated field can be
     * {@code -1} or {@code 0} - both of which produce a bogus {@code #rev} suffix that
     * {@code p4 print} can't parse, dropping that file silently from the batched response.
     * Using {@code @CL} sidesteps the ambiguity entirely: Perforce resolves the per-file
     * revision at that CL on the server side, the same way {@code getDepotFiles} originally
     * resolved it.
     *
     * <p>The returned stream is the concatenation of every file's {@code p4 print} output:
     * each file is prefixed by a header line of the form
     * {@code //depot/path#rev - <action> change <CL> (<type>)} followed by the raw file
     * content bytes. {@link #parseBatchedPrintStream} locates the header line positions in
     * the byte buffer and slices the content for each file as the bytes between two
     * consecutive header lines (preserving line endings, leading blank lines and trailing
     * newlines verbatim - important for line-level diff accuracy).
     *
     * @param p4Connection the Perforce connection used to call {@code execStreamCmd}
     * @param revisionFileSpecs the file specs returned by {@code getDepotFiles} - each carries
     *                          a depot path for the file at the requested CL; specs with a
     *                          null depot path (file missing at this revision) are skipped
     * @param sourceFileDiffContexts the diff-context map keyed by depot path; each context's
     *                               {@code sourceContentOriginal} or {@code sourceContentNew}
     *                               is populated based on {@code forOriginal}
     * @param forOriginal {@code true} when fetching the "before" version, {@code false} for
     *                    the "after" version
     * @param revision the revision specifier appended to each depot path in the argv, e.g.
     *                 {@code @1234} or {@link #HAVE_REVISION} (the same specifier that was passed
     *                 to {@code getDepotFiles} to resolve the specs)
     */
    private void loadFileSpecsContentIntoDiffContext(P4Connection p4Connection,
                                                     List<IFileSpec> revisionFileSpecs,
                                                     Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                     boolean forOriginal,
                                                     String revision) {
        List<String> expectedDepotPaths = new ArrayList<>(revisionFileSpecs.size());
        for (IFileSpec fileSpec : revisionFileSpecs) {
            String depotPath = fileSpec.getDepotPathString();
            if (depotPath == null) {
                // The file doesn't exist in the CL. With allRevs=true on the range query in
                // getSourceFilesImpactedFromPreviousSubmit, this should be rare - the
                // action-override semantics in buildDiffContext correctly classify
                // ADD-then-EDIT-in-range as ADD, so the forOriginal=true pass skips it via
                // the changeType filter rather than reaching this branch. The remaining
                // legitimate case is merge-of-delete (file deleted in both the original CL
                // and the new CL).
                log.info("No file found in P4 for the CL {} (forOriginal={})",
                        fileSpec.getChangelistId(), forOriginal);
                continue;
            }
            expectedDepotPaths.add(depotPath);
        }
        if (expectedDepotPaths.isEmpty()) {
            return;
        }

        Map<String, String> contentByDepotPath = printDepotFilesAtRevision(p4Connection, expectedDepotPaths, revision,
                "forOriginal=" + forOriginal);
        for (String depotPath : expectedDepotPaths) {
            loadFileContentIntoDiffContext(sourceFileDiffContexts, forOriginal, depotPath,
                    contentByDepotPath.get(depotPath));
        }
    }

    /**
     * Fetch the content of every given depot path at the given revision in a single batched
     * {@code p4 print} round-trip and return it keyed by depot path. See
     * {@link #loadFileSpecsContentIntoDiffContext} for why the argv is built by hand with a
     * revision annotation and how the concatenated output is split per file. Every requested
     * path must exist at the revision: a path whose header is missing from the response fails
     * fast rather than being returned without content, because silently missing content would
     * mis-select tests.
     *
     * @param p4Connection the Perforce connection used to call {@code execStreamCmd}.
     * @param depotPaths the depot paths to print; must be non-empty and exist at {@code revision}.
     * @param revision the revision specifier appended to each depot path, e.g. {@code @1234}
     *                 or {@link #HAVE_REVISION}.
     * @param requestLabel a short description of the request, included in error messages.
     * @return the content of every requested depot path, keyed by depot path.
     */
    private Map<String, String> printDepotFilesAtRevision(final P4Connection p4Connection,
                                                          final List<String> depotPaths,
                                                          final String revision,
                                                          final String requestLabel) {
        List<String> argvList = new ArrayList<>(depotPaths.size());
        for (String depotPath : depotPaths) {
            argvList.add(depotPath + revision);
        }

        byte[] streamBytes;
        try {
            InputStream stream = p4Connection.getServer().execStreamCmd("print",
                    argvList.toArray(new String[0]));
            if (stream == null) {
                throw new VCSAnalyzerException("Batched p4 print returned a null stream for "
                        + argvList.size() + " files (" + requestLabel + ")");
            }
            try {
                streamBytes = readAllBytes(stream);
            } finally {
                stream.close();
            }
        } catch (P4JavaException | IOException e) {
            throw new VCSAnalyzerException(e);
        }

        Map<String, String> contentByDepotPath = parseBatchedPrintStream(streamBytes, depotPaths);
        for (String depotPath : depotPaths) {
            if (!contentByDepotPath.containsKey(depotPath)) {
                // We asked p4 print for this depot path but no header for it appeared in the
                // response. Either p4java returned a partial stream or the demux missed a
                // header - either way, silently leaving the content null would mis-select
                // tests, so fail fast.
                throw new VCSAnalyzerException("Batched p4 print returned no content for depot path "
                        + depotPath + " (" + requestLabel + ")");
            }
        }
        return contentByDepotPath;
    }

    /**
     * Read every byte of {@code stream} into an in-memory byte array. The batched
     * {@code p4 print} output is bounded by the total size of the changed source files in
     * the range - kilobytes to a few megabytes in normal use - so buffering in memory is
     * fine and keeps the parse loop simple.
     *
     * @param stream the input stream to drain (caller closes it)
     * @return the byte contents
     * @throws IOException if the underlying read fails
     */
    private byte[] readAllBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /**
     * Locate each {@code //depot/path#rev - ...} header line in {@code streamBytes} and slice
     * the content for each file as the verbatim bytes between consecutive header line
     * starts. Slicing by byte position (rather than reading line-by-line and rejoining)
     * preserves line endings, leading blank lines and trailing newlines exactly as the
     * server emitted them - critical so the line-level diff comparison in
     * {@code MethodImpactAnalyzer} sees the same byte shape that the serial per-file fetch
     * would have produced.
     *
     * @param streamBytes the raw stream contents
     * @param expectedDepotPaths depot paths the caller requested (used to validate header
     *                           candidates - prevents a content line that happens to look
     *                           like a header from being mistaken for one)
     * @return content keyed by depot path; files whose header was not found in the stream
     *         are absent from the map
     */
    /**
     * Matches the portion of a p4 print header that comes <em>after</em> {@code <path>#}, up
     * to the end of the header line. Shape: {@code <digits> - <whatever> (<type>)}. The
     * {@code <whatever>} segment varies by action ({@code edit change 1234},
     * {@code integrate change 1234 from //other#3}, etc.) so we don't pin its internal
     * structure; only the bookend digits, the {@code  - }, and the trailing parenthesised
     * file-type segment are load-bearing.
     */
    private static final Pattern P4_PRINT_HEADER_TAIL =
            Pattern.compile("\\d+ - .* \\([^)]+\\)");

    private Map<String, String> parseBatchedPrintStream(byte[] streamBytes,
                                                        List<String> expectedDepotPaths) {
        String stream = new String(streamBytes, StandardCharsets.UTF_8);

        // Phase 1: locate every expected file's header by direct substring search and
        // header-shape validation. We do NOT require the header to be preceded by '\n' -
        // a source file whose content lacks a trailing '\n' makes the next file's header
        // physically adjacent to the prior file's content tail in the stream bytes
        // ("<prior content tail>//nextFile#1 - add change 1234 (text)\n"). Java source
        // files commonly omit the trailing newline, so this case must be handled.
        //
        // Instead of a boundary check, we validate the full header-line shape after the
        // path's '#' (digits, ' - ', whatever, ' (<type>)'). The chance of source code
        // accidentally containing this exact substring sequence for one of THIS run's
        // expected depot paths is vanishingly small.
        List<int[]> headerSpans = new ArrayList<>();   // [headerStartIdx, contentStartIdx]
        List<String> headerDepotPaths = new ArrayList<>();
        for (String expectedPath : expectedDepotPaths) {
            String needle = expectedPath + "#";
            int searchFrom = 0;
            while (searchFrom <= stream.length() - needle.length()) {
                int idx = stream.indexOf(needle, searchFrom);
                if (idx < 0) {
                    break;
                }

                int afterHash = idx + needle.length();
                int nlIdx = stream.indexOf('\n', afterHash);
                int lineEnd = (nlIdx == -1) ? stream.length() : nlIdx;
                int lineDataEnd = lineEnd;
                if (lineDataEnd > afterHash && stream.charAt(lineDataEnd - 1) == '\r') {
                    lineDataEnd--; // ignore CR for header-shape validation
                }

                Matcher m = P4_PRINT_HEADER_TAIL.matcher(stream);
                m.region(afterHash, lineDataEnd);
                if (m.matches()) {
                    int contentStart = (nlIdx == -1) ? stream.length() : nlIdx + 1;
                    headerSpans.add(new int[]{idx, contentStart});
                    headerDepotPaths.add(expectedPath);
                    break;  // first valid header for this path wins; move to next path
                }
                searchFrom = idx + 1;
            }
        }

        // Phase 2: sort header positions in stream order (p4's response order isn't
        // guaranteed to match the order of expectedDepotPaths), then slice content for
        // each file as the bytes between its header's end and the next header's start.
        List<Integer> orderedIndices = new ArrayList<>(headerSpans.size());
        for (int i = 0; i < headerSpans.size(); i++) {
            orderedIndices.add(i);
        }
        orderedIndices.sort((a, b) -> Integer.compare(headerSpans.get(a)[0], headerSpans.get(b)[0]));

        Map<String, String> contentByDepotPath = new HashMap<>();
        for (int i = 0; i < orderedIndices.size(); i++) {
            int spanIdx = orderedIndices.get(i);
            int contentStart = headerSpans.get(spanIdx)[1];
            int contentEnd = (i < orderedIndices.size() - 1)
                    ? headerSpans.get(orderedIndices.get(i + 1))[0]
                    : stream.length();
            contentByDepotPath.put(headerDepotPaths.get(spanIdx), stream.substring(contentStart, contentEnd));
        }
        return contentByDepotPath;
    }

    private void loadFileContentIntoDiffContext(Map<String, SourceFileDiffContext> sourceFileDiffContexts, boolean forOriginal,
                                                String depotPath, String fileContent) {
        if (forOriginal) {
            sourceFileDiffContexts.get(depotPath).setSourceContentOriginal(fileContent);
        } else {
            sourceFileDiffContexts.get(depotPath).setSourceContentNew(fileContent);
        }
    }

    private boolean isFileAdded(FileAction changeType){
        return convertP4ChangeType(changeType) == ChangeType.ADD;
    }

    private ChangeType convertP4ChangeType(FileAction p4ChangeType){
        switch(p4ChangeType){
            case ADD:
            case MOVE_ADD:
            case ADDED:
            case BRANCH:
                return ChangeType.ADD;
            case EDIT:
            case INTEGRATE:
            case EDIT_FROM:
            case EDIT_IGNORED:
            case UPDATED:
            case REFRESHED:
            case RESOLVED:
            case REPLACED:
                return ChangeType.MODIFY;
            case DELETE:
            case MOVE_DELETE:
            case DELETED:
                return ChangeType.DELETE;
        }

        return null;
    }

    /**
     * Check if a file is a source code file.
     * i.e. Is it a Java  or Groovy file.
     *
     * @param fileName
     * @return
     */
    private boolean isFileSourceCode(String fileName) {
        return fileName.toLowerCase().endsWith("." + JAVA_FILE_EXT) ||
                fileName.toLowerCase().endsWith("." + GROOVY_FILE_EXT);
    }
}
