package org.tiatesting.spock

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.model.SpecInfo
import org.tiatesting.vcs.git.GitReader

class TiaSpockGlobalExtension implements IGlobalExtension{

    private static final Logger log = LoggerFactory.getLogger(TiaSpockGlobalExtension.class);
    private final boolean tiaEnabled
    private final String projectDir;
    private final String dbFilePath;
    private final String dbPersistenceStrategy;
    private final List<String> sourceFilesDirs;
    private final TiaTestingSpockRunListener tiaTestingSpockRunListener
    private Set<String> ignoredTests = []

    TiaSpockGlobalExtension(){
        tiaEnabled = System.getProperty("tiaEnabled")?.toBoolean()
        if (tiaEnabled){
            projectDir = System.getProperty("tiaProjectDir")
            dbFilePath = System.getProperty("tiaDBFilePath")
            dbPersistenceStrategy = System.getProperty("tiaDBPersistenceStrategy")
            sourceFilesDirs = System.getProperty("tiaSourceFilesDirs") ? Arrays.asList(System.getProperty("tiaSourceFilesDirs").split(",")) : null
            this.tiaTestingSpockRunListener = new TiaTestingSpockRunListener(new GitReader(projectDir), dbFilePath, dbPersistenceStrategy)
        } else {
            log.error("TIA is disabled for this test run (use tiaEnabled to enable TIA).")
        }
    }

    void start() {
        if (tiaEnabled) {
            ignoredTests = new TiaSpockInitializer().getTestsToRun(projectDir, dbFilePath, sourceFilesDirs)
        }
    };

    void visitSpec(SpecInfo spec){
        if (tiaEnabled){
            spec.addListener(tiaTestingSpockRunListener)

            if (ignoredTests.contains(tiaTestingSpockRunListener.getSpecName(spec))){
                spec.skip("Test not selected to run based on the changes analyzed by Tia")
            }
        }
    };

    void stop(){
        if (tiaEnabled) {
            tiaTestingSpockRunListener.finishAllTests()
        }
    };
}
