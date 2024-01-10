package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;
import org.tiatesting.persistence.StoredMapping;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;

public abstract class AbstractInfoMojo extends AbstractTiaMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final VCSReader vcsReader = getVCSReader();
        final DataStore dataStore = new MapDataStore(getTiaDBFilePath(), vcsReader.getBranchName());
        StoredMapping storedMapping = dataStore.getStoredMapping();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss zzz", Locale.ENGLISH).withZone(ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("Tia Info:" + System.lineSeparator());
        sb.append("DB last updated: " + dtf.format(storedMapping.getLastUpdated()) + System.lineSeparator());
        sb.append("Test mapping valid for commit number: " + storedMapping.getCommitValue() + System.lineSeparator());
        sb.append("Number of tests classes with mappings: " + storedMapping.getTestSuitesTracked().keySet().size()
                + System.lineSeparator());
        sb.append("Number of source methods tracked for tests: " + storedMapping.getMethodsTracked().keySet().size()
                + System.lineSeparator());
        sb.append("Pending failed tests: " + System.lineSeparator() + storedMapping.getTestSuitesFailed().stream().map(test ->
                "\t" + test).collect(Collectors.joining(System.lineSeparator())));
        getLog().info(sb.toString());
    }

    public abstract VCSReader getVCSReader();
}
