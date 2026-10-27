# Local changes and a workspace behind the mapping

With `checkLocalChanges` on, Tia selects tests for the uncommitted edits in a developer's workspace
(Git) or the files opened in it (Perforce). The mapping it selects from is usually built by a CI job,
so the developer's workspace and the stored mapping commit are rarely in step. This chapter covers
what local mode diffs, why a workspace behind the mapping can make it miss tests, and what Tia does
about it.

### What local mode diffs

Local mode diffs only the local edits: each changed file's base revision against its working copy.
The base revision is `HEAD` in Git and the file's synced revision (`#have`) in Perforce. The stored
mapping commit is never used as the baseline, so commits submitted by other people never select
tests in local mode, whether the workspace is ahead of the stored commit or behind it.

Perforce reads the base at `#have` rather than at the workspace's highest synced changelist. A
workspace can have files synced to different changelists, and printing an opened file at the
highest one would add every change between its real base and that changelist to the diff, reversed.

### Why a workspace behind the mapping can miss tests

Tia matches a diff to tracked methods by line number (see
[Matching a diff to tracked methods](method-diff-matching.md)). The diff's line numbers come from
the developer's base revision, but the stored method line ranges come from the file at the stored
mapping commit. When the stored commit has changes to the file that the developer's base doesn't,
the two can disagree.

For example, the mapping is at CL 600 and the workspace is at CL 580. CL 590 added ten lines near
the top of `OrderService.java`, so every method below them sits ten lines lower in the mapping than
in the developer's copy:

| Method | Lines in the workspace (580) | Stored lines (600) |
|---|---|---|
| `calculateTotal()` | 50-65 | 60-75 |
| `applyDiscount()` | 70-85 | 80-95 |

An edit to line 72 is inside `applyDiscount()` in the developer's copy, but line 72 is inside the
stored range of `calculateTotal()`. Tia would run the tests for `calculateTotal()` and skip the ones
for `applyDiscount()`.

### What Tia does

In local mode, after the diff has been filtered to tracked source files, `TestSelector` asks the
VCS provider (`VCSReader.findMissingUpstreamChanges`) which of those files are missing upstream
changes. For each file that is, every tracked method in it is treated as impacted instead of being
matched by line, and the file's content isn't loaded. Tia logs a WARN that names each file, its
tracked method count and the missing changes, and explains why.

- **Perforce:** one batched `p4 files -a <depotPath>#>have,@<storedCL>` lists the revisions after
  each file's synced revision, up to the stored changelist. The check is per file, so it also
  catches a single file synced to an older revision than the rest of the workspace.
- **Git:** the workspace is behind when the stored commit isn't an ancestor of `HEAD`. Each file is
  then looked up in the commits from the merge base of `HEAD` and the stored commit up to the stored
  commit. That also covers a workspace with its own commits on an older base, and only upstream
  commits are reported. A workspace at or ahead of the stored commit costs one ancestry check.
- **Stored commit not in the repository (Git):** a clone that hasn't fetched since the mapping was
  built doesn't have the stored commit. The workspace is then almost certainly behind it, but the
  files can't be checked, so every tracked locally changed file is treated as fully impacted and the
  WARN says to fetch.

The check only runs when at least one tracked source file is locally modified, and costs at most one
VCS round trip.

### Known limitations

- **Only behind is handled.** A workspace ahead of the stored commit, or a Git workspace whose own
  unpushed commits changed a file, also has a base that differs from the file at the stored commit.
  The line numbers can disagree the same way, and the file is still matched by line.
- **Whole-file widening.** Every tracked method in an affected file is selected, which can run more
  tests than the edit needs. Syncing and resolving the file restores a precise selection.
