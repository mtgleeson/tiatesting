package org.tiatesting.vcs.perforce;

import com.perforce.p4java.core.IChangelistSummary;
import com.perforce.p4java.core.file.FileSpecBuilder;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.option.server.GetChangelistsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        log.info("Successfully initialized P4 connection to {} with user name {} and client {}", p4Connection.getServerUri(),
                p4Connection.getUserName(), p4Connection.getClientName());
        return p4Connection;
    }

    @Override
    public Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum, final List<String> sourceFilesDirs,
                                                   final List<String> testFilesDirs, final boolean checkLocalChanges) {
        List<String> sourceAndTestFilesDir = new ArrayList<>(sourceFilesDirs);
        sourceAndTestFilesDir.addAll(testFilesDirs);
        return p4DiffAnalyzer.getDiffFiles(p4Context, baseChangeNum, sourceAndTestFilesDir, checkLocalChanges);
    }

    @Override
    public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs, final String baseChangeNum,
                                    final boolean checkLocalChanges) {
        p4DiffAnalyzer.loadContentForDiffContexts(p4Context, diffs, baseChangeNum, checkLocalChanges);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Walks the P4 file specs returned by either the submit-range query
     * ({@code getDepotFiles(...@from,to)} with {@code allRevs=true}) or the local-changes query
     * ({@code getExtendedFiles(//...)} with opened-files=true) and returns each path resolved
     * through the client view ({@code p4 where}) and relativized against the client root, so
     * the result is workspace-root-relative and forward-slash.
     */
    @Override
    public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
        return p4DiffAnalyzer.getChangedFilePaths(p4Context, baseChangeNum, checkLocalChanges);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, FileContentPair> loadFileContents(final Set<String> paths, final String baseChangeNum,
                                                         final boolean checkLocalChanges) {
        return p4DiffAnalyzer.loadFileContents(p4Context, paths, baseChangeNum, checkLocalChanges);
    }

    @Override
    public void close() {
        log.debug("Closing the P4 connection resource");
        p4Context.getP4Connection().stop();
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

    /**
     * Find the most recent changelist the workspace is synced to ({@code p4 changes -m1 //<client>/...#have}).
     *
     * <p>The query uses client syntax rather than stream syntax so the server expands the client
     * view to the real depot paths in the have list. A stream-scoped query matches nothing for
     * virtual streams (which own no files and map the parent stream's paths), for streams whose
     * files come in via {@code import} paths, and for classic clients that have no stream.
     *
     * @param p4Connection the Perforce connection whose current client is queried.
     * @return the id of the workspace head changelist.
     * @throws VCSAnalyzerException if no changelist is found or the Perforce query fails.
     */
    static String readHeadCL(final P4Connection p4Connection) {
        GetChangelistsOptions options = new GetChangelistsOptions();
        options.setMaxMostRecent(1);
        String workspaceHeadCL;
        String clientName = p4Connection.getClient().getName();
        String workspacePath = "//" + clientName + "/...#have";

        try {
            List<IChangelistSummary> changeLists = p4Connection.getServer().getChangelists(
                    FileSpecBuilder.makeFileSpecList(workspacePath), options);
            if (changeLists.isEmpty()){
                throw new VCSAnalyzerException("Couldn't find the head changelist for the workspace of client "
                        + clientName + " using " + workspacePath);
            }
            workspaceHeadCL = String.valueOf(changeLists.get(0).getId());
            log.debug("Head changelist id for the workspace of client {} is : {}", clientName, workspaceHeadCL);
        } catch (P4JavaException e) {
            throw new VCSAnalyzerException(e);
        }
        return workspaceHeadCL;
    }

}
