package org.tiatesting.core.staticselection.pom;

import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.VCSReader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Classifies the changes to POMs over one diff (a commit range or the local workspace) for
 * POM-aware static test selection rules. Each POM is classified in the environment of the other
 * POMs in the repository (in-repo parents, the reactor, child modules; see {@link PomTree}), which
 * are read only when a classification decision needs them. Content is fetched in batched VCS
 * calls, and each POM is classified once and cached, so several rules matching the same POM share
 * the work. An added or deleted POM is classified by whether another changed POM references it as a
 * module or parent (see {@link PomReferences}); the diff's other changed {@code pom.xml} files are
 * read for that in one call, and only when such a POM is classified. One instance serves one test
 * selection run.
 */
public class PomChangeAnalyzer {

    private static final String POM_FILE_NAME = "pom.xml";

    private final PomTree tree;
    private final PomChangeClassifier classifier;
    private final Set<String> changedPomPaths;
    private final Map<String, PomChangeReport> reportsByPath = new HashMap<>();
    private PomReferences references;

    /**
     * Construct an analyzer for one diff.
     *
     * @param vcsReader the VCS reader the POM content is read from.
     * @param baseChangeNum the "before" commit of the diff; ignored when {@code checkLocalChanges} is {@code true}.
     * @param checkLocalChanges whether the diff is head against the working copy.
     * @param changedPaths every changed path in the diff; its {@code pom.xml} files are the
     *                     candidates that may reference an added or deleted POM, read only when
     *                     such a POM is classified.
     * @param classifier the classifier, carrying the tracked-library coordinates.
     * @param externalPomSource reads POMs outside the repository (external parents, imported BOMs)
     *                          so their version bumps are classified by what they change;
     *                          {@code null} when not available, making such bumps
     *                          {@code DEPENDENCY_VERSIONS}.
     */
    public PomChangeAnalyzer(final VCSReader vcsReader, final String baseChangeNum, final boolean checkLocalChanges,
                             final Set<String> changedPaths, final PomChangeClassifier classifier,
                             final ExternalPomSource externalPomSource) {
        this.tree = new PomTree(vcsReader, baseChangeNum, checkLocalChanges, externalPomSource);
        this.classifier = classifier;
        this.changedPomPaths = pomFiles(changedPaths);
    }

    /**
     * Classify the changes to each given POM. The given POMs not analysed before are fetched in a
     * single {@link VCSReader#loadFileContents} call; other POMs are read only if a classification
     * needs them.
     *
     * @param paths the repo-relative POM paths, as {@link VCSReader#getChangedFilePaths} returns them.
     * @return one report per requested path, in the iteration order of {@code paths}.
     */
    public Map<String, PomChangeReport> analyze(final Set<String> paths) {
        Set<String> missing = new HashSet<>(paths);
        missing.removeAll(reportsByPath.keySet());
        if (!missing.isEmpty()) {
            tree.loadParentChains(missing);
            for (String path : missing) {
                FileContentPair content = tree.content(path);
                reportsByPath.put(path, classifier.classify(content.getOldContent(), content.getNewContent(),
                        PomTreeEnvironment.forChangedPom(tree, classifier, path, () -> references(paths))));
            }
        }

        Map<String, PomChangeReport> reports = new LinkedHashMap<>();
        for (String path : paths) {
            reports.put(path, reportsByPath.get(path));
        }
        return reports;
    }

    /**
     * The reference index over the diff's changed {@code pom.xml} files and the analysed paths
     * (which may use other file names), built on first use so a diff with no added or deleted
     * POM never reads it. Built once per analyzer: one analyzer serves one diff.
     *
     * @param analysedPaths the paths of the {@link #analyze} call that first needs the index.
     * @return the index.
     */
    private PomReferences references(final Set<String> analysedPaths) {
        if (references == null) {
            Set<String> referring = new LinkedHashSet<>(changedPomPaths);
            referring.addAll(analysedPaths);
            references = PomReferences.build(tree, referring);
        }
        return references;
    }

    /**
     * The {@code pom.xml} files among a diff's changed paths.
     *
     * @param changedPaths the changed paths.
     * @return the POM paths, in the given order.
     */
    private static Set<String> pomFiles(final Set<String> changedPaths) {
        Set<String> poms = new LinkedHashSet<>();
        for (String path : changedPaths) {
            if (path.equals(POM_FILE_NAME) || path.endsWith("/" + POM_FILE_NAME)) {
                poms.add(path);
            }
        }
        return poms;
    }
}
