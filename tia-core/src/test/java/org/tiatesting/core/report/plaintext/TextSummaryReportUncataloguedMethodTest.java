package org.tiatesting.core.report.plaintext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the text summary report tolerates a suite whose edges name a method that the seal has
 * not yet added to the method catalogue (an unsealed suite's coverage).
 */
class TextSummaryReportUncataloguedMethodTest {

    @Test
    void mappingSkipsMethodsMissingFromTheCatalogue(@TempDir Path tempDir) throws IOException {
        // given a suite covering one catalogued method (1) and one uncatalogued method (2)
        TiaData tiaData = new TiaData();
        tiaData.setTestStats(new TestStats());
        tiaData.setTestSuitesFailed(Collections.emptySet());
        tiaData.setTestRunHistory(Collections.emptyList());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(1, new MethodImpactTracker("com/example/Car.drive.()V", 10, 20));
        tiaData.setMethodsTracked(methods);

        TestSuiteTracker suite = new TestSuiteTracker("com.example.MySpec");
        suite.setClassesImpacted(new ArrayList<>(Collections.singletonList(
                new ClassImpactTracker("com/example/Car.java", new HashSet<>(Arrays.asList(1, 2))))));
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(suite.getName(), suite);
        tiaData.setTestSuitesTracked(suites);

        // when
        String fileName = new TextSummaryReport("uncatalogued", tempDir.toFile()).generateSummaryReport(tiaData);

        // then the catalogued method is still listed under the suite
        String report = new String(Files.readAllBytes(new File(fileName).toPath()), StandardCharsets.UTF_8);
        assertTrue(report.contains("com.example.MySpec" + System.lineSeparator() + "\tcom/example/Car.drive.()V 10 -> 20"),
                "the suite mapping should list its catalogued method. Report was:\n" + report);
    }
}
