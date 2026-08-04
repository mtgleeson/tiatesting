# Note: coverage edge removal as an under-selection vector

Date: 2026-08-04
Status: Closed for the stale-baseline case. Out of scope for the spurious case, by decision.

## What this note covers

An edge (`tia_source_class_method` row) is removed whenever a run observes a suite covering less
code than before, because `persistTestSuiteClasses` replaces a suite's rows wholesale. Edge
*addition* is self-correcting; edge *removal* is not, and a removed edge means a suite is not
selected for a change it should have been selected for. Under-selection produces no error, just
a test that did not run.

Two distinct problems were identified. They are recorded together because they look alike, but
they have different causes, different blast radii and different resolutions.

## Case 1: stale baseline - RESOLVED

Stored commit A. A run at commit B rewrites suite S's rows and the S→M edge is legitimately
removed, because at B the code genuinely no longer has S reaching M. The build fails before the
seal, so the stored commit stays at A. Commit C restores the behaviour. The next build diffs
A→C, sees M change, but the mapping says S does not cover M, so S is not selected.

The defining feature is a **disagreement between the mapping and the baseline the diff runs
from**: the mapping is at B, the diff starts at A. The removal was correct at B and is only wrong
when read against A.

There is a structural argument that this self-heals. For S to stop covering M it must take a
different path, and the branch deciding that lives in code S still covers, so the restoring
change at C lands in a method S still has an edge to and selects S that way. It holds in the
common case but has real counterexamples: dispatch driven by untracked files (`.properties`, DI
wiring, reflection), or a caller deleted at B and restored at C. An argument, not a guarantee.

**Resolved** by Problem C in
`docs/superpowers/specs/2026-08-04-mapping-write-integrity-design.md`: suite mapping rows are
marked unsealed when written and cleared by the seal, so a crash before the seal leaves exactly
the suites that ran flagged for a forced re-run. That replaces the structural argument with a
mechanical guarantee and makes the counterexamples irrelevant.

## Case 2: spurious removal - OUT OF SCOPE, by decision

This asks a different question: **why was the edge removed at all?** Case 1 assumes the removal
was legitimate. But an edge is removed whenever a run merely *observes* less coverage, and
observation is not the same as truth.

Example. Suite S calls a service backed by a lazily-initialised cache. Running after suite T, the
cache is already populated, S takes the cache-hit path and never executes `CacheLoader.load()`.
Running first, it executes `load()`. Both runs pass. Whichever ran last wins, and if it was the
cache-hit ordering the S→load edge is deleted from a mapping that is otherwise perfectly in sync
with the stored commit. No failed build, no baseline disagreement, and no window that closes.

Sub-case worth recording: a suite that **fails early** also observes less coverage, but it lands
in `tia_test_suites_failed` and `addPreviouslyFailedTests` force-runs it next build, so a passing
re-run restores its full coverage. That case is self-healing. The unprotected case is a suite
that **passes** while covering less, because nothing forces it to run again.

**Out of scope by decision (2026-08-04):** this is outside Tia's control. The execution path
through source code from a given test should be deterministic between runs given unchanged source
and test code, and tests should be written not to depend on or be affected by other tests. No
easy solution belongs inside Tia.

Recorded because it is worth knowing that **distributed runs amplify it**. Today every suite runs
in one JVM after the same set of preceding suites, so order-dependent coverage is at least
consistently order-dependent. Under distribution a suite's neighbours are whichever suites landed
in its group, and group composition changes every build with the selection and the group count -
so order-dependent coverage would start varying run to run. If this ever does surface, the
distributed rollout is the most likely trigger.

Directions considered but not evaluated, if it is ever revisited: measure edge churn at a fixed
commit before designing anything; union rather than replace with a separate eviction policy;
require corroboration before removal (absent from N consecutive runs of that suite).

## Related

- `docs/superpowers/specs/2026-08-04-mapping-write-integrity-design.md` - resolves case 1.
- `docs/superpowers/specs/2026-08-04-distributed-test-runs-design.md` - where both were found.
- `wiki/persist-flow-and-crash-safety.md` - the Category A failure-mode taxonomy, whose
  "no under-selection" claim this note qualifies and the integrity spec then repairs.
