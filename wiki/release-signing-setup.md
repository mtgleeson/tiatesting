# Setting up a machine to run the release tasks (GPG signing)

Publishing Tia to Maven Central requires every artifact to be GPG-signed. This chapter is the step-by-step runbook for getting a fresh machine to the point where the release tasks succeed. Both build tools sign the same way - by shelling out to the `gpg` binary - so the setup is shared:

- **Gradle** signs via `useGpgCmd()` in `buildSrc/shared.gradle`, driven by the `signing.gnupg.*` properties. It runs `gpg`, selecting the key by `signing.gnupg.keyName`.
- **Maven** signs via the `maven-gpg-plugin` (the `release` profile in each `*-maven-plugin/pom.xml`), which also runs `gpg` and reads the key from your GnuPG keyring.

In both cases the passphrase is supplied by **gpg-agent + a pinentry program**, never stored in a build file. So a release machine needs a working `gpg` install, your secret key imported, and a pinentry that can unlock it - plus the Central Portal upload token. The rest of this chapter sets those up.

### The signing model: gpg binary + agent + pinentry (no stored passphrase)

Both build tools delegate the actual signing to `gpg`, and `gpg` delegates passphrase entry to `gpg-agent`, which calls a **pinentry** program. This keeps your passphrase out of every build file: Gradle's `signing.gnupg.keyName` and Maven's `gpg.executable` only name *which* key and binary to use - the secret key stays in your GnuPG keyring and the passphrase stays with the agent.

The one thing that varies by OS is the pinentry:
- **macOS** - `pinentry-mac` shows a native dialog with a "Save in Keychain" checkbox, so after the first entry the passphrase is retrieved from the login Keychain automatically and survives reboots.
- **Windows / Linux** - the bundled GUI/terminal pinentry prompts, and gpg-agent caches the passphrase in memory for a configurable TTL. It is not written to disk, but it is cleared when the agent stops (reboot).

Per-OS setup is in the "Platform notes" sections below. The alternative Gradle mode, `useInMemoryPgpKeys` (armored key + passphrase passed as Gradle properties), is deliberately *not* used: it would require storing key material and the passphrase in `gradle.properties`, which is exactly what the agent/Keychain model avoids.

### One-time GPG key setup (shared by both paths)

If you already have a published signing key, skip to the per-tool sections. Otherwise:

1. **Install GnuPG** (needed on every release machine, since both build tools shell out to `gpg`). The install differs by OS - see the Platform notes below (macOS: `brew install gnupg` + `pinentry-mac`; Windows: Gpg4win).

2. **Generate a key** (RSA 4096 or ed25519, no expiry or a long one), using the email you want associated with the release:
   ```
   gpg --full-generate-key
   ```

3. **Find the key ID** — the long hex string after `sec rsa4096/`:
   ```
   gpg --list-secret-keys --keyid-format long
   ```

4. **Publish the public key** to a keyserver so Central can verify signatures:
   ```
   gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
   ```

### Central Portal credentials (shared by both paths)

Both build tools upload to the Central Portal (`central.sonatype.com`), which authenticates with a **user token**, not your account password:

1. Log in to `central.sonatype.com`, go to **Account → Generate User Token**.
2. It produces a token *username* and *password* pair. These are the `ossrhUsername` / `ossrhPassword` values the Gradle `publishToCentral` task base64-encodes into its `Authorization: Bearer` header, and the `central` server credentials Maven uses.

These credentials authenticate the *upload* only - they are unrelated to GPG signing (gpg signs the artifacts; the token uploads them). The `ossrh` name is legacy, inherited from the retired OSSRH/Nexus service; the values are your current Central Portal token. They are required on every release machine - `publishToCentral` fails at configuration time with `Could not get unknown property 'ossrhUsername'` if they're absent.

### Gradle release setup

The Gradle release path (`./gradlew publishToCentral -Prelease`) needs your GnuPG key available to `gpg` (Platform notes below) plus a few properties in `~/.gradle/gradle.properties` (home directory, so they stay out of the repo - never a project-level `gradle.properties`):

