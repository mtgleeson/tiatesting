package org.tiatesting.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.vcs.perforce.P4Reader;

@Mojo(name = "info", defaultPhase = LifecyclePhase.NONE)
public class InfoMojo extends AbstractInfoMojo {
    @Override
    public VCSReader getVCSReader() {
        return new P4Reader(isTiaEnabled(), getTiaVcsServerUri(), getTiaVcsUserName(), getTiaVcsPassword(), getTiaVcsClientName());
    }
}
