package org.tiatesting.vcs.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.vcs.VCSAnalyzerException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

/**
 * TODO shouldnt need this anymore - delete
 * Checkout a version of the source code from the repository at a given commit.
 */
public class GitCheckoutProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitCheckoutProcessor.class);

    protected File checkoutSourceAtVersion(GitContext gitContext, String commit){
        //String tmpDirName = "tia-" + gitContext.getRepository().getDirectory().getParentFile().getName()
        //        + "-" + gitContext.getBranchName() + "-" + commit;
        String tmpDirName = "tia-" + UUID.randomUUID();
        File tmpDirPath;

        try {
            tmpDirPath = Files.createTempDirectory(tmpDirName).toFile();
        } catch (IOException e) {
            throw new VCSAnalyzerException(e);
        }

        log.info(String.format("Checking out source files for version %s to %s", commit, tmpDirPath.getAbsolutePath()));

        try (Git localGit = Git.cloneRepository().setURI(gitContext.getRepository().getDirectory().getAbsolutePath()).setDirectory(tmpDirPath).call()){
            localGit.checkout().setName(commit).call();
        } catch (GitAPIException e) {
            throw new VCSAnalyzerException(e);
        }

        return tmpDirPath;
    }
}
