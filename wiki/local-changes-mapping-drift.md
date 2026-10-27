# Local changes and a workspace out of step with the mapping

With `checkLocalChanges` on, Tia selects tests for the uncommitted edits in a developer's workspace
(Git) or the files opened in it (Perforce). The mapping it selects from is usually built by a CI job,
so the developer's workspace and the stored mapping commit are rarely in step. This chapter covers
what local mode diffs, why a workspace behind or ahead of the mapping can make it miss tests, and
what Tia does about it.

### What local mode diffs

Local mode diffs only the local edits: each changed file's base revision against its working copy.
The base revision is `HEAD` in Git and the file's synced revision (`#have`) in Perforce. The stored
mapping commit is never used as the baseline, so commits submitted by other people never select
tests in local mode, whether the workspace is ahead of the stored commit or behind it.

Perforce reads the base at `#have` rather than at the workspace's highest synced changelist. A
workspace can have files synced to different changelists, and printing an opened file at the
highest one would add every change between its real base and that changelist to the diff, reversed.

### Why a workspace out of step with the mapping can miss tests

Tia matches a diff to tracked methods by line number (see
[Matching a diff to tracked methods](method-diff-matching.md)). The diff's line numbers come from
the developer's base revision, but the stored method line ranges come from the file at the stored
mapping commit (the mapped version). When the two versions of the file differ, the line numbers can
disagree. That happens in either direction:

- **Behind:** the mapped version has changes the developer's base doesn't. The developer hasn't
  synced since the mapping was built.
- **Ahead:** the developer's base has changes the mapped version doesn't. The developer synced past
  the mapping commit while the mapping job hasn't caught up yet, or (Git) committed their own
  changes to the file. This is the common case for a developer who syncs to the latest before
  starting work.

For example, the workspace is at CL 580 and the mapping is at CL 600. CL 590 added ten lines near
the top of `OrderService.java`, so every method below them sits ten lines lower in the mapping than
in the developer's copy:

| Method | Lines in the workspace (580) | Stored lines (600) |
|---|---|---|
| `calculateTotal()` | 50-65 | 60-75 |
| `applyDiscount()` | 70-85 | 80-95 |

An edit to line 72 is inside `applyDiscount()` in the developer's copy, but line 72 is inside the
stored range of `calculateTotal()`. Tia would run the tests for `calculateTotal()` and skip the ones
for `applyDiscount()`. Swap the numbers (the workspace at 600, the mapping at 580) and the same
mismatch happens the other way round.

### What Tia does

In local mode, after the diff has been filtered to tracked source files, `TestSelector` asks the
VCS provider (`VCSReader.findMappingDrift`) which of those files have drifted: their base version
differs from their mapped version. For each file that has, every tracked method in it is treated as
impacted instead of being matched by line, and the file's content isn't loaded. Tia logs a WARN that
names each file, its tracked method count and the changes on each side ("in the mapping but not your
copy", "in your copy but not the mapping"), explains why, and says how to get a precise selection
back: sync for a file that is behind, or wait for the mapping to be rebuilt for one that is ahead.

- **Perforce:** one batched `p4 files -a` call with two ranges per file. `<depotPath>#>have,@<storedCL>`
  lists the revisions the stored changelist has after the synced one (behind), and
  `<depotPath>@>storedCL,#have` lists the synced revisions after the stored changelist (ahead). The
  check is per file, so it also catches a single file synced to a different revision than the rest
  of the workspace.
- **Git:** one tree walk compares each file's blob at `HEAD` with its blob at the stored commit. Only
  a file whose blob differs has drifted, so a file changed and then changed back since the mapping
  was built is still matched by line. For each drifted file the commits that touched it are listed
  from the merge base of `HEAD` and the stored commit up to each of them. That covers a workspace
  behind, ahead, or with its own commits on an older base.
- **Stored commit not in the repository (Git):** a clone that hasn't fetched since the mapping was
  built doesn't have the stored commit. The workspace is then almost certainly behind it, but the
  files can't be checked, so every tracked locally changed file is treated as fully impacted and the
  WARN says to fetch.

The check only runs when at least one tracked source file is locally modified, and costs at most one
VCS round trip.

### Known limitations

- **Whole-file widening.** Every tracked method in a drifted file is selected, which can run more
  tests than the edit needs. A more precise alternative would translate the edit's line numbers
  through a diff of the base version against the mapped version, at the cost of fetching both. A
  behind file gets a precise selection back once it is synced, and an ahead file once the mapping is
  rebuilt with its changes.
