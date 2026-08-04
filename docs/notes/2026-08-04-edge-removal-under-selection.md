# Note: coverage edge removal as an under-selection vector

Date: 2026-08-04
Status: Open. Not scheduled, not designed. Raised during the distributed test runs design.

## Why this note exists

Any scenario that can produce under-selection is a correctness concern for Tia, however rare.
This one was identified while arguing that coverage edges are safe when a build fails before the
seal. The argument holds for the general case, but it has a hole worth investigating on its own.

While writing it up, the hole turned out to be **broader than the failed-build case**. It is not
specific to distributed runs, and not specific to unsealed builds. See framing 2, which is
probably the one that matters.

## Framing 1: the narrow case (unsealed build)

Stored commit A. A build at commit B runs suite S with coverage and rewrites its mapping rows.
S's coverage no longer includes method M, which it did cover at A, so the S→M edge is removed.
The build then fails before the seal, so the stored commit stays at A.

The next build diffs A→C. M changed somewhere in that range. S is not selected, because the
current mapping says S does not cover M. But relative to the baseline the build is actually
diffing from - commit A - S *did* cover M.

The argument for why this is acceptable: S genuinely stopped covering M at B, coverage is a
function of the code, so at C it still does not cover M, and the B-generation edge is the more
accurate one. Producing a real failure needs coverage to change at B and change back before C -
for instance a revert landing between B and C.

That argument depends entirely on "coverage is a function of the code", which is where framing 2
comes in.

## Framing 2: the broader case (any run, sealed or not)

**Coverage is not a pure function of the code in practice.** A test's covered set can vary
between runs at the same commit:

- Conditional paths driven by time, locale, timezone, random seeds, hostname, environment.
- Tests that are flaky in *which* code they reach, not only in whether they pass.
- Ordering effects between suites (caches, statics, lazily-initialised singletons).
- Suites that short-circuit early on a failure, so a failing run covers strictly less than a
  passing one.

Whenever a run observes less coverage than the previous run, `persistTestSuiteClasses` rewrites
that suite's rows to the smaller set, permanently removing the edge. Nothing ever restores it
except another run of that suite that happens to reach the code again.

The last bullet is the one that looks most likely to bite: a suite that fails early covers less,
Tia records the reduced mapping, and the suite is then not selected for changes to the code it
used to cover. The suite is in `tia_test_suites_failed` so it is force-re-run next time, which
masks it in the common case - but only until it passes once.

So the general shape is: **any transient reduction in observed coverage narrows the stored
mapping permanently, and a narrowed mapping under-selects.** Edge *addition* is self-correcting;
edge *removal* is not.

## Why this is not obviously already a problem

Tia has been running against real projects without this surfacing, which suggests either that
coverage is stable enough in practice, or that the failed-suite force-re-run and the
modified-test-file rule mask it. Worth establishing which before designing anything.

## Possible directions, none evaluated

- **Measure first.** Instrument a project over many runs at a fixed commit and count edge
  churn: how often does a suite's covered set shrink without a code change? This determines
  whether the concern is theoretical or live, and should precede any design.
- **Union rather than replace**, with an explicit eviction policy. Removes the under-selection
  vector but grows the mapping monotonically and would need a way to drop genuinely dead edges,
  or the DB grows without bound and selection widens over time.
- **Only shrink a suite's edges on a clean pass.** Cheap and targeted at the
  early-failure case specifically: a suite that failed did not run to completion, so its coverage
  is not authoritative and should not replace a wider stored set. This is probably the highest
  value-to-cost option and may be worth doing regardless of what the measurement shows.
- **Track edge age** and require corroboration before removal (seen absent N consecutive runs).
  More faithful, more state.

## Related

- `docs/superpowers/specs/2026-08-04-distributed-test-runs-design.md` - where this was found;
  contains the full edge-safety argument this note qualifies.
- `docs/superpowers/specs/2026-08-04-mapping-write-integrity-design.md` - the *other*
  under-selection vector found at the same time (method catalogue coordinate misalignment).
  That one is understood and being fixed; this one is not yet understood.
- `wiki/persist-flow-and-crash-safety.md` - the Category A failure-mode taxonomy.
