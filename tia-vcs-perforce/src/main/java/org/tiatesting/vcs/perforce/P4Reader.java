package org.tiatesting.vcs.perforce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.vcs.perforce.connection.P4Connection;

import java.util.HashSet;
import java.util.Set;

public class P4Reader implements VCSReader {

    private static final Logger log = LoggerFactory.getLogger(P4Reader.class);

    //private final GitDiffAnalyzer gitDiffAnalyzer;
    private final P4Context p4Context;

    public P4Reader(final String serverUri, final String userName, final String password, final String clientName) {
        P4Connection p4Connection = initializeConnection(serverUri, userName, password, clientName);
        p4Context = new P4Context(p4Connection, readBranchName(p4Connection), readHeadCL(p4Connection));
        //gitDiffAnalyzer = new GitDiffAnalyzer();
    }

    private P4Connection initializeConnection(final String serverUri, final String userName, final String password, final String clientName){
        P4Connection p4Connection = P4Connection.getInstance();

        // TODO If serverUri, userName or clientName are null, get settings (except password) from p4 set command. This this as the default.

        p4Connection.setP4Settings(serverUri, userName, password, clientName);
        p4Connection.start();
        log.info("Successfully initialized P4 connection to {} with user name {} and client {}", serverUri, userName, clientName);
        return p4Connection;
    }

    @Override
    public Set<SourceFileDiffContext> buildDiffFilesContext(final String commitFrom, boolean checkLocalChanges) {
        // TODO return gitDiffAnalyzer.buildDiffFilesContext(gitContext, commitFrom, checkLocalChanges);
        return new HashSet<>();
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
       return "";
    }
    /*
        private Repository getFileRepository(final String gitPath){
            try {
                return new FileRepositoryBuilder()
                        .setGitDir(new File(gitPath)) //"my_repo/.git"
                        .build();
            } catch (IOException e) {
                throw new VCSAnalyzerException(e);
            }
        }
    */
    private String readHeadCL(final P4Connection p4Connection) {
        return "";
    }

}
