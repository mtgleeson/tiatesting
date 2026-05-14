---
title: Tia - Test Impact Analysis
theme: white
highlightTheme: github
favicon: assets/tia_favicon.ico
revealOptions:
  transition: fade
  slideNumber: true
  width: 1280
  height: 800
---

<style>
  :root {
    --tia-navy: #1B2D4F;
    --tia-navy-soft: rgba(27, 45, 79, 0.08);
    --tia-green: #3CA53C;
    --tia-bg: #f5f8fb;
    --tia-card: #ffffff;
  }

  /* Page background: tinted off-white + subtle grid texture */
  html, body, .reveal, .reveal .slides, .reveal-viewport {
    background: var(--tia-bg) !important;
    color: var(--tia-navy);
  }
  .reveal-viewport::before {
    content: '';
    position: fixed;
    inset: 0;
    pointer-events: none;
    z-index: 0;
    background-image:
      linear-gradient(rgba(27, 45, 79, 0.05) 1px, transparent 1px),
      linear-gradient(90deg, rgba(27, 45, 79, 0.05) 1px, transparent 1px);
    background-size: 28px 28px;
  }
  .reveal .slides { z-index: 1; }

  /* Typography */
  .reveal, .reveal p, .reveal li {
    font-family: 'Inter', 'Helvetica Neue', -apple-system, sans-serif;
    color: var(--tia-navy);
    font-size: 28px;
    line-height: 1.4;
  }
  .reveal h1, .reveal h2, .reveal h3 {
    color: var(--tia-navy);
    font-weight: 700;
    text-transform: none;
    letter-spacing: -0.01em;
  }
  .reveal h1 { font-size: 2.2em; }
  .reveal h2 {
    font-size: 1.5em;
    border-bottom: 4px solid var(--tia-green);
    display: inline-block;
    padding-bottom: 0.1em;
    margin-bottom: 0.5em;
  }

  /* Slide number, controls, progress */
  .reveal .slide-number {
    color: var(--tia-green);
    background: transparent;
    font-weight: 600;
  }
  .reveal .controls { color: var(--tia-navy); }
  .reveal .progress { color: var(--tia-green); }

  /* Bullets — green markers */
  .reveal ul { list-style: none; padding-left: 0; }
  .reveal ul li {
    padding-left: 1.2em;
    position: relative;
    margin-bottom: 0.35em;
  }
  .reveal ul li::before {
    content: '▸';
    color: var(--tia-green);
    position: absolute;
    left: 0;
    font-weight: 700;
  }
  .reveal ul li strong { color: var(--tia-navy); }

  /* Blockquote (slide 3 callout) */
  .reveal blockquote {
    background: var(--tia-card);
    border-left: 5px solid var(--tia-green);
    padding: 0.7em 1.2em;
    box-shadow: 0 4px 20px rgba(27, 45, 79, 0.10);
    font-style: normal;
    font-weight: 500;
    color: var(--tia-navy);
    border-radius: 4px;
    width: 80%;
    margin: 0.5em auto 0.8em;
  }

  /* Inline code */
  .reveal code {
    background: var(--tia-navy-soft);
    color: var(--tia-navy);
    padding: 0.05em 0.35em;
    border-radius: 4px;
    font-size: 0.85em;
  }

  /* Tables (slide 5) */
  .reveal table {
    font-size: 0.7em;
    border-collapse: collapse;
    margin: 0.4em auto;
    background: var(--tia-card);
    box-shadow: 0 4px 20px rgba(27, 45, 79, 0.10);
    border-radius: 4px;
    overflow: hidden;
  }
  .reveal table th {
    background: var(--tia-navy);
    color: white;
    padding: 0.5em 1em;
    font-weight: 600;
    text-align: left;
  }
  .reveal table td {
    padding: 0.4em 1em;
    border-top: 1px solid var(--tia-navy-soft);
    vertical-align: top;
  }
  .reveal table tr:nth-child(even) td { background: rgba(27, 45, 79, 0.02); }

  /* Diagram card */
  .reveal img.diagram {
    background: var(--tia-card);
    padding: 0.8em;
    border-radius: 8px;
    box-shadow: 0 4px 24px rgba(27, 45, 79, 0.10);
    margin: 0.3em auto;
    display: block;
    max-width: 86%;
    max-height: 56vh;
    width: auto;
    height: auto;
  }

  /* Top-right logo wordmark on every content slide. The logo has a stable
     3:2 landscape aspect ratio, so it can't look squished regardless of the
     box dimensions. */
  .reveal .slides section:not(.no-badge)::after {
    content: '';
    position: absolute;
    top: 16px;
    right: 16px;
    width: 160px;
    height: 107px;
    background-image: url('assets/tia_logo.png');
    background-size: contain;
    background-position: top right;
    background-repeat: no-repeat;
    opacity: 0.85;
    pointer-events: none;
  }

  /* Title + Q&A slides */
  .reveal .slides section.no-badge { text-align: center; }
  .reveal .title-logo {
    max-height: 300px;
    margin-bottom: 0.3em;
    filter: drop-shadow(0 6px 24px rgba(27, 45, 79, 0.15));
  }
  .reveal .footnote {
    color: var(--tia-navy);
    opacity: 0.7;
    font-size: 0.55em;
    margin-top: 0.4em;
  }
  .reveal .footnote a, .reveal a {
    color: var(--tia-green);
    text-decoration: none;
    border-bottom: 1px solid var(--tia-green);
  }
  .reveal .footnote a:hover { opacity: 1; }
