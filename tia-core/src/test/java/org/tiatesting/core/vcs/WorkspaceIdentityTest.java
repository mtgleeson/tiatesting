package org.tiatesting.core.vcs;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.SourceFileDiffContext;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link WorkspaceIdentity}'s resolution rules, and above all the one behaviour the whole
 * class exists for: a value that was configured must never cause a VCS reader to be constructed.
 * That is asserted by counting supplier invocations rather than by inspecting the returned values,
 * because a test that only checked the values would pass just as well against an implementation
 * that opened a repository and then ignored what it read - which is exactly the failure this class
 * is meant to rule out on a runner with no VCS access.
 *
 * <p>The failure-message tests assert on message content, not just the exception type: naming the
 * property to set is the whole point of wrapping the underlying VCS failure, since the raw one
 * ("Could not find .git directory") tells a user nothing about what to configure.
 */
class WorkspaceIdentityTest {

    /**
     * Verifies that when both values are configured, neither getter constructs a VCS reader and
     * both return the configured values. This is the distributed-runner case: a VM with no
     * repository and no reachable Perforce server must be able to resolve its identity.
     */
    @Test
    void getters_bothValuesConfigured_neverConstructsAVcsReader() {
        // given
        CountingSupplier supplier = new CountingSupplier(new FakeVCSReader("vcs-branch", "vcs-commit"));

        // when
        String branch;
        String commitValue;
        try (WorkspaceIdentity identity = WorkspaceIdentity.resolving("main", "abc123", supplier)) {
            branch = identity.getBranch();
            commitValue = identity.getCommitValue();
        }

        // then
        assertEquals("main", branch);
        assertEquals("abc123", commitValue);
        assertEquals(0, supplier.invocations, "no VCS reader should have been constructed");
    }

    /**
     * Verifies that a caller needing only the branch - the completion and status stages both do -
     * constructs no VCS reader when the branch alone is configured, even though the commit is not.
     * Resolution has to be lazy per value for those stages to work without VCS access.
     */
    @Test
    void getBranch_onlyBranchConfigured_neverConstructsAVcsReader() {
        // given
        CountingSupplier supplier = new CountingSupplier(new FakeVCSReader("vcs-branch", "vcs-commit"));

        // when
        String branch;
        try (WorkspaceIdentity identity = WorkspaceIdentity.resolving("main", null, supplier)) {
            branch = identity.getBranch();
        }

        // then
        assertEquals("main", branch);
        assertEquals(0, supplier.invocations, "no VCS reader should have been constructed");
    }

    /**
     * Verifies that an unconfigured value falls back to the VCS, which is what keeps every existing
     * single-host build working unchanged with no new configuration.
     */
    @Test
    void getters_nothingConfigured_readsBothValuesFromTheVcs() {
        // given
        CountingSupplier supplier = new CountingSupplier(new FakeVCSReader("vcs-branch", "vcs-commit"));

        // when
        String branch;
        String commitValue;
        try (WorkspaceIdentity identity = WorkspaceIdentity.resolving(null, null, supplier)) {
            branch = identity.getBranch();
            commitValue = identity.getCommitValue();
        }

        // then
        assertEquals("vcs-branch", branch);
        assertEquals("vcs-commit", commitValue);
    }

    /**
     * Verifies that a configured value still wins once a reader has been opened for the other one,
     * rather than the reader's value quietly replacing it. A runner told which branch it is on must
     * use that branch even when its workspace happens to have a repository reporting another.
     */
    @Test
    void getBranch_branchConfiguredAndCommitResolvedFromVcs_keepsTheConfiguredBranch() {
        // given
        CountingSupplier supplier = new CountingSupplier(new FakeVCSReader("vcs-branch", "vcs-commit"));

        // when
        String branch;
        String commitValue;
        try (WorkspaceIdentity identity = WorkspaceIdentity.resolving("main", null, supplier)) {
            commitValue = identity.getCommitValue();
            branch = identity.getBranch();
        }

        // then
        assertEquals("main", branch);
        assertEquals("vcs-commit", commitValue);
        assertEquals(1, supplier.invocations);
    }

