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
    private final boolean tiaUpdateDB;

    public TiaSpockTestRunInitializer(final VCSReader vcsReader, final DataStore dataStore, final boolean tiaUpdateDB){
        this.vcsReader = vcsReader;
        this.dataStore = dataStore;
        this.tiaUpdateDB = tiaUpdateDB;
    }

    Set<String> getTestsToIgnore(final List<String> sourceFilesDirs, final List<String> testFilesDirs){
        TestSelector testSelector = new TestSelector(dataStore);
        return testSelector.selectTestsToIgnore(vcsReader, sourceFilesDirs, testFilesDirs, tiaUpdateDB);
    }
}
