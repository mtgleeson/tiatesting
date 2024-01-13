package org.tiatesting.vcs.perforce;

import com.perforce.p4java.core.IChangelistSummary;
import com.perforce.p4java.core.file.FileSpecBuilder;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.option.server.GetChangelistsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class P4Reader implements VCSReader {

    private static final Logger log = LoggerFactory.getLogger(P4Reader.class);

    private final P4DiffAnalyzer p4DiffAnalyzer;
    private final P4Context p4Context;

    public P4Reader(final boolean enabled, final String serverUri, final String userName,
                    final String password, final String clientName) {
        if (enabled){
            P4Connection p4Connection = initializeConnection(serverUri, userName, password, clientName);
            p4Context = new P4Context(p4Connection, readBranchName(p4Connection), readHeadCL(p4Connection));
            p4DiffAnalyzer = new P4DiffAnalyzer();
        }else{
            p4Context = null;
            p4DiffAnalyzer = null;
        }
    }

    private P4Connection initializeConnection(final String serverUri, final String userName, final String password, final String clientName){
        P4Connection p4Connection = P4Connection.getInstance();
        p4Connection.setP4Settings(serverUri, userName, password, clientName);
        p4Connection.start();
        log.info("Successfully initialized P4 connection to {} with user name {} and client {}", serverUri, userName, clientName);
        return p4Connection;
    }

    @Override
    public Set<SourceFileDiffContext> buildDiffFilesContext(final String baseChangeNum, final List<String> sourceFilesDirs,
                                                            final List<String> testFilesDirs, final boolean checkLocalChanges) {
        List<String> sourceAndTestFilesDir = new ArrayList<>(sourceFilesDirs);
        sourceAndTestFilesDir.addAll(testFilesDirs);
        return p4DiffAnalyzer.buildDiffFilesContext(p4Context, baseChangeNum, sourceAndTestFilesDir, checkLocalChanges);
    }

    @Override
    public String getHeadCommit(){
        return p4Context.getHeadCL();
    }

    @Override
    public String getBranchName(){
        return p4Context.getBranchName();
    }

    private String readBranchName(final P4Connection p4Connection) {
        String streamName = p4Connection.getClient().getStream();
        streamName = streamName.replace("//", "");
        streamName = streamName.replace("/", "-");
        return streamName;
    }

    private String readHeadCL(final P4Connection p4Connection) {
        GetChangelistsOptions options = new GetChangelistsOptions();
        options.setMaxMostRecent(1);
        String workspaceHeadCL = null;
        String workspacePath = p4Connection.getClient().getStream() + "/...#have";

        try {
            List<IChangelistSummary> changeLists = p4Connection.getServer().getChangelists(
                    FileSpecBuilder.makeFileSpecList(workspacePath), options);
            if (changeLists.isEmpty()){
                throw new VCSAnalyzerException("Couldn't find the head changelist for the workspace");
            }
            workspaceHeadCL = String.valueOf(changeLists.get(0).getId());
            log.debug("Head changelist id for the workspace is : {}", workspaceHeadCL);
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }
        return workspaceHeadCL;
    }

}
