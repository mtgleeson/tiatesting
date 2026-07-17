package org.tiatesting.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.vcs.perforce.P4Reader;

@Mojo(name = "publish-lib-stamp", defaultPhase = LifecyclePhase.INSTALL)
public class PublishLibStampMojo extends AbstractPublishLibStampMojo {
    @Override
    public VCSReader getVCSReader() {
        return new P4Reader(isTiaEnabled(), getTiaVcsServerUri(), getTiaVcsUserName(), getTiaVcsPassword(), getTiaVcsClientName());
    }
}