    /**
     * Verifies that repeated reads across both values open the VCS reader exactly once. Opening a
     * JGit repository or a Perforce server connection per getter would turn a cheap resolution into
     * a per-call cost on a path every build runs.
     */
    @Test
    void getters_calledRepeatedly_constructsTheVcsReaderOnce() {
        // given
        CountingSupplier supplier = new CountingSupplier(new FakeVCSReader("vcs-branch", "vcs-commit"));

        // when
        try (WorkspaceIdentity identity = WorkspaceIdentity.resolving(null, null, supplier)) {
            identity.getBranch();
            identity.getCommitValue();
            identity.getBranch();
            identity.getCommitValue();
        }

        // then
        assertEquals(1, supplier.invocations);
    }

    /**
     * Verifies that a whitespace-only configured value is treated as absent rather than used. A
     * property left as an unresolved placeholder arrives blank, and a blank branch would resolve to
     * a schema name derived from nothing.
     */
    @Test
    void getters_blankConfiguredValues_treatedAsAbsent() {
        // given
        CountingSupplier supplier = new CountingSupplier(new FakeVCSReader("vcs-branch", "vcs-commit"));

        // when
        String branch;
        String commitValue;
        try (WorkspaceIdentity identity = WorkspaceIdentity.resolving("   ", "", supplier)) {
            branch = identity.getBranch();
            commitValue = identity.getCommitValue();
        }

        // then
        assertEquals("vcs-branch", branch);
        assertEquals("vcs-commit", commitValue);
    }

    /**
     * Verifies that configured values are trimmed, since a value passed through a CI variable or a
     * properties file commonly arrives with trailing whitespace and would otherwise produce a
     * schema name or a commit comparison that differs invisibly.
     */
    @Test
    void getters_configuredValuesWithWhitespace_areTrimmed() {
        // given
        CountingSupplier supplier = new CountingSupplier(new FakeVCSReader("vcs-branch", "vcs-commit"));

        // when
        String branch;
        String commitValue;
        try (WorkspaceIdentity identity = WorkspaceIdentity.resolving(" main ", " abc123\n", supplier)) {
            branch = identity.getBranch();
            commitValue = identity.getCommitValue();
        }

        // then
        assertEquals("main", branch);
        assertEquals("abc123", commitValue);
    }

    /**
     * Verifies that the reader opened during resolution is closed by {@link
     * WorkspaceIdentity#close()}, so the JGit repository handle the Gradle call sites currently
     * leak is released.
     */
    @Test
    void close_afterResolvingFromTheVcs_closesTheReader() {
        // given
        FakeVCSReader reader = new FakeVCSReader("vcs-branch", "vcs-commit");
        CountingSupplier supplier = new CountingSupplier(reader);

        // when
        try (WorkspaceIdentity identity = WorkspaceIdentity.resolving(null, null, supplier)) {
            identity.getBranch();
        }

        // then
        assertEquals(1, reader.closeCount);
    }

    /**
     * Verifies that closing an identity that never needed the VCS neither constructs a reader nor
     * fails. Every fully-configured call site closes in a try-with-resources block, so this is the
     * common path rather than an edge case.
     */
    @Test
    void close_withoutResolvingFromTheVcs_constructsNoReaderAndDoesNotThrow() {
        // given
        CountingSupplier supplier = new CountingSupplier(new FakeVCSReader("vcs-branch", "vcs-commit"));

        // when
        WorkspaceIdentity identity = WorkspaceIdentity.resolving("main", "abc123", supplier);
        identity.getBranch();
        identity.close();

        // then
        assertEquals(0, supplier.invocations);
    }