</style>

<!-- .slide: class="no-badge" -->

<img class="title-logo" src="assets/tia_logo.png" alt="Tia logo" />

# Test Impact Analysis for the JVM

<p class="footnote">Speaker name &middot; date</p>

---

## The problem

- Full test suites run on every change &mdash; even one-line edits.
- Most of those tests never touch the changed code.
- Cost shows up as **slow CI feedback**, **wasted compute**, and **noisy signal**.

Note: "I fixed a typo in `StringUtil` and 4000 tests just ran" — set the scene.

---

## What is Tia

> Runs **only the tests impacted by your code changes.**

- Selects tests **per change**, based on a coverage map built by previous runs.
- **Branch-aware** &mdash; each branch maintains its own mapping.
- **Drop-in** via Maven / Gradle plugin.
- JVM-focused today; principle applies to any language with coverage tooling.

---

## Benefits

- **Faster CI cycles** &mdash; shorter wait between push and signal.
- **Lower compute spend** &mdash; pay only for tests that matter.
- **Same effective coverage** for code that actually changed.
- **Stable mapping** &mdash; updates gated to CI; local runs don't pollute.
- **Branch isolation** &mdash; no bleed between feature branches.

Note: Tradeoff — mapping quality depends on representative CI runs.

---

## Supported frameworks & ecosystem

| Test frameworks | Build tools | VCS |
|---|---|---|
| JUnit 4 *(legacy)* | Maven (`tia-maven-plugin`) | Git |
| JUnit 5 (Jupiter) | Gradle (`tia-gradle` + variants) | Perforce |
| Spock 2.x | | |

Note: Spock support is independent of the JUnit 5 path even though Spock uses the JUnit Platform &mdash; Tia has a dedicated Spock listener.

---

## How it works &mdash; at a glance

<img class="diagram" src="assets/diagrams/overview.svg" alt="Overview flow" />

- Two loops: **selection** (1 → 2 → 3 → 4) and **mapping update** (4 → 2).

---

## How it works &mdash; building the mapping

<img class="diagram" src="assets/diagrams/mapping.svg" alt="Mapping build flow" />

- Coverage captured per **suite**, then the agent's data is reset.

---

## How it works &mdash; selecting tests

<img class="diagram" src="assets/diagrams/selecting.svg" alt="Test-selection flow" />

- No mapping yet on this branch? &rarr; runs everything (safe default).
- Modified or newly-added test files &rarr; always run.
- Tests that failed last run &rarr; always re-run.

---

## What if no tests are selected?

- Every previously-tracked test goes into the **ignore list**.
- The Tia agent attaches `@Disabled` (or equivalent) to each ignored class at JVM load time.
- Surefire / Gradle still starts the test phase, but **almost nothing executes** &mdash; the phase completes in seconds.
- Any **new test file** not yet in the mapping still runs &mdash; it can't be in the ignore list.

Note: if there is no mapping for the branch at all, both runs and ignores are empty, so the agent disables nothing and the full suite runs — the safe-default path.

---

## How it works &mdash; branch & update model

- Mapping **keyed by branch**; each branch keeps its own HEAD pointer.
- Mapping updates gated to CI via `tiaUpdateDBMapping=true`.
- Local dev runs **read** but don't write &mdash; source-of-truth stays stable.
- Fresh branch &rarr; runs everything until the first successful CI mapping commit.

---

## How it works &mdash; tracking libraries

- Tia can also track changes in **in-repo source libraries** declared via `tiaSourceLibs`.
- The build plugin resolves each coordinate to a JAR + source directory.
- Library method changes invalidate suites just like in-project changes.
- Versioning policy (`BUMP_AT_RELEASE` / `BUMP_AFTER_RELEASE`) controls when a new version triggers re-mapping.

Note: useful for shared internal modules consumed via Maven/Gradle by the project under test.

---

## Live demo

---

<!-- .slide: class="no-badge" -->

<img class="title-logo" src="assets/tia_logo.png" alt="Tia logo" />

# Q&A

<p class="footnote"><a href="https://github.com/mtgleeson/tiatesting">github.com/mtgleeson/tiatesting</a></p>
