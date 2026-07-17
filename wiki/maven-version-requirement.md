# Why Tia requires Maven 3.8.1+

### The problem

A Maven plugin runs *inside* the user's `mvn` process. The user's installed Maven supplies the actual `maven-core`, `maven-plugin-api`, and resolver classes at runtime — what the plugin's POM declares for those dependencies is only a compile-time API contract. This is true for every Maven plugin, including each of Tia's: `tia-junit4-git-maven-plugin`, `tia-junit4-perforce-maven-plugin`, `tia-junit5-git-maven-plugin`, `tia-junit5-perforce-maven-plugin`.

That separation matters when one of those Maven runtime classes turns out to have a vulnerability. The plugin's compile-time version of `maven-core` is irrelevant to the user's exposure; only the user's Maven installation is. Bumping the plugin's `<dependency>` version on `maven-core` would silence vulnerability scanners but leave users on old Maven runtimes just as exposed as before. The fix has to live somewhere that actually changes what the user runs against.

The driving CVE here is [CVE-2021-26291](https://nvd.nist.gov/vuln/detail/CVE-2021-26291). Maven before 3.8.1 will follow `<repository>` entries declared in transitively-resolved POMs, including ones served over plain `http://` URLs. A malicious POM published anywhere in a project's transitive dependency graph could redirect artifact resolution to an attacker-controlled HTTP repository and substitute a poisoned JAR. Maven 3.8.1 introduced the "external HTTP blocker" mitigation: by default, non-HTTPS external repositories declared in transitive POMs are rejected at resolution time. The vulnerability is fixed by upgrading the Maven runtime, not by anything the plugin can do at compile time.

### The design

Tia's Maven plugins set `<prerequisites><maven>3.8.1</maven></prerequisites>` in each plugin POM. Maven enforces this floor at *plugin discovery time*: when the user runs `mvn tia:select-tests` (or any other goal) under Maven < 3.8.1, the plugin loader refuses to load the plugin and emits a clear error along the lines of "requires Maven version 3.8.1". The check happens before any of the plugin's code runs, so users on an older Maven get an actionable message immediately rather than a confusing failure deep in plugin execution.

A few non-obvious design decisions sit behind this:

**Why `<prerequisites>` and not `maven-enforcer-plugin`'s `requireMavenVersion` rule.** The two mechanisms look interchangeable but serve different purposes. `requireMavenVersion` runs as part of the *consuming project's* build — it enforces a Maven floor on whoever is *building* the project that uses the plugin. `<prerequisites>` is a property of the *plugin's* POM and is checked by Maven's plugin-loading machinery at the moment the plugin is invoked. For a tool whose only purpose is to run as a plugin, `<prerequisites>` is the more direct fit: it lets the plugin itself say "I need this Maven runtime" without requiring the consuming project to add and configure an enforcer plugin. It also runs even when the consuming project's build doesn't bind enforcer to any phase.

**Why `<prerequisites>` only on the four wrapper plugins, not on `tia-maven-plugin`.** `tia-maven-plugin` is `packaging=jar`, not `packaging=maven-plugin`. It's a shared library — never invoked directly as a plugin — that the four wrappers extend. Maven only honours `<prerequisites>` for `maven-plugin` packaging; on a jar, it's a no-op and the build emits a deprecation warning. End users never depend on `tia-maven-plugin` directly: they pick a wrapper based on their test runner (Junit4 / Junit5) and VCS (Git / Perforce), and `tia-maven-plugin` comes in transitively. Putting the floor on the wrappers is both sufficient (every entry point covered) and the only place it actually does work.

**Why 3.8.1 specifically, and not a more recent LTS like 3.9.x.** 3.8.1 is the *lowest* Maven version that fixes CVE-2021-26291. Picking it as the floor maximises the user base we accept while still actually closing the vulnerability. Bumping to 3.9.x would lock out users running otherwise-fine 3.8.x installations for no security gain — they're already protected from the CVE. The floor can move forward later if a future Maven version fixes a CVE that 3.8.x doesn't, but raising it without that justification would just be churn.

**Why we still pin transitive dep versions in `<dependencyManagement>`.** This is a separate concern from the runtime CVE. Transitive deps like `commons-lang3`, `plexus-utils`, `commons-io`, `guava`, and `maven-shared-utils` are flagged by SCA scanners (Mend, Snyk, Dependabot) against their default versions shipped with `maven-core:3.6.3`. Pinning them to non-vulnerable versions keeps the *plugin's declared dependency tree* clean for scanners and avoids shipping stale transitive artifacts when a future build resolution puts one of them on a non-provided scope. The pins don't change runtime exposure (the user's Maven still wins for runtime classes) — they're hygiene for static analysis, not a security fix.

### Why this approach over alternatives considered

**"Just bump the plugin's compile-time `maven-core` version."** Doesn't help users. The user's installed Maven supplies the runtime classes regardless of what the plugin declares. A user still on Maven 3.6.x is exposed to CVE-2021-26291 even if the plugin's POM says it depends on `maven-core:3.9.9`. This is a real misconception worth flagging because it's the path most automated remediation tools push you toward.

**"Don't enforce a floor; let users decide."** Acceptable for an internal tool, but Tia's Maven plugins are publicly distributed and this CVE is a critical-severity (CVSS 9.1) RCE-style supply-chain vector. A clear floor with an actionable error message is the right user experience: it tells them *why* the upgrade matters and gives them a concrete version to upgrade to.

**"Use `maven-enforcer-plugin` configured by the user."** Putting the responsibility on the consuming project's build is unreliable — most users won't add it, and the failure mode for those that don't (running on old Maven, getting silently exposed to the CVE) is worse than a clear plugin-loader error.

### Practical guidance

For users: run a recent Maven. 3.9.x is the current LTS; 3.8.x is also fine. The exact floor (3.8.1) is the lowest version that fixes the CVE, not a recommendation — most active users will be well above it.

For contributors: when upgrading Tia's compile-time Maven dependencies (e.g. for a future API surface change), the `<prerequisites>` floor is independent of those bumps. Move the floor only when there's a security or API reason that genuinely requires it; raising it gratuitously costs users for no gain. The transitive pins in `<dependencyManagement>` should track the latest non-vulnerable version on the Java-8-compatible line until the project's own Java baseline moves up.

---


---

Prev: [Logging conventions (TRACE vs DEBUG)](logging-conventions.md) | [Back to the Wiki index](../WIKI.md) | Next: [Profiling select-tests against a synthetic large DB](profiling-select-tests.md)
