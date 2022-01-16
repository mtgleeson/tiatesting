package org.tiatesting.spock.git;

import org.tiatesting.spock.TiaSpockGlobalExtension;
import org.tiatesting.vcs.git.GitReader;

public class TiaSpockGitGlobalExtension extends TiaSpockGlobalExtension {

    public TiaSpockGitGlobalExtension(){
        super(Boolean.parseBoolean(System.getProperty("tiaEnabled")) == true ? new GitReader(System.getProperty("tiaProjectDir")) : null);
    }
}
