package org.tiatesting.core.library;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.core.vcs.VCSReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Coverage for {@link PublishScopedVCSReader}: a repeated question goes to the VCS once, a
 * different question is asked afresh, and diff content is loaded once per diff object.
 */
class PublishScopedVCSReaderTest {

    private static final List<String> DIRS = Collections.singletonList("/ws/lib/src/main/java");

    @Test
    void theSameDiffIsReadOnceAndADifferentRangeIsReadAgain() {
        // given a reader over a counting VCS
        CountingVCSReader vcs = new CountingVCSReader();
        PublishScopedVCSReader reader = new PublishScopedVCSReader(vcs);

        // when two schemas ask for the diff from one baseline and a third from another
        Set<SourceFileDiffContext> first = reader.getDiffFiles("c0", DIRS, Collections.<String>emptyList(), false);
        Set<SourceFileDiffContext> second = reader.getDiffFiles("c0", new ArrayList<>(DIRS),
                Collections.<String>emptyList(), false);
        reader.getDiffFiles("c5", DIRS, Collections.<String>emptyList(), false);

        // then the VCS was asked once per range and the same diff objects are shared
        assertEquals(Arrays.asList("c0", "c5"), vcs.diffBases);
        assertSame(first, second);
    }

    @Test
    void contentIsLoadedOncePerDiffObject() {
        // given a cached diff whose content one schema has already loaded
        CountingVCSReader vcs = new CountingVCSReader();
        PublishScopedVCSReader reader = new PublishScopedVCSReader(vcs);
        Set<SourceFileDiffContext> diffs = reader.getDiffFiles("c0", DIRS, Collections.<String>emptyList(), false);
        reader.loadContentForDiffs(diffs, "c0", false);

        // when a second schema loads the same diffs plus one new diff object
        SourceFileDiffContext other = new SourceFileDiffContext("B.java", "B.java", ChangeType.MODIFY);
        List<SourceFileDiffContext> again = new ArrayList<>(diffs);
        again.add(other);
        reader.loadContentForDiffs(again, "c0", false);

        // then only the new object reached the VCS, and the shared one kept its loaded content
        assertEquals(2, vcs.contentLoads.size());
        assertEquals(Collections.singletonList(other), vcs.contentLoads.get(1));
        assertEquals("loaded", diffs.iterator().next().getSourceContentNew());
    }

    @Test
    void changedPathsFileContentsAndHeadAreReadOnce() {
        // given a reader over a counting VCS
        CountingVCSReader vcs = new CountingVCSReader();
        PublishScopedVCSReader reader = new PublishScopedVCSReader(vcs);
        Set<String> paths = new HashSet<>(Collections.singletonList("lib/pom.xml"));

        // when each question is asked twice
        for (int i = 0; i < 2; i++) {
            reader.getChangedFilePaths("c0", false);
            reader.loadFileContents(paths, "c0", false);
            reader.getHeadCommit();
        }

        // then the VCS answered each once
        assertEquals(1, vcs.changedPathReads);
        assertEquals(1, vcs.fileContentReads);
        assertEquals(1, vcs.headReads);
    }

    @Test
    void theSourceLineIsResolvedOnceEvenWhenUnknown() {
        // given a VCS that cannot identify the line
        CountingVCSReader vcs = new CountingVCSReader();
        vcs.line = null;
        PublishScopedVCSReader reader = new PublishScopedVCSReader(vcs);

        // when three schemas ask for the publishing module's line
        for (int i = 0; i < 3; i++) {
            assertNull(reader.getSourceLine(DIRS, "main"));
        }

        // then the VCS was asked once
        assertEquals(1, vcs.sourceLineReads);
    }

    @Test
    void closingLeavesTheOwnersReaderOpen() {
        // given a reader over a VCS reader the publishing build owns
        CountingVCSReader vcs = new CountingVCSReader();

        // when the publish-scoped reader is closed
        new PublishScopedVCSReader(vcs).close();

        // then the owner's reader is still open
        assertFalse(vcs.closed);
    }

    /** A VCS reader that counts each question and returns one diff from any range. */
    private static final class CountingVCSReader implements VCSReader {
        private final List<String> diffBases = new ArrayList<>();
        private final List<List<SourceFileDiffContext>> contentLoads = new ArrayList<>();
        private int changedPathReads;
        private int fileContentReads;
        private int headReads;
        private int sourceLineReads;
        private String line = "main";
        private boolean closed;

        @Override public String getBranchName() { return "main"; }

        @Override
        public String getHeadCommit() {
            headReads++;
            return "c9";
        }

        @Override public boolean isHeadBehind(String commit) { return false; }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            diffBases.add(baseChangeNum);
            return new HashSet<>(Collections.singletonList(
                    new SourceFileDiffContext("A.java", "A.java", ChangeType.MODIFY)));
        }

        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                        boolean checkLocalChanges) {
            contentLoads.add(new ArrayList<>(diffs));
            for (SourceFileDiffContext diff : diffs) {
                diff.setSourceContentNew("loaded");
            }
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            changedPathReads++;
            return new HashSet<>();
        }

        @Override
        public Map<String, FileContentPair> loadFileContents(Set<String> paths, String baseChangeNum,
                                                             boolean checkLocalChanges) {
            fileContentReads++;
            return Collections.emptyMap();
        }

        @Override
        public MappingDrift findMappingDrift(String baseChangeNum, Collection<SourceFileDiffContext> localDiffs) {
            return null;
        }

        @Override
        public String getSourceLine(List<String> sourceDirs, String workspaceBranch) {
            sourceLineReads++;
            return line;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
