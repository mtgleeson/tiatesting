package org.tiatesting.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.vcs.git.GitReader;

@Mojo(name = "libraries", defaultPhase = LifecyclePhase.NONE)
public class LibrariesMojo extends AbstractLibrariesMojo {
    @Override
    public VCSReader getVCSReader() {
        return new GitReader(getTiaProjectDir());
    }
}
