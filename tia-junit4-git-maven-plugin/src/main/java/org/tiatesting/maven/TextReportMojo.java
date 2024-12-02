package org.tiatesting.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.vcs.git.GitReader;

@Mojo(name = "text-report", defaultPhase = LifecyclePhase.NONE)
public class TextReportMojo extends AbstractTextReportMojo{
    @Override
    public VCSReader getVCSReader() {
        return new GitReader(getTiaProjectDir());
    }
}
