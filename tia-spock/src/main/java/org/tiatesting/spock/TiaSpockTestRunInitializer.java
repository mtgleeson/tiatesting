package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.vcs.VCSReader;

import java.util.List;

public class TiaSpockTestRunInitializer {
    private static final Logger log = LoggerFactory.getLogger(TiaSpockTestRunInitializer.class);

    private final VCSReader vcsReader;
    private final DataStore dataStore;

    public TiaSpockTestRunInitializer(final VCSReader vcsReader, final DataStore dataStore){
        this.vcsReader = vcsReader;
        this.dataStore = dataStore;
    }

    /**
     * Run Tia's test selection for a Spock build, returning the suites to ignore.
     *
     * @param sourceFilesDirs the configured source-file directories.
     * @param testFilesDirs the configured test-file directories.
     * @param checkLocalChanges whether to inspect the local workspace instead of the commit range.
     * @param updateDBMapping whether this run owns mapping-DB updates.
     * @param libraryConfig the library impact analysis config; may be {@code null}.
     * @param staticMappingConfig the static test selection config; may be {@code null}.
     * @return the {@link TestSelectorResult} produced by {@link TestSelector#selectTestsToIgnore}.
     */
    TestSelectorResult selectTests(final List<String> sourceFilesDirs, final List<String> testFilesDirs,
                                   boolean checkLocalChanges, boolean updateDBMapping,
                                   LibraryImpactAnalysisConfig libraryConfig,
                                   StaticTestSelectionConfig staticMappingConfig){
        TestSelector testSelector = new TestSelector(dataStore);
        return testSelector.selectTestsToIgnore(vcsReader, sourceFilesDirs, testFilesDirs, checkLocalChanges,
                libraryConfig, staticMappingConfig, updateDBMapping);
    }
}
