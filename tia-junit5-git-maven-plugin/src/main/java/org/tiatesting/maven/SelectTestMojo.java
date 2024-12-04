package org.tiatesting.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.vcs.git.GitReader;

@Mojo(name = "select-tests", defaultPhase = LifecyclePhase.NONE)
public class SelectTestMojo extends AbstractSelectTestsMojo {
    @Override
    public VCSReader getVCSReader() {
        return new GitReader(getTiaProjectDir());
    }
}
