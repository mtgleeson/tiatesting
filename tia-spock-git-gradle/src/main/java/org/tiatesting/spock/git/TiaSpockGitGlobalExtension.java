package org.tiatesting.spock.git;

import org.tiatesting.spock.TiaSpockGlobalExtension;
import org.tiatesting.vcs.git.GitReader;

/**
 * The Spock global extension as registered for a Git project. It adds nothing to {@link
 * TiaSpockGlobalExtension} but the means of opening a repository, and hands that over as a supplier
 * rather than a constructed reader: the branch and the commit arrive as system properties the Gradle
 * daemon resolved, so the only caller left is an ordinary build's own test selection. A disabled
 * build and a distributed runner both invoke the supplier never, and so open nothing.
 */
public class TiaSpockGitGlobalExtension extends TiaSpockGlobalExtension {

    /**
     * Register the extension with a supplier that opens this workspace's Git repository on demand.
     */
    public TiaSpockGitGlobalExtension(){
        super(() -> new GitReader(System.getProperty("tiaProjectDir")));
    }
}
