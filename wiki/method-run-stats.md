# Method run stats

Every method in the catalogue (`tia_source_method`) carries two counters:

- **`executed_run_count`** - the number of runs that executed the method, meaning at least one of
  the run's test suites covered it.
- **`triggered_run_count`** - the number of runs whose test selection was triggered by a change to
  the method, meaning the diff touched its lines and its covering suites were selected because of it.

Together they show how often a method is exercised by the test suite and how often changing it
costs a test run. Both appear in the HTML report: as numeric columns on the Source Methods index,
and in a "Run stats" section on each method's detail page.

### Which runs count

The counters follow the same rules as the core and per-suite stats: **only mapping-update runs
count**. A run that doesn't update the mapping writes nothing to the catalogue, so it never touches
the counters. Within that:

- **A retry of failed tests adds nothing.** The first attempt already counted the run, and a retry
  carries the same selection, so counting it again would double the triggered count too. This is
  the same rule that keeps a retry out of the core stats (see
  [Failed-suite tracking](failed-suite-tracking.md) for how a retry is detected).
- **A distributed build counts once.** The sealer seals once per build, using the union of every
  runner's observed methods and the triggers the plan staged. See
  [Distributed test runs](distributed-test-runs.md).
- **A run that executed none of its expected suites doesn't seal**, so it isn't counted.

### How the counts accumulate

The counts are written at the seal, as part of the catalogue rewrite the seal already does (see
[Persist flow and crash safety](persist-flow-and-crash-safety.md)). `SealedRunDataAssembler`
rebuilds the catalogue from the on-disk trackers and the trackers the run observed. An observed
tracker is built fresh from JaCoCo coverage and starts at zero, so the assembler first copies each
method's stored counts onto whichever tracker it keeps, then:

- adds one to `executed_run_count` for every method the run observed, and
- adds one to `triggered_run_count` for every catalogued method whose change triggered the run.

A triggering method that is no longer in the catalogue (deleted, or no longer covered by any suite)
has nowhere to record the run and is skipped.

### Carrying the triggering method ids to the seal

Selection happens in the build JVM, but the seal happens later: in the forked test JVM for a
single-host run, or in the elected sealer for a distributed build. The ids of the triggering
methods travel there on the selection's `SOURCE_METHOD` triggers (`TestRunTrigger.getMethodId()`),
over the same channels the [run history details](run-history-details.md) breakdown already uses:

- **Single-host runs:** the `run-selection-details.txt` sidecar file has a method id field on each
  trigger line, empty for a static rule.
- **Distributed runs:** `tia_distributed_run_trigger.trigger_method_id`, staged at plan time. The
  sealer reads the staged breakdown whenever it seals, not only when it writes a history row, so the
  count is recorded even with history logging off.

The id isn't stored in `tia_test_run_history_trigger`; only the seal needs it, so triggers read
back from run history have no id.

The trigger name can't stand in for the id. A method id is the hash of the method's full name, but
the trigger name falls back to the bare id when the name isn't known, so hashing the name back
would count the wrong method.

### What resets the counts

- **A method that leaves the catalogue loses its counts.** That happens when no suite covers it any
  more, or when it is deleted. Renaming a method or changing its signature gives it a new id, so it
  starts again from zero.
- **A re-seed keeps them** for every method still covered, because the re-seed clear-out only
  deletes catalogue rows no remaining coverage edge references. See
  [Forced runs and re-seed](forced-runs-and-reseed.md).
- **An existing DB starts every method at zero.** The columns are added by an
  `ADD COLUMN IF NOT EXISTS` migration with `DEFAULT 0`.

### Cost

Measured with H2 2.2.224 on a catalogue of 50K methods with realistic names:

- **Disk:** about 8 bytes per method, so +0.38 MB at 50K methods (2.63 MB -> 3.01 MB for the
  catalogue table). Against a 258 MB DB with 5.6M coverage edges that is about 0.15%; the edges
  dominate the size and are untouched. Two columns on the existing row are smaller than a separate
  stats table (+0.65 MB), which would add nothing in return.
- **select-tests:** no change. The targeted changed-files query names its columns and doesn't read
  the counters (see [Profiling select-tests against a synthetic large DB](profiling-select-tests.md)
  for the read path). The by-id catalogue read uses `SELECT *` but only for the methods the diff
  touches.
- **Seal:** the increments are in-memory map updates on a catalogue the seal already reads in full,
  and the counters ride along on the rewrite it already does: the 50K-row insert went from 180 ms to
  188 ms. Incrementing in place with an `UPDATE` per method instead would have cost 62 ms per 5K
  methods at 50K, and 458 ms per 20K at 200K, so the counters deliberately piggyback on the rewrite.