```
signing.gnupg.executable=gpg
signing.gnupg.keyName=YOUR_KEY_ID
ossrhUsername=central-portal-token-username
ossrhPassword=central-portal-token-password
```

- `signing.gnupg.executable` / `signing.gnupg.keyName` drive `useGpgCmd()`: `gpg` is invoked and selects your key by id (short id, long id, or fingerprint all resolve). **No passphrase or key material is stored** - gpg-agent + pinentry supply the passphrase. On Windows set `signing.gnupg.executable` to `gpg` if it's on `PATH`, or the full path to `gpg.exe` (see the Windows Platform notes).
- `ossrhUsername` / `ossrhPassword` are the Central Portal upload token described above.

Then run (signing is opt-in via `-Prelease`; without it the signing task isn't registered, so plain `publishToMavenLocal` still works on a machine with no key):
```
./gradlew publishToCentral -Prelease
```

The first run triggers the pinentry passphrase prompt (and the Keychain save on macOS); subsequent runs are silent while the agent/Keychain holds the passphrase. This is the same `signing.gnupg.*` setup the Maven side's `gpg.executable` mirrors, so one imported key + one pinentry serves both build tools.

### Why the Gradle upload task is hand-rolled

`publishToCentral` is a bespoke task (a `packageDistribution` `Zip` feeding a `curl` `Exec` that POSTs the bundle to the Central Portal's `publisher/upload` endpoint) rather than a plugin. This is a deliberate choice given the current tooling landscape, not an oversight:

- **There is still no official Gradle plugin for the Central Portal.** Sonatype's own guidance (as of mid-2026) states there is no official Gradle plugin and that Gradle support is only "on our roadmap." The legacy OSSRH service the old Nexus plugins targeted reached end-of-life on 30 June 2025, so the Portal upload API this task calls is now the only route.
- **The Maven side is already on the official path.** The five Maven plugins publish through Sonatype's own `central-publishing-maven-plugin`, which *is* the official Maven tool. So the asymmetry (plugin for Maven, hand-rolled task for Gradle) exists only because Sonatype ships one for Maven and not for Gradle.
- **Community Gradle plugins exist but are explicitly unsupported by Sonatype.** The documented options are `GradleUp/nmcp`, `vanniktech/gradle-maven-publish-plugin`, `SgtSilvio/gradle-maven-central-publishing`, `lukebemishprojects/CentralPortalPublishing`, and JReleaser. Any of these could replace the `packageDistribution` + `curl` machinery while leaving the existing `publishing {}` publication and `useGpgCmd()` signing block intact - `nmcp` is the lightest-touch fit because it adds Portal upload tasks over the standard `maven-publish` setup rather than taking over the publication config. Adopting one is a possible future simplification, deferred while the hand-rolled task works and no official plugin exists.

If a first-party Sonatype Gradle plugin ships, revisit this task: it would likely let us delete the manual zip-and-`curl` step and get clearer upload diagnostics.

### Maven release setup

The Maven release path (`mvn deploy -Prelease`) signs through the `maven-gpg-plugin`, which reads the secret key from your local GnuPG keyring. So here the `gpg` install and an imported key *are* required:

1. **Import the secret key into the keyring** (skip if you generated it on this machine — it's already there). If you exported it elsewhere:
   ```
   gpg --import secret-key.asc
   ```

2. **gpg-agent config - usually nothing to do.** The pom passes `--pinentry-mode loopback`, and on current GnuPG (Homebrew's 2.4.x) loopback is allowed by default, so `maven-gpg-plugin` collects the passphrase and feeds it to gpg with no `gpg-agent.conf` change needed. In loopback mode gpg does **not** consult the configured `pinentry-program` at all - which is why the existing (Apple Silicon) release machine signs fine even though its only `gpg-agent.conf` line is `pinentry-program /opt/homebrew/bin/pinentry-mac`. That line is used only for interactive gpg *outside* Maven, not for the deploy.

   So on a new machine you do not need to touch `gpg-agent.conf` for the release to work. Two caveats:
   - **Don't copy the working machine's `pinentry-mac` line onto a machine that doesn't have that binary** (e.g. the Intel box here, where Homebrew lives under `/usr/local` and pinentry-mac never built). Leave `gpg-agent.conf` absent, or if you want a working pinentry for interactive gpg use, point it at the terminal one that ships with gnupg: `pinentry-program /usr/local/bin/pinentry` (Intel) or `/opt/homebrew/bin/pinentry` (Apple Silicon).
   - **Only if a deploy fails** with `gpg: signing failed: No pinentry` (older gpg, or loopback explicitly disabled) add `allow-loopback-pinentry` to `~/.gnupg/gpg-agent.conf` and reload with `gpgconf --kill gpg-agent`.

3. **Add the Central server and GPG profile to `~/.m2/settings.xml`.** This mirrors the working configuration on the existing release machine. Two things matter: the `central-publishing-maven-plugin` uses `publishingServerId=central`, so the `<server>` id must be `central` (username/password are the Central Portal **user token** pair from the shared step above); and an `ossrh` profile activated by default points the gpg plugin at the `gpg` executable. The key passphrase is deliberately **not** stored - `maven-gpg-plugin` prompts for it on the console at deploy time:
   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>central-portal-token-username</username>
         <password>central-portal-token-password</password>
       </server>
     </servers>
     <profiles>
       <profile>
         <id>ossrh</id>
         <activation><activeByDefault>true</activeByDefault></activation>
         <properties>
           <gpg.executable>gpg</gpg.executable>
         </properties>
       </profile>
     </profiles>
     <!-- Lets `mvn tia:...` resolve the plugin prefix without the full groupId. -->
     <pluginGroups>
       <pluginGroup>org.tiatesting</pluginGroup>
     </pluginGroups>
   </settings>
   ```
   The existing release machine also carries a `maven-https` profile pinning `central` to `https://repo1.maven.org/maven2` for *dependency resolution* - that's unrelated to signing/publishing and only needed on machines that still default to an insecure central mirror, so it's omitted here.

   For a **non-interactive** run (CI, or to avoid the prompt), add `<gpg.passphrase>your-key-passphrase</gpg.passphrase>` to that `ossrh` profile instead of typing it - the loopback signer then reads it from the property. Keep it out of any committed file.

4. **Run the release.** Signing is gated behind the `release` profile (which flips `gpg.skip` from `true` to `false`), mirroring the Gradle `-Prelease` opt-in. `maven-gpg-plugin` prompts for the passphrase unless you stored `gpg.passphrase`:
   ```
   mvn deploy -Prelease
   ```

### Platform notes: macOS (pinentry-mac + Keychain)

macOS is the smoothest target because `pinentry-mac` persists the passphrase in the login Keychain.

1. **Install GnuPG and pinentry-mac:**
   ```
   brew install gnupg pinentry-mac
   ```
2. **Point gpg-agent at pinentry-mac.** In `~/.gnupg/gpg-agent.conf` (path from `which pinentry-mac` - Apple Silicon is `/opt/homebrew/...`, Intel is `/usr/local/...`):
   ```
   pinentry-program /usr/local/bin/pinentry-mac
   ```
   Then reload: `gpgconf --kill gpg-agent`.
3. **Prime the Keychain.** Sign anything once and tick **"Save in Keychain"** in the dialog:
   ```
   echo "test" | gpg --clearsign
   ```
   From now on gpg-agent pulls the passphrase from the Keychain automatically - `publishToCentral -Prelease` and `mvn deploy -Prelease` sign with no prompt, and nothing is stored in any build file.

**Troubleshooting `pinentry-mac` (the trap this project actually hit):** Homebrew upgrades `libassuan` independently of `pinentry-mac`. When `libassuan` jumped from 2.x to 3.x it bumped the library soname (`libassuan.0.dylib` → `libassuan.9.dylib`), so an already-installed `pinentry-mac` built against the old soname dies at launch with:
```
dyld: Library not loaded: /usr/local/opt/libassuan/lib/libassuan.0.dylib
```
The fix is to rebuild `pinentry-mac` against the new `libassuan` - but that rebuild compiles a `.nib` with `ibtool`, which only ships with **full Xcode**, not the Command Line Tools. If `brew reinstall pinentry-mac` fails with `xcode-select: error: tool 'ibtool' requires Xcode`, and you have `/Applications/Xcode.app` installed, point the toolchain at it for the rebuild, then switch back:
```
sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer
brew reinstall pinentry-mac
sudo xcode-select --switch /Library/Developer/CommandLineTools
gpgconf --kill gpg-agent
```
(If you don't have Xcode, install it from the App Store first - Xcode 14.2 is the last version for macOS 12 Monterey.) Verify the rebuilt binary loads with `echo BYE | pinentry-mac` - it should answer `OK Pleased to meet you` rather than a `dyld` error. This is worth knowing because signing can break with *no code change* - just a routine `brew upgrade` bumping `libassuan`.

### Platform notes: Windows 11 (Gpg4win, no plaintext passphrase)

Windows has no Keychain-writing pinentry, but you can still keep the passphrase out of any file: enter it once into the GUI pinentry and let gpg-agent hold it in memory.

1. **Install Gpg4win** - it bundles `gpg`, `gpg-agent`, the GUI pinentry (`pinentry-w32`), and Kleopatra (a key manager):
   ```
   winget install GnuPG.Gpg4win
   ```
   (or the installer from gpg4win.org). Default install path is `C:\Program Files (x86)\GnuPG\bin`.
2. **Import or generate your key** - via Kleopatra's GUI, or `gpg --import your-secret-key.asc` / `gpg --full-generate-key` in PowerShell. Confirm with `gpg --list-secret-keys --keyid-format long`.
3. **Keep the passphrase in memory, not on disk.** Gpg4win already uses `pinentry-w32` (a GUI prompt) by default, so no `pinentry-program` line is needed. To avoid frequent re-prompts, raise the agent cache TTLs in `%APPDATA%\gnupg\gpg-agent.conf` (create it if absent):
   ```
   default-cache-ttl 34560000
   max-cache-ttl 34560000
   ```
   (seconds - ~400 days). Reload with `gpgconf --kill gpg-agent`. The passphrase now lives only in the agent process memory; it is **never written to a plaintext file**. The one difference from macOS: the cache is cleared when the agent stops (reboot or logout), so you re-enter it once per session rather than never. There is no standard pinentry that persists to Windows Credential Manager, so in-memory caching is the no-plaintext option.
4. **Point Gradle at gpg** in `%USERPROFILE%\.gradle\gradle.properties`. Use forward slashes or escaped backslashes (`\` is an escape char in `.properties` files); if `bin` is on `PATH`, just `gpg`:
   ```
   signing.gnupg.executable=C:/Program Files (x86)/GnuPG/bin/gpg.exe
   signing.gnupg.keyName=YOUR_KEY_ID
   ossrhUsername=central-portal-token-username
   ossrhPassword=central-portal-token-password
   ```
5. **Test:**
   ```
   echo test | gpg --clearsign
   gradlew.bat :tia-core:publishToCentral -Prelease
   ```
   The first sign pops the `pinentry-w32` dialog; later signs in the same session are silent from the cache.

For the **Maven** side on Windows the same `gpg` install serves - but note the poms force `--pinentry-mode loopback`, which bypasses the GUI pinentry, so `mvn deploy -Prelease` prompts for the passphrase on the console instead of using the cached agent entry (or set `gpg.passphrase` in the `ossrh` profile for a non-interactive run, accepting that it then sits in `settings.xml`).

### Running the whole release from IntelliJ

The repo ships IntelliJ run configurations under `.idea/runConfigurations/` so a release is one click rather than a sequence of remembered commands. The orchestrator is **`Deploy all Tia modules`**: it is a Maven `deploy` configuration (run with the `release` profile active) whose before-launch task chain runs, in order:

1. `Gradle: publishToCentral -Prelease` — builds, signs (in-memory key), bundles, and uploads every Gradle module. This task cleans each subproject first (see below), so there is no separate clean step in the chain.
2. The five Maven `deploy` configs (`Maven deploy: tia-maven-plugin` and the four wrapper plugins), each with the `release` profile active so `gpg.skip` flips to `false` and the artifacts get signed and pushed through the `central-publishing-maven-plugin`.

So both build tools' release paths run from a single invocation. There are matching `Maven install: *` and `Install all Tia modules` configs for the non-publishing local-install equivalent. These configs only encode *which tasks run with which flags*; they still rely on the per-tool credentials from the sections above (the Gradle properties in `~/.gradle/gradle.properties` and the Maven `settings.xml` `central` server plus `ossrh` gpg profile), so a fresh machine must complete that setup before the one-click deploy works. Note the Maven passphrase is entered interactively during the run unless you stored `gpg.passphrase`.

`publishToCentral` cleans before it builds. `build/repos/releases` is a maven-publish repo layout that accumulates artifacts across versions - a bumped release leaves the previous version's files in place - and `packageDistribution` zips that whole directory for upload. Without a clean, a stale version would be bundled into the release. The task therefore declares `dependsOn 'clean'`, backed by a `mustRunAfter tasks.named('clean')` guard on every other task in the subproject: `dependsOn` alone does not order clean relative to the build tasks, so the guard is what guarantees clean runs before anything is rebuilt rather than deleting freshly built output mid-graph. It is inert for builds that don't invoke clean, so ordinary incremental builds are unaffected.

### Verifying the setup

- **Confirm the key is present and published:** `gpg --list-secret-keys --keyid-format long` shows the key; a fresh clone of the public key from the keyserver (`gpg --keyserver keyserver.ubuntu.com --recv-keys YOUR_KEY_ID`) confirms it propagated.
- **Gradle dry check:** `./gradlew packageDistribution -Prelease` builds and signs the artifacts into `build/repos/releases` and zips them without uploading, so you can confirm `.asc` signature files are produced before hitting the Portal. The first run triggers the pinentry prompt; if it signs silently, the agent/Keychain already holds the passphrase.

### Troubleshooting

- **macOS `xcode-select: error: tool 'ibtool' requires Xcode ... make: *** [Main.nib] Error`** — `brew` is *building* `pinentry-mac` from source (usually because it's upgrading it against a new `libassuan`), and the `.nib` compile needs full Xcode. See the macOS Platform notes above: switch the toolchain to `/Applications/Xcode.app`, `brew reinstall pinentry-mac`, switch back.
- **macOS `dyld: Library not loaded: .../libassuan.0.dylib`** — an installed `pinentry-mac` is linked against an old `libassuan` soname that a `brew upgrade` has since replaced. Rebuild it (macOS Platform notes). Signing can break this way with no code change.
- **Gradle `Cannot perform signing task ... has no configured signatory`** — `useGpgCmd()` couldn't find a key. Check `signing.gnupg.keyName` is set in `~/.gradle/gradle.properties` and matches a key in `gpg --list-secret-keys`, and that `signing.gnupg.executable` resolves to your `gpg` (full `gpg.exe` path on Windows).
- **Maven `gpg: signing failed: No pinentry` or a hang waiting for input** — `allow-loopback-pinentry` isn't set (or the agent wasn't reloaded), so the pom's loopback mode can't collect the passphrase. Confirm the `gpg-agent.conf` line and run `gpgconf --kill gpg-agent`. If you opted into the non-interactive `gpg.passphrase` property, check it's inside the default-active `ossrh` profile and spelled correctly.
- **`401 Unauthorized` from the Portal** — the `ossrh*` / `central` credentials are your *account* login rather than a generated **user token**. Regenerate a token under Account and use that pair.

---

Prev: [Static test selection](static-test-selection.md) | [Back to the Wiki index](../WIKI.md)