    /**
     * Verifies that closing twice closes the reader once. A caller that closes explicitly inside a
     * try-with-resources block would otherwise close a JGit repository twice.
     */
    @Test
    void close_calledTwice_closesTheReaderOnce() {
        // given
        FakeVCSReader reader = new FakeVCSReader("vcs-branch", "vcs-commit");
        WorkspaceIdentity identity = WorkspaceIdentity.resolving(null, null, new CountingSupplier(reader));
        identity.getBranch();

        // when
        identity.close();
        identity.close();

        // then
        assertEquals(1, reader.closeCount);
    }

    /**
     * Verifies that a failure constructing the VCS reader - what a runner with no .git directory
     * gets - is reported with a message naming tiaBranch, the property that makes the VCS
     * unnecessary, and that the original failure is kept as the cause.
     */
    @Test
    void getBranch_vcsReaderConstructionFails_throwsNamingTiaBranch() {
        // given
        RuntimeException cause = new VCSAnalyzerException("Could not find .git directory in /build");
        Supplier<VCSReader> supplier = () -> { throw cause; };
        WorkspaceIdentity identity = WorkspaceIdentity.resolving(null, null, supplier);

        // when
        VCSAnalyzerException ex = assertThrows(VCSAnalyzerException.class, identity::getBranch);

        // then
        assertTrue(ex.getMessage().contains("tiaBranch"),
                "message should name tiaBranch, was: " + ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    /**
     * Verifies that the same failure asked for through the commit getter names tiaCommitValue
     * instead, so the message names the value the caller actually needed rather than a fixed one.
     */
    @Test
    void getCommitValue_vcsReaderConstructionFails_throwsNamingTiaCommitValue() {
        // given
        Supplier<VCSReader> supplier = () -> { throw new VCSAnalyzerException("no repository"); };
        WorkspaceIdentity identity = WorkspaceIdentity.resolving("main", null, supplier);

        // when
        VCSAnalyzerException ex = assertThrows(VCSAnalyzerException.class, identity::getCommitValue);

        // then
        assertTrue(ex.getMessage().contains("tiaCommitValue"),
                "message should name tiaCommitValue, was: " + ex.getMessage());
    }

    /**
     * Verifies that a supplier returning null is reported the same way as one that threw. A null
     * reader is what a caller that guards its supplier on a disabled-Tia flag would hand over, and
     * the resulting NullPointerException would say nothing about which property to set.
     */
    @Test
    void getBranch_supplierReturnsNull_throwsNamingTiaBranch() {
        // given
        Supplier<VCSReader> supplier = () -> null;
        WorkspaceIdentity identity = WorkspaceIdentity.resolving(null, null, supplier);

        // when
        VCSAnalyzerException ex = assertThrows(VCSAnalyzerException.class, identity::getBranch);

        // then
        assertTrue(ex.getMessage().contains("tiaBranch"),
                "message should name tiaBranch, was: " + ex.getMessage());
    }

    /**
     * Verifies that a reader that opened but failed to report a value is closed before the failure
     * propagates, rather than left open by the throw.
     */
    @Test
    void getBranch_vcsReadFails_closesTheReaderAndThrowsNamingTiaBranch() {
        // given
        FakeVCSReader reader = new FakeVCSReader("vcs-branch", "vcs-commit");
        reader.failOnRead = true;
        WorkspaceIdentity identity = WorkspaceIdentity.resolving(null, null, new CountingSupplier(reader));

        // when
        VCSAnalyzerException ex = assertThrows(VCSAnalyzerException.class, identity::getBranch);

        // then
        assertTrue(ex.getMessage().contains("tiaBranch"),
                "message should name tiaBranch, was: " + ex.getMessage());
        assertEquals(1, reader.closeCount, "the opened reader should have been closed");
    }

    /**
     * Verifies that a null supplier is rejected at construction. Every call site has a reader to
     * offer even when it expects never to need one, so a null supplier is a wiring mistake, and
     * failing at construction reports it at the point it was made rather than on the first getter
     * that happens to need the VCS.
     */
    @Test
    void resolving_nullSupplier_throws() {
        // given
        Supplier<VCSReader> supplier = null;

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WorkspaceIdentity.resolving("main", "abc123", supplier));

        // then
        assertTrue(ex.getMessage().contains("VCS reader"),
                "message should say what was missing, was: " + ex.getMessage());
    }

    /**
     * Verifies that a caller that needs the reader itself gets one even when both identity values
     * were configured. The test selection reads diffs, which no configured branch or commit can
     * answer for, so the explicit request must not be short-circuited by the configuration that
     * covers the two getters.
     */
    @Test
    void openVCSReader_bothValuesConfigured_stillConstructsTheReader() {
        // given
        FakeVCSReader reader = new FakeVCSReader("vcs-branch", "vcs-commit");
        CountingSupplier supplier = new CountingSupplier(reader);

        // when
        VCSReader opened;
        try (WorkspaceIdentity identity = WorkspaceIdentity.resolving("main", "abc123", supplier)) {
            opened = identity.openVCSReader();
        }

        // then
        assertSame(reader, opened);
        assertEquals(1, supplier.invocations);
        assertEquals(1, reader.closeCount, "the identity should close the reader it opened");
    }

    /**
     * Verifies that a caller needing both the branch and the diffs shares one reader between them.
     * Opening a second would mean a second Perforce server connection for one build.
     */
    @Test
    void openVCSReader_afterResolvingFromTheVcs_sharesTheOneReader() {
        // given
        FakeVCSReader reader = new FakeVCSReader("vcs-branch", "vcs-commit");
        CountingSupplier supplier = new CountingSupplier(reader);

        // when
        VCSReader opened;
        try (WorkspaceIdentity identity = WorkspaceIdentity.resolving(null, null, supplier)) {
            identity.getBranch();
            opened = identity.openVCSReader();
        }

        // then
        assertSame(reader, opened);
        assertEquals(1, supplier.invocations);
    }

    /**
     * Verifies that asking for the reader after the identity was closed fails rather than quietly
     * constructing a second one, which would hand the caller a reader nothing is going to close.
     */
    @Test
    void openVCSReader_afterClose_throws() {
        // given
        WorkspaceIdentity identity = WorkspaceIdentity.resolving(null, null,
                new CountingSupplier(new FakeVCSReader("vcs-branch", "vcs-commit")));
        identity.getBranch();
        identity.close();

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class, identity::openVCSReader);

        // then
        assertTrue(ex.getMessage().contains("closed"),
                "message should say the identity is closed, was: " + ex.getMessage());
    }

