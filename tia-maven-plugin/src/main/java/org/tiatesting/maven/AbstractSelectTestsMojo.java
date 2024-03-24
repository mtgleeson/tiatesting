package org.tiatesting.maven;

import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.diffanalyze.selector.TestSelector;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mojo used to display the tests selected by Tia based on the changes it will analyse.
 * Note: this previews the selected tests but doesn't actually run them.
 */
public abstract class AbstractSelectTestsMojo extends AbstractTiaMojo {
    @Override
    public void execute() {
        System.out.println("Displaying the tests selected by Tia:");
        final VCSReader vcsReader = getVCSReader();
        final DataStore dataStore = new H2DataStore(getTiaDBFilePath(), vcsReader.getBranchName());
        List<String> sourceFilesDirs = getTiaSourceFilesDirs() != null ? Arrays.asList(getTiaSourceFilesDirs().split(",")) : null;
        List<String> testFilesDirs = getTiaTestFilesDirs() != null ? Arrays.asList(getTiaTestFilesDirs().split(",")) : null;

        TestSelector testSelector = new TestSelector(dataStore);
        Set<String> testsToRun = testSelector.selectTestsToRun(getVCSReader(), sourceFilesDirs, testFilesDirs, isTiaCheckLocalChanges());

        System.out.println("Selected tests to run: " +
                testsToRun.stream().map(String::valueOf).collect(Collectors.joining("\n\t", "\n\t", "")));
    }
}
