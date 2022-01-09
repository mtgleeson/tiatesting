package org.tiatesting.spock

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.model.SpecInfo
import org.tiatesting.vcs.git.GitReader

class TiaSpockGlobalExtension implements IGlobalExtension{

    private static final Logger log = LoggerFactory.getLogger(TiaSpockGlobalExtension.class);
    private TiaTestingSpockRunListener tiaTestingSpockRunListener

    TiaSpockGlobalExtension(){
        def tiaEnabled = System.getProperty("tiaEnabled")
        if (tiaEnabled){
            this.tiaTestingSpockRunListener = new TiaTestingSpockRunListener(new GitReader(System.getProperty("tiaProjectDir")))
        } else {
            log.error("TIA is disabled for this test run (use tiaEnabled to enable TIA).")
        }
    }

    void start() {
    };

    void visitSpec(SpecInfo spec){
        if (tiaTestingSpockRunListener){
            spec.addListener(tiaTestingSpockRunListener)
        }
    };

    void stop(){
        if (tiaTestingSpockRunListener) {
            tiaTestingSpockRunListener.finishAllTests()
        }
    };
}
