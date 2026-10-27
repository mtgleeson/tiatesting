package org.tiatesting.vcs.git;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.core.vcs.VCSAnalyzerException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.tiatesting.core.sourcefile.FileExtensions.GROOVY_FILE_EXT;
import static org.tiatesting.core.sourcefile.FileExtensions.JAVA_FILE_EXT;

/**
 * Build the list of source files that have been changed since the previously analyzed commit.
 */
public class GitDiffAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GitDiffAnalyzer.class);

    /** Length of the abbreviated commit ids reported for mapping drift. */
    private static final int ABBREVIATED_COMMIT_LENGTH = 8;

    /**
     * Build the list of files that have changes either since the previously stored commit, or from local uncommited changes.
     * Return the list of SourceFileDiffContext.
     *
     * @param gitContext object used to hold data about a Git repository being analysed by Tia.
     * @param commitFrom the oldest commit number in the range being analysed
     * @param sourceAndTestDirs the list of source code and test files for the source project being analysed
     * @param checkLocalChanges should local changes be analyzed for test selection
     * @return list of SourceFileDiffContext for the files impacted in the given commit range to head
     */
    protected Set<SourceFileDiffContext> getDiffFiles(final GitContext gitContext, final String commitFrom,
                                                      final List<String> sourceAndTestDirs,
                                                      final boolean checkLocalChanges) {
        ObjectId commitFromObjectId = getObjectId(gitContext, commitFrom);
        ObjectId commitToObjectId = gitContext.getHeadObjectId();

        // get changes from the previously stored commit to HEAD - the diff list only, no content.
        // Content is loaded separately via loadContentForDiffs so the caller can restrict it to a
        // chosen subset of files.
        Set<SourceFileDiffContext> sourceFileDiffContexts = new HashSet<>();

        if (checkLocalChanges){
            // get the local changes compared to local HEAD
            sourceFileDiffContexts.addAll(getLocalChanges(gitContext, sourceAndTestDirs));
        } else {
            // get the changes since the CL that was last run with Tia
            sourceFileDiffContexts.addAll(getChangesSinceLastRunCommit(gitContext, commitFromObjectId, commitToObjectId,
                    sourceAndTestDirs));
        }

        return sourceFileDiffContexts;
    }

    /**
     * Load the before/after file content onto the given diff contexts, keyed by each context's
     * {@link SourceFileDiffContext#getVcsFetchKey() repo-relative fetch key}. For the commit-range
     * case the "before" is read at {@code commitFrom} and the "after" at head; for local changes
     * the "before" is read at head and the "after" is the working-copy content read from disk.
     *
     * @param gitContext the Git context (repository + head)
     * @param diffs the diff contexts to populate; may be empty
     * @param commitFrom the commit the mapping is valid for (the commit-range "before")
     * @param checkLocalChanges whether the diffs are local-workspace changes
     */
    void loadContentForDiffContexts(GitContext gitContext, Collection<SourceFileDiffContext> diffs,
                                            String commitFrom, boolean checkLocalChanges) {
        if (diffs.isEmpty()) {
            return;
        }

        // Rebuild the repo-relative-path-keyed map the content readers expect from the contexts' fetch keys.
        Map<String, SourceFileDiffContext> diffsByPath = new HashMap<>();
        for (SourceFileDiffContext diff : diffs) {
            diffsByPath.put(diff.getVcsFetchKey(), diff);
        }

        Repository repository = gitContext.getRepository();
        if (checkLocalChanges) {
            readFileContentForVersion(repository, gitContext.getHeadObjectId(), true, diffsByPath);
            readLocalFileContent(repository, false, diffsByPath);
        } else {
            ObjectId commitFromObjectId = getObjectId(gitContext, commitFrom);
            readFileContentForVersion(repository, commitFromObjectId, true, diffsByPath);
            readFileContentForVersion(repository, gitContext.getHeadObjectId(), false, diffsByPath);
        }
    }

    /**
     * Return the repo-relative, forward-slash-normalised paths of every file changed either since
     * the previously stored commit or in the local workspace. No file content is loaded and no
     * file-type filter is applied (paths for all file types are returned). For renames and
     * copies both the source and destination paths are included; the {@code /dev/null}
     * placeholder used by JGit for adds (old path) and deletes (new path) is excluded.
     *
     * @param gitContext object used to hold data about a Git repository being analysed by Tia.
     * @param commitFrom the oldest commit number in the range being analysed; ignored when
     *                   {@code checkLocalChanges} is {@code true}.
     * @param checkLocalChanges when {@code true}, return paths for files modified in the
     *                          local workspace rather than the commit range.
     * @return the set of changed file paths; never {@code null}, may be empty.
     */
    protected Set<String> getChangedFilePaths(final GitContext gitContext, final String commitFrom,
                                              final boolean checkLocalChanges) {
        Set<String> changedPaths = new HashSet<>();
        Repository repository = gitContext.getRepository();

        try (DiffFormatter diffFormatter = new DiffFormatter(null)) {
            diffFormatter.setRepository(repository);

            List<DiffEntry> diffEntries;
            if (checkLocalChanges) {
                AbstractTreeIterator commitTreeIterator = prepareTreeParser(repository, Constants.HEAD);
                FileTreeIterator workTreeIterator = new FileTreeIterator(repository);
                diffEntries = diffFormatter.scan(commitTreeIterator, workTreeIterator);
            } else {
                ObjectId commitFromObjectId = getObjectId(gitContext, commitFrom);
                ObjectId commitToObjectId = gitContext.getHeadObjectId();
                diffEntries = diffFormatter.scan(commitFromObjectId, commitToObjectId);
            }

            for (DiffEntry diffEntry : diffEntries) {
                addPathIfReal(changedPaths, diffEntry.getOldPath());
                addPathIfReal(changedPaths, diffEntry.getNewPath());
            }
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }

        return changedPaths;
    }

    /**
     * Load the "before" and "after" content of the given repo-relative paths. For the commit
     * range the "before" side is read from the tree at {@code commitFrom} and the "after" side
     * from the tree at head; for local changes the "before" side is read at head and the "after"
     * side from the working copy on disk. Each path is looked up directly in the commit tree
     * (no full tree walk), so the cost scales with the number of paths requested.
     *
     * @param gitContext the Git context (repository + head).
     * @param paths the repo-relative, forward-slash paths to load.
     * @param commitFrom the commit the mapping is valid for (the commit-range "before"); ignored
     *                   when {@code checkLocalChanges} is {@code true}.
     * @param checkLocalChanges whether to compare head against the working copy.
     * @return one entry per requested path; a side is {@code null} when the file does not exist there.
     */
    Map<String, FileContentPair> loadFileContents(final GitContext gitContext, final Set<String> paths,
                                                  final String commitFrom, final boolean checkLocalChanges) {
        Map<String, FileContentPair> contents = new HashMap<>();
        if (paths.isEmpty()) {
            return contents;
        }

        Repository repository = gitContext.getRepository();
        Map<String, String> oldContent;
        Map<String, String> newContent;
        if (checkLocalChanges) {
            oldContent = readContentAtCommit(repository, gitContext.getHeadObjectId(), paths);
            newContent = readWorkingCopyContent(repository, paths);
        } else {
            ObjectId commitFromObjectId = getObjectId(gitContext, commitFrom);
            if (commitFromObjectId == null) {
                throw new VCSAnalyzerException("Couldn't resolve the commit " + commitFrom + " in the Git repository");
            }
            oldContent = readContentAtCommit(repository, commitFromObjectId, paths);
            newContent = readContentAtCommit(repository, gitContext.getHeadObjectId(), paths);
        }

        for (String path : paths) {
            contents.put(path, new FileContentPair(oldContent.get(path), newContent.get(path)));
        }
        return contents;
    }

    /**
     * Read the UTF-8 content of each path that exists in the tree of the given commit. Paths
     * missing from the tree (or naming a directory) are absent from the result.
     *
     * @param repository the Git repository.
     * @param commitObjectId the commit whose tree is read.
     * @param paths the repo-relative paths to read.
     * @return content keyed by path, for the paths that exist as files at that commit.
     */
    private Map<String, String> readContentAtCommit(final Repository repository, final ObjectId commitObjectId,
                                                    final Set<String> paths) {
        Map<String, String> contentByPath = new HashMap<>();
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevTree tree = revWalk.parseCommit(commitObjectId).getTree();
            for (String path : paths) {
                try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, tree)) {
                    if (treeWalk == null || treeWalk.isSubtree()) {
                        continue;
                    }
                    ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
                    contentByPath.put(path, new String(loader.getBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
        return contentByPath;
    }

    /**
     * Read the UTF-8 content of each path that exists as a file in the working copy.
     *
     * @param repository the Git repository whose working copy is read.
     * @param paths the repo-relative paths to read.
     * @return content keyed by path, for the paths that exist on disk.
     */
    private Map<String, String> readWorkingCopyContent(final Repository repository, final Set<String> paths) {
        Map<String, String> contentByPath = new HashMap<>();
        String projectPath = getProjectPath(repository);
        for (String path : paths) {
            File file = new File(buildLocalFilePath(path, projectPath));
            if (!file.isFile()) {
                continue;
            }
            try {
                contentByPath.put(path, FileUtils.readFileToString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new VCSAnalyzerException(e);
            }
        }
        return contentByPath;
    }

    /**
     * Add a JGit diff path to the set unless it is the {@code /dev/null} placeholder used for
     * the absent side of an add or delete. Paths from JGit are already repo-relative and use
     * forward slashes, so no further normalisation is required.
     *
     * @param paths the accumulator set.
     * @param diffPath the JGit-reported path; may be {@code /dev/null}.
     */
    private void addPathIfReal(final Set<String> paths, final String diffPath) {
        if (diffPath != null && !DiffEntry.DEV_NULL.equals(diffPath)) {
            paths.add(diffPath);
        }
    }

    /**
     * Find all the source code files in a list of revisions from a given commit value to the head of the VCS.
     * For each impacted source code file, load the file content from the starting revision and the head revision.
     *
     * @param gitContext object used to hold data about a Git repository being analysed by Tia.
     * @param commitFrom the oldest commit number in the range being analysed
     * @param commitTo the newest commit number in the range being analysed
     * @param sourceAndTestDirs the list of source code and test files for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Set<SourceFileDiffContext> getChangesSinceLastRunCommit(GitContext gitContext, ObjectId commitFrom,
                                                                    ObjectId commitTo, List<String> sourceAndTestDirs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts;
        sourceFileDiffContexts = getSourceFilesImpactedFromPreviousCommit(gitContext.getRepository(), commitFrom,
                commitTo, sourceAndTestDirs);
        log.info("Source files found in the commit range: {}", sourceFileDiffContexts.keySet());

        // Content is loaded by the caller (loadContentForDiffContexts) so it can be deferred to a
        // chosen subset; this method returns the diff list only.
        return new HashSet<>(sourceFileDiffContexts.values());
    }

    /**
     * Get all source files impacted by changes in a given commit range - commitFrom -> commitTo.
     *
     * @param repository the Git repository being analysed
     * @param commitFrom the oldest commit number in the range being analysed
     * @param commitTo the newest commit number in the range being analysed
     * @param sourceAndTestDirs the list of source code and test files for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Map<String, SourceFileDiffContext> getSourceFilesImpactedFromPreviousCommit(Repository repository,
                                                                                        ObjectId commitFrom, ObjectId commitTo,
                                                                                        List<String> sourceAndTestDirs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        log.info("Finding the impacted sources code files in Git for the commit range from {} to {}",
                commitFrom.getName(), commitTo.getName());

        try (DiffFormatter diffFormatter = new DiffFormatter(null)) {
            diffFormatter.setRepository(repository);

            for (DiffEntry diffEntry : diffFormatter.scan(commitFrom, commitTo)) {
                buildDiffContextsFromDiffEntries(diffEntry, sourceFileDiffContexts, getProjectPath(repository), sourceAndTestDirs);
            }
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceFileDiffContexts;
    }

    /**
     * Find all the source code files that have been changed locally compared to HEAD.
     * For each impacted source code file, load the file content from the starting revision and the head revision.
     *
     * @param gitContext object used to hold data about a Git repository being analysed by Tia.
     * @param sourceAndTestDirs the list of source code and test files for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private Set<SourceFileDiffContext> getLocalChanges(GitContext gitContext, List<String> sourceAndTestDirs){
        Map<String, SourceFileDiffContext> sourceFileDiffContexts;
        sourceFileDiffContexts = getSourceFilesImpactedFromLocalChanges(gitContext.getRepository(), sourceAndTestDirs);
        log.info("Source files found with local changes: {}", sourceFileDiffContexts.keySet());

        // Content is loaded by the caller (loadContentForDiffContexts); this method returns the
        // diff list only.
        return new HashSet<>(sourceFileDiffContexts.values());
    }

    /**
     * Get all source files impacted by local uncommitted changes compared to local HEAD.
     *
     * @param repository the Git repository being analysed
     * @param sourceAndTestDirs the list of source code and test files for the source project being analysed
     * @return map keyed by the old file path (or new path for new files) and the value being the SourceFileDiffContext
     */
    private  Map<String, SourceFileDiffContext> getSourceFilesImpactedFromLocalChanges(Repository repository,
                                                                                       List<String> sourceAndTestDirs){
        log.info("Finding the impacted sources code files in Git for local uncommited changes to workspace HEAD");
        Map<String, SourceFileDiffContext> sourceFileDiffContexts = new HashMap<>();
        AbstractTreeIterator commitTreeIterator = prepareTreeParser( repository,  Constants.HEAD );
        FileTreeIterator workTreeIterator = new FileTreeIterator( repository );

        try (DiffFormatter diffFormatter = new DiffFormatter(null)) {
            diffFormatter.setRepository(repository);
            for (DiffEntry diffEntry : diffFormatter.scan( commitTreeIterator, workTreeIterator )){
                buildDiffContextsFromDiffEntries(diffEntry, sourceFileDiffContexts, getProjectPath(repository), sourceAndTestDirs);
            }
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }

        return sourceFileDiffContexts;
    }

    private void buildDiffContextsFromDiffEntries(DiffEntry diffEntry, Map<String, SourceFileDiffContext> sourceFileDiffContexts,
                                                  String projectPath, List<String> sourceAndTestDirs) {
        String diffOldPath = diffEntry.getOldPath();
        String diffNewPath = diffEntry.getNewPath();
        DiffEntry.ChangeType changeType = diffEntry.getChangeType();
        log.trace("Found diff entry: old path: {}, new path: {}, change type: {}", diffOldPath, diffNewPath, changeType);

        String localOldPath = buildLocalFilePath(diffOldPath, projectPath);
        String localNewPath = buildLocalFilePath(diffNewPath, projectPath);

        if (isValidSourceOrTestFile(localOldPath, sourceAndTestDirs)){
            // file has been modified or deleted
            buildDiffContext(localOldPath, localNewPath, changeType, sourceFileDiffContexts, diffOldPath);
        } else if (isValidSourceOrTestFile(localNewPath, sourceAndTestDirs)){
            // if the old path doesn't exist but the new path is source code, a new source file has been added
            buildDiffContext(localOldPath, localNewPath, changeType, sourceFileDiffContexts, diffNewPath);
        }
    }

    private void buildDiffContext(String diffOldPath, String diffNewPath, DiffEntry.ChangeType changeType,
                                  Map<String, SourceFileDiffContext> sourceFileDiffContexts, String pathForTracking) {
        SourceFileDiffContext diffContext = new SourceFileDiffContext(diffOldPath, diffNewPath,
                convertGitChangeType(changeType));
        // The repo-relative path is the handle the content readers fetch by (object-store lookup
        // and local-disk path); record it so content can be loaded in a later step keyed off the
        // context alone.
        diffContext.setVcsFetchKey(pathForTracking);
        sourceFileDiffContexts.put(pathForTracking, diffContext);
    }

    private String buildLocalFilePath(String relativePath, String projectPath){
        String localPath = relativePath.contains(File.separator) ? relativePath : relativePath.replaceAll("\\\\", File.separator);
        return projectPath + File.separator + relativePath;
    }

    /**
     * Prepare a Git Tree Parser that can be used for scanning for differences
     *
     * @param repository the Git repository being analysed
     * @param ref the commit id reference
     * @return AbstractTreeIterator
     */
    private AbstractTreeIterator prepareTreeParser(Repository repository, String ref) {
        RevTree tree;

        try {
            Ref head = repository.getRefDatabase().findRef(ref);
            RevWalk walk = new RevWalk(repository);
            RevCommit commit = walk.parseCommit(head.getObjectId());
            tree = walk.parseTree(commit.getTree().getId());
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }

        CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
        ObjectReader oldReader = repository.newObjectReader();
        try {
            oldTreeParser.reset(oldReader, tree.getId());
        } catch (IncorrectObjectTypeException e) {
            throw new VCSAnalyzerException(e);
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        } finally {
            oldReader.close();
        }
        return oldTreeParser;
    }

    /**
     * Read the content of a list of source files at a given version.
     * Use a TreeWalk to recursively walk through the repository tree for a version. Read the content for each of the
     * given source files and load it into a map to be used later for diffing.
     *
     * @param repository the Git repository being analysed
     * @param commitObjectId the id of the commit revision we are reading the file content for
     * @param forOriginal is the file content being read used as the 'before' in the diff?
     * @param sourceFilesImpacted the map of source files that were impacted in the diff range
     */
    private void readFileContentForVersion(Repository repository, ObjectId commitObjectId, boolean forOriginal,
                                           Map<String, SourceFileDiffContext> sourceFilesImpacted) {
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(commitObjectId);
            RevTree tree = commit.getTree();

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                while(treeWalk.next()){
                    if (sourceFilesImpacted.containsKey(treeWalk.getPathString())){
                        ObjectId objectId = treeWalk.getObjectId(0);
                        ObjectLoader loader = repository.open(objectId);
                        loader.copyTo(baos);

                        if (forOriginal){
                            sourceFilesImpacted.get(treeWalk.getPathString()).setSourceContentOriginal(baos.toString());
                        } else {
                            sourceFilesImpacted.get(treeWalk.getPathString()).setSourceContentNew(baos.toString());
                        }

                        baos.reset();
                    }
                }
            }

            revWalk.dispose();
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    /**
     * Read the content of a list of source files from the local changes.
     * Read the content for each of the given source files and load it into a map to be used later for diffing.
     *
     * @param repository the Git repository being analysed
     * @param forOriginal is the file content being read used as the 'before' in the diff?
     * @param sourceFileDiffContexts the map of source files that were impacted in the diff range
     */
    private void readLocalFileContent(Repository repository, boolean forOriginal,
                                      Map<String, SourceFileDiffContext> sourceFileDiffContexts) {
        for (String sourceFileRelativePath: sourceFileDiffContexts.keySet()){
            String fullPath = buildLocalFilePath(sourceFileRelativePath, getProjectPath(repository));
            File file = new File(fullPath);
            try {
                String fileContent = FileUtils.readFileToString(file, "UTF-8");
                loadFileContentIntoDiffContext(sourceFileDiffContexts, forOriginal, sourceFileRelativePath, fileContent);
            } catch (IOException e) {
                throw new VCSAnalyzerException(e);
            }
        }
    }


    private void loadFileContentIntoDiffContext(Map<String, SourceFileDiffContext> sourceFileDiffContexts, boolean forOriginal,
                                                String fileRelativePath, String fileContent) {
        if (forOriginal) {
            sourceFileDiffContexts.get(fileRelativePath).setSourceContentOriginal(fileContent);
        } else {
            sourceFileDiffContexts.get(fileRelativePath).setSourceContentNew(fileContent);
        }
    }

    /**
     * For locally changed files, find the ones whose version at {@code HEAD} (the base of the
     * local edit) differs from their version at the stored commit, and the commits that make up
     * the difference. One tree walk over the two commits compares the files' blobs, so a workspace
     * at the stored commit, or one whose newer commits didn't touch these files, costs just that.
     * For each drifted file the commits that touched it are listed from the merge base of the two
     * commits up to the stored commit (missing from the workspace, behind) and up to {@code HEAD}
     * (not in the mapping, ahead - including the developer's own unpushed commits).
     *
     * @param gitContext the Git context (repository + head).
     * @param commitFrom the commit the stored mapping was built at.
     * @param localDiffs the locally changed files to check; their fetch keys are repo-relative paths.
     * @return the drift of each drifted file keyed by fetch key, with abbreviated commit ids
     *         oldest first, or {@link MappingDrift#baselineNotFound()} when the stored commit is
     *         not in the local repository.
     */
    MappingDrift findMappingDrift(final GitContext gitContext, final String commitFrom,
                                  final Collection<SourceFileDiffContext> localDiffs) {
        if (localDiffs.isEmpty()) {
            return MappingDrift.none();
        }

        Repository repository = gitContext.getRepository();
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId storedObjectId = getObjectId(gitContext, commitFrom);
            if (storedObjectId == null || !repository.getObjectDatabase().has(storedObjectId)) {
                return MappingDrift.baselineNotFound();
            }

            RevCommit stored = walk.parseCommit(storedObjectId);
            RevCommit head = walk.parseCommit(gitContext.getHeadObjectId());
            if (stored.equals(head)) {
                return MappingDrift.none();
            }

            Set<String> paths = new LinkedHashSet<>();
            for (SourceFileDiffContext diff : localDiffs) {
                paths.add(diff.getVcsFetchKey());
            }
            Set<String> driftedPaths = findPathsDifferingBetween(repository, stored.getTree(), head.getTree(), paths);
            if (driftedPaths.isEmpty()) {
                return MappingDrift.none();
            }

            RevCommit mergeBase = findMergeBase(repository, stored, head);
            Map<String, MappingDrift.FileDrift> driftByPath = new LinkedHashMap<>();
            for (String path : paths) {
                if (driftedPaths.contains(path)) {
                    driftByPath.put(path, new MappingDrift.FileDrift(
                            findCommitsTouchingPath(repository, stored, mergeBase, path),
                            findCommitsTouchingPath(repository, head, mergeBase, path)));
                }
            }
            return MappingDrift.of(driftByPath);
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    /**
     * Find which of the given paths have a different blob in one tree than in the other,
     * including a path present in only one of them, with a single recursive tree walk.
     *
     * @param repository the Git repository.
     * @param first one tree.
     * @param second the other tree.
     * @param paths the repo-relative paths to compare.
     * @return the paths that differ between the trees.
     * @throws IOException if the repository can't be read.
     */
    private Set<String> findPathsDifferingBetween(final Repository repository, final RevTree first,
                                                  final RevTree second, final Set<String> paths) throws IOException {
        Set<String> differing = new HashSet<>();
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(first);
            treeWalk.addTree(second);
            treeWalk.setRecursive(true);
            treeWalk.setFilter(AndTreeFilter.create(PathFilterGroup.createFromStrings(paths), TreeFilter.ANY_DIFF));
            while (treeWalk.next()) {
                differing.add(treeWalk.getPathString());
            }
        }
        return differing;
    }

    /**
     * Find the merge base (best common ancestor) of two commits.
     *
     * @param repository the Git repository.
     * @param first one commit.
     * @param second the other commit.
     * @return the merge base, or {@code null} when the commits share no history.
     * @throws IOException if the repository can't be read.
     */
    private RevCommit findMergeBase(final Repository repository, final RevCommit first, final RevCommit second)
            throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(first));
            walk.markStart(walk.parseCommit(second));
            return walk.next();
        }
    }

    /**
     * List the commits that changed a path, reachable from {@code tip} but not from
     * {@code exclude} - the equivalent of {@code git log --reverse exclude..tip -- path}.
     *
     * @param repository the Git repository.
     * @param tip the newest commit in the range.
     * @param exclude the commit whose history is excluded; {@code null} to include all of
     *                {@code tip}'s history.
     * @param path the repo-relative path.
     * @return the abbreviated ids of the matching commits, oldest first; empty when {@code tip} is
     *         {@code exclude}.
     * @throws IOException if the repository can't be read.
     */
    private List<String> findCommitsTouchingPath(final Repository repository, final RevCommit tip,
                                                 final RevCommit exclude, final String path) throws IOException {
        List<String> commits = new ArrayList<>();
        if (tip.equals(exclude)) {
            return commits;
        }
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(tip));
            if (exclude != null) {
                walk.markUninteresting(walk.parseCommit(exclude));
            }
            walk.setTreeFilter(AndTreeFilter.create(PathFilter.create(path), TreeFilter.ANY_DIFF));
            for (RevCommit commit : walk) {
                commits.add(commit.abbreviate(ABBREVIATED_COMMIT_LENGTH).name());
            }
        }
        Collections.reverse(commits);
        return commits;
    }

    private ObjectId getObjectId(GitContext gitContext, String commit){
        try {
            return gitContext.getRepository().resolve(commit);
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
    }

    private ChangeType convertGitChangeType(DiffEntry.ChangeType gitChangeType){
        switch(gitChangeType){
            case ADD:
                return ChangeType.ADD;
            case MODIFY:
                return ChangeType.MODIFY;
            case DELETE:
                return ChangeType.DELETE;
            case RENAME:
                return ChangeType.RENAME;
            case COPY:
                return ChangeType.COPY;
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

    /**
     * Return the full path to the directory of the project.
     *
     * @param repository
     * @return
     */
    private String getProjectPath(Repository repository){
        String projectPathFull;
        try {
            projectPathFull = repository.getDirectory().getParentFile().getCanonicalPath();
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }
        return projectPathFull;
    }

    private boolean isValidSourceOrTestFile(String fileFullPath, List<String> sourceAndTestDirs){
        return isFileSourceCode(fileFullPath) && isFileInSourceOrTestDir(fileFullPath, sourceAndTestDirs);
    }

    private boolean isFileInSourceOrTestDir(String fileFullPath, List<String> sourceAndTestDirs){
        for (String sourceAndTestDir: sourceAndTestDirs){
            log.trace("Checking if file {} is in source or test directory: {}", fileFullPath, sourceAndTestDir);
            if (fileFullPath.contains(sourceAndTestDir)){
                return true;
            }
        }
        log.trace("Skipping file not found in a source or test directory being analysed: {}", fileFullPath);
        return false;
    }
}
