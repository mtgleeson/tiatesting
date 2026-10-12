package org.tiatesting.core.staticselection.pom;

import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.VCSReader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Classifies the changes to POMs over one diff (a commit range or the local workspace) for
 * POM-aware static test selection rules. Each POM is classified in the environment of the other
 * POMs in the repository (in-repo parents, the reactor, child modules; see {@link PomTree}), which
 * are read only when a classification decision needs them. Content is fetched in batched VCS
 * calls, and each POM is classified once and cached, so several rules matching the same POM share
 * the work. One instance serves one test selection run.
 */
public class PomChangeAnalyzer {

    private final PomTree tree;
    private final PomChangeClassifier classifier;
    private final Map<String, PomChangeReport> reportsByPath = new HashMap<>();

    /**
     * Construct an analyzer for one diff.
     *
     * @param vcsReader the VCS reader the POM content is read from.
     * @param baseChangeNum the "before" commit of the diff; ignored when {@code checkLocalChanges} is {@code true}.
     * @param checkLocalChanges whether the diff is head against the working copy.
     * @param classifier the classifier, carrying the tracked-library coordinates.
     */
    public PomChangeAnalyzer(final VCSReader vcsReader, final String baseChangeNum, final boolean checkLocalChanges,
                             final PomChangeClassifier classifier) {
        this.tree = new PomTree(vcsReader, baseChangeNum, checkLocalChanges);
        this.classifier = classifier;
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
                        PomTreeEnvironment.forChangedPom(tree, classifier, path)));
            }
        }

        Map<String, PomChangeReport> reports = new LinkedHashMap<>();
        for (String path : paths) {
            reports.put(path, reportsByPath.get(path));
        }
        return reports;
    }
}
