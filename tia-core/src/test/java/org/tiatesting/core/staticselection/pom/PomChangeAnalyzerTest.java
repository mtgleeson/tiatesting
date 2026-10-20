package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.VCSReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PomChangeAnalyzer}: batched fetching, per-path caching, the diff
 * parameters passed to the VCS, and tracked libraries reaching the classifier.
 */
class PomChangeAnalyzerTest {

    @Test
    void analyzesEachPomOnceAndFetchesOnlyUnseenPaths() {
        // given
        Map<String, FileContentPair> contents = new HashMap<>();
        contents.put("pom.xml", new FileContentPair(pom("1.0"), pom("2.0")));
        contents.put("a/pom.xml", new FileContentPair(pom("1.0"), pom("1.0")));
        RecordingVCSReader reader = new RecordingVCSReader(contents);
        PomChangeAnalyzer analyzer = new PomChangeAnalyzer(reader, "base123", true, Collections.<String>emptySet(),
                new PomChangeClassifier(Collections.<String>emptyList()), null);

        // when
        Map<String, PomChangeReport> first = analyzer.analyze(new HashSet<>(Collections.singletonList("pom.xml")));
        Map<String, PomChangeReport> second = analyzer.analyze(new HashSet<>(Arrays.asList("pom.xml", "a/pom.xml")));

        // then - the second call fetches only the new path, with the diff parameters passed through
        assertEquals(Arrays.asList(Collections.singleton("pom.xml"), Collections.singleton("a/pom.xml")), reader.requests);
        assertEquals("base123", reader.lastBase);
        assertTrue(reader.lastCheckLocalChanges);
        assertEquals(EnumSet.of(PomChangeCategory.DEPENDENCY_VERSIONS), first.get("pom.xml").getCategories());
        assertTrue(second.get("a/pom.xml").getChanges().isEmpty());
    }

    @Test
    void trackedLibrariesReachTheClassifier() {
        // given - the bumped dependency is tracked
        RecordingVCSReader reader = new RecordingVCSReader(Collections.singletonMap("pom.xml",
                new FileContentPair(pom("1.0"), pom("2.0"))));
        PomChangeAnalyzer analyzer = new PomChangeAnalyzer(reader, "base", false, Collections.<String>emptySet(),
                new PomChangeClassifier(Collections.singletonList("org.lib:lib")), null);

        // when
        Map<String, PomChangeReport> reports = analyzer.analyze(Collections.singleton("pom.xml"));

        // then
        assertTrue(reports.get("pom.xml").getCategories().isEmpty());
    }

    @Test
    void pathWithNoContentReturnedIsTreatedAsUnchanged() {
        // given - the reader returns nothing for the path
        RecordingVCSReader reader = new RecordingVCSReader(Collections.<String, FileContentPair>emptyMap());
        PomChangeAnalyzer analyzer = new PomChangeAnalyzer(reader, "base", false, Collections.<String>emptySet(),
                new PomChangeClassifier(Collections.<String>emptyList()), null);

        // when
        Map<String, PomChangeReport> reports = analyzer.analyze(Collections.singleton("pom.xml"));

        // then
        assertTrue(reports.get("pom.xml").getChanges().isEmpty());
    }

    /**
     * @param libVersion the version of the {@code org.lib:lib} dependency.
     * @return a jar POM text.
     */
    private static String pom(String libVersion) {
        return "<project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1</version>"
                + "<dependencies><dependency><groupId>org.lib</groupId><artifactId>lib</artifactId>"
                + "<version>" + libVersion + "</version></dependency></dependencies></project>";
    }

    /**
     * VCS reader serving fixed content (only for paths it has; the rest are left out of the
     * result) and recording each content request.
     */
    private static final class RecordingVCSReader implements VCSReader {
        private final Map<String, FileContentPair> contents;
        private final List<Set<String>> requests = new ArrayList<>();
        private String lastBase;
        private boolean lastCheckLocalChanges;

        /**
         * @param contents the content to serve by path.
         */
        RecordingVCSReader(Map<String, FileContentPair> contents) {
            this.contents = contents;
        }

        /**
         * @return a fixed branch name.
         */
        @Override
        public String getBranchName() {
            return "main";
        }

        /**
         * @return a fixed head commit.
         */
        @Override
        public String getHeadCommit() {
            return "head";
        }

        /**
         * @param baseChangeNum unused.
         * @param sourceFilesDirs unused.
         * @param testFilesDirs unused.
         * @param checkLocalChanges unused.
         * @return never returns.
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * @param diffs unused.
         * @param baseChangeNum unused.
         * @param checkLocalChanges unused.
         */
        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                        boolean checkLocalChanges) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * @param baseChangeNum unused.
         * @param checkLocalChanges unused.
         * @return never returns.
         */
        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Record the request and serve the content the reader has.
         *
         * @param paths the requested paths.
         * @param baseChangeNum the diff baseline, recorded.
         * @param checkLocalChanges the diff mode, recorded.
         * @return the content of the requested paths the reader has.
         */
        @Override
        public Map<String, FileContentPair> loadFileContents(Set<String> paths, String baseChangeNum,
                                                             boolean checkLocalChanges) {
            requests.add(new HashSet<>(paths));
            lastBase = baseChangeNum;
            lastCheckLocalChanges = checkLocalChanges;
            Map<String, FileContentPair> loaded = new HashMap<>();
            for (String path : paths) {
                if (contents.containsKey(path)) {
                    loaded.put(path, contents.get(path));
                }
            }
            return loaded;
        }

        /**
         * Nothing to release.
         */
        @Override
        public void close() {
        }
    }
}
