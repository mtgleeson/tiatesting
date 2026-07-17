# Tia working conventions

This file captures project-specific conventions Claude should follow when working in this repo. These apply across all modules (tia-core, tia-maven-plugin, tia-gradle, the language/VCS sub-plugins, etc).

## Testing

- Write unit tests for every code change.
- Use the `// given` / `// when` / `// then` style: each test method begins with these three marker comments separating setup, action, and assertion. Existing tests in `tia-core/src/test` follow this pattern; match it.

## Javadocs

- Add a javadoc to every new or modified method.
- Use `@param` for each parameter and `@return` when the method returns a value.
- The javadoc should explain the method's purpose and summarize what it does, not just restate the signature.
- This is the project default. The "no comments" rule from broader guidance does not apply here.
- Do not reference planning/design markdown files (e.g. `DESIGN-*.md`) in javadocs or code comments; reference the relevant `WIKI.md` chapter instead. Design docs are transient working documents; the WIKI is the durable reference. When a design lands and its WIKI chapter is written, re-point any interim references at the WIKI.

## Staged delivery

- Break big changes into discrete reviewable stages.
- After completing each stage, stop and wait for review before starting the next stage. Do not batch stages together.
- After each stage, print a short summary suitable for pasting into the commit message:
  - Files added or modified.
  - One or two sentences on what changed and why.
  - Any reviewer check or follow-up that's intentionally deferred to a later stage.

## Branching

- Big or non-trivial changes go on a new branch, not on main and not on an unrelated branch.
- Create the branch before starting the implementation; do not start work on the existing branch and decide to branch later.
- Stack dependent branches off each other (A then B-off-A) rather than mixing unrelated work in one branch.

## Build verification

- After making changes, compile the affected modules.
- Read any build analysis messages or warnings reported on lines touched by the change. Address the ones that relate to your changes; ignore pre-existing warnings on unrelated lines.
- Pay attention to common cleanups the IDE suggests (try-with-resources for AutoCloseable, redundant null checks, unused imports) when they apply to code you just wrote.

## No backwards-compatibility shims

- Tia is pre-release with no external users. Change signatures directly; update all callers in the same change.
- Do not add transitional overloads, deprecated routing methods, or backwards-compatibility wrappers.
- This applies to public APIs in `tia-core` as well as to internal helpers.

## Performance

- Tia's primary purpose is to cut total build time via selective testing. It must itself run fast.
- When planning a change, consider its performance impact. Read paths in the `select-tests` flow (and anything called by `TestSelector.selectTestsToIgnore` or `H2DataStore.getTiaData`) are particularly sensitive.
- Avoid adding work on the hot read path that doesn't have an owner who needs it. Prefer lazy-load via `Supplier`, share computed results between callers, or skip work for callers that don't consume it.
- The synthetic large-DB harness under `tia-core/src/test/java/org/tiatesting/core/perf/` (`generateLargeTiaDb`, `profileSelectTests`) exists for measuring this. See the "Profiling select-tests against a synthetic large DB" chapter in `WIKI.md`.

## Style: hyphens

- Use a normal ASCII hyphen `-`. Do not use the em-dash character.
- Applies to commit messages, javadocs, code comments, and any prose Claude writes about the project.
