package org.tiatesting.maven;

import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.util.StringUtil;
import org.tiatesting.core.vcs.VCSReader;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mojo used to display the tests selected by Tia based on the changes it will analyse.
 * Note: this previews the selected tests but doesn't actually run them — selection runs with
 * {@code updateDBMapping=false} so library reconcile and pending-stamp persistence are skipped.
 * Drain analysis still runs (read-only) so the preview matches what the agent mojo would select.
 */
public abstract class AbstractSelectTestsMojo extends AbstractTiaMojo {
    @Override
    public void execute() {
        System.out.println("Displaying the tests selected by Tia:");
        final VCSReader vcsReader = getVCSReader();
        final DataStore dataStore = new H2DataStore(getTiaDBFilePath(), vcsReader.getBranchName());
        List<String> sourceFilesDirs = getTiaSourceFilesDirs() != null ? Arrays.asList(getTiaSourceFilesDirs().split(",")) : null;
        StringUtil.sanitizeInputArray(sourceFilesDirs);
        List<String> testFilesDirs = getTiaTestFilesDirs() != null ? Arrays.asList(getTiaTestFilesDirs().split(",")) : null;
        StringUtil.sanitizeInputArray(testFilesDirs);

        TestSelector testSelector = new TestSelector(dataStore);
        LibraryImpactAnalysisConfig libraryConfig = buildLibraryImpactAnalysisConfig();
        TestSelectorResult result = testSelector.selectTestsToIgnore(vcsReader, sourceFilesDirs,
                testFilesDirs, isCheckLocalChanges(), libraryConfig, false);
        Set<String> testsToRun = result.getTestsToRun();
        System.out.println("Selected tests to run: ");

        if (testsToRun.isEmpty()){
            System.out.println("none");
        } else {
            System.out.println(testsToRun.stream().map(String::valueOf).collect(Collectors.joining("\n\t", "\t", "")));
        }
    }

    /**
     * Check if Tia should analyze local changes.
     * If we're updating the DB, we shouldn't check for local changes as the DB needs to be in sync with
     * committed changes only.
     *
     * @return
     */
    private boolean isCheckLocalChanges(){
        if (isTiaUpdateDBMapping()){
            return false;
        } else{
            return isTiaCheckLocalChanges();
        }
    }
}