    /**
     * Counts how many times a VCS reader was asked for, which is how every "no VCS was touched"
     * assertion in this class is made.
     */
    private static final class CountingSupplier implements Supplier<VCSReader> {
        private final VCSReader reader;
        private int invocations;

        private CountingSupplier(final VCSReader reader) {
            this.reader = reader;
        }

        @Override
        public VCSReader get() {
            invocations++;
            return reader;
        }
    }

    /**
     * A VCS reader that reports fixed values and records its closes. Only the branch and head
     * commit are implemented; the diff methods throw, because a {@link WorkspaceIdentity} that
     * called one of them would be doing work no caller of this class asked for.
     */
    private static final class FakeVCSReader implements VCSReader {
        private final String branch;
        private final String headCommit;
        private boolean failOnRead;
        private int closeCount;

        private FakeVCSReader(final String branch, final String headCommit) {
            this.branch = branch;
            this.headCommit = headCommit;
        }

        @Override
        public String getBranchName() {
            if (failOnRead) {
                throw new VCSAnalyzerException("could not read the branch");
            }
            return branch;
        }

        @Override
        public String getHeadCommit() {
            if (failOnRead) {
                throw new VCSAnalyzerException("could not read the head commit");
            }
            return headCommit;
        }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum,
                                                        final List<String> sourceFilesDirs,
                                                        final List<String> testFilesDirs,
                                                        final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("WorkspaceIdentity must not read diffs");
        }

        @Override
        public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs,
                                         final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("WorkspaceIdentity must not read diffs");
        }

        @Override
        public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("WorkspaceIdentity must not read diffs");
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
