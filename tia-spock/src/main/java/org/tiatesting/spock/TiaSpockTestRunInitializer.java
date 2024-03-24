package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;

import java.util.List;

public class TiaSpockTestRunInitializer {
    private static final Logger log = LoggerFactory.getLogger(TiaSpockTestRunInitializer.class);

    private final VCSReader vcsReader;
    private final DataStore dataStore;

    public TiaSpockTestRunInitializer(final VCSReader vcsReader, final DataStore dataStore){
        this.vcsReader = vcsReader;
        this.dataStore = dataStore;
    }

    TestSelectorResult selectTests(final List<String> sourceFilesDirs, final List<String> testFilesDirs, boolean checkLocalChanges){
        TestSelector testSelector = new TestSelector(dataStore);
        return testSelector.selectTestsToIgnore(vcsReader, sourceFilesDirs, testFilesDirs, checkLocalChanges);
    }
}
