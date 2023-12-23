package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.diffanalyze.selector.TestSelector;
import org.tiatesting.persistence.DataStore;

import java.util.List;
import java.util.Set;

public class TiaSpockTestRunInitializer {
    private static final Logger log = LoggerFactory.getLogger(TiaSpockTestRunInitializer.class);

    private final VCSReader vcsReader;
    private final DataStore dataStore;

    public TiaSpockTestRunInitializer(final VCSReader vcsReader, final DataStore dataStore){
        this.vcsReader = vcsReader;
        this.dataStore = dataStore;
    }

    Set<String> getTestsToIgnore(final List<String> sourceFilesDirs){
        TestSelector testSelector = new TestSelector();
        return testSelector.selectTestsToIgnore(dataStore.getStoredMapping(), vcsReader, sourceFilesDirs);
    }
}
