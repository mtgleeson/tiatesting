# Database credentials: the channels, and why the fork gets a reference

Tia's build JVM and its forked test JVM both connect to the database. The build JVM runs
`select-tests`, `history` and the distributed planning goals; the fork persists the mapping and the
history row at the end of the run. So whatever supplies the password has to satisfy both, and the
fork sits on the other side of a process boundary.

The whole design turns on one constraint, which is not obvious and is the reason the rest of this
chapter exists.

## The constraint: a system property in the fork is a published artifact

The Tia agent's `premain` calls `ForkSystemProperties.applyToSystemProperties`, which republishes
**every** key of `fork.properties` as a system property in the forked test JVM. Maven Surefire then
writes the fork's system properties into every `target/surefire-reports/TEST-*.xml`:

```xml
<property name="tiaDBPassword" value="hunter2"/>
```

That file is the artifact Jenkins, GitLab, GitHub Actions and Bamboo all ingest and routinely
publish. It is a worse exposure than the build directory, which at least requires someone to archive
it deliberately.

The timing is counter-intuitive and each case was verified rather than inferred:

| Property set via | In the report XML? |
|---|---|
| `-javaagent` premain (Tia's mechanism) | **yes** |
| Surefire `<systemPropertyVariables>` | **yes** |
| `@BeforeClass` or test code | no - Surefire snapshots after fork start, before tests run |
| Surefire `<environmentVariables>` | **no** |

Gradle's own JUnit XML is clean - it writes an empty `<properties/>` block - but Gradle turns
`Test.systemProperty` into a `-D` on the worker's command line, readable from the process table by
any local user. `Test.environment` avoids that.

**The rule both halves produce: the password must never become a system property in the fork.**
Forward a *reference* - a file path, or a value in the environment - never the value itself.

## The four channels

Precedence is identical on both build tools:

```
tiaDBPassword / dbPassword  >  tiaDBServerId (Maven)  >  tiaDBPasswordFile / dbPasswordFile  >  TIA_DB_PASSWORD  >  empty
```

| Channel | Where the secret lives | What Tia writes to disk |
|---|---|---|
| `tiaDBPassword` / `dbPassword` | your build config | Maven: a staged file. Gradle: nothing |
| `tiaDBServerId` (Maven only) | `~/.m2/settings.xml`, optionally encrypted | a staged file |
| `tiaDBPasswordFile` / `dbPasswordFile` | a file you own | **nothing** |
| `TIA_DB_PASSWORD` | the environment | **nothing** |

Resolution happens once, in the build JVM, in `CredentialResolver` and the two plugins' resolvers.
It is deliberately not H2-specific: it is called by `DataStoreFactory.fromConfig` *before* that
method branches on the dialect, because while the fallback lived inside `H2ConnectionSettings` a
Postgres build silently ignored the environment variables and was pushed onto the one channel that
leaked. See [Embedded vs server-mode H2 connections](h2-connection-modes.md).

## Why the transports differ between Maven and Gradle

Gradle can set the fork's environment, so it forwards the resolved value with
`testTask.environment(...)` and writes nothing to disk.

Maven cannot. Surefire `<environmentVariables>` is the one clean channel, and a plugin cannot inject
it: Maven does not re-read plugin configuration mutated at runtime. An earlier attempt is still
recorded in a commented-out block in `AbstractTiaAgentMojo`. That leaves Maven with `argLine` (which
is the leak) or a file, so Maven stages the password in a file and forwards the path.

A staged file is created owner-only, as a creation attribute on POSIX so it is never briefly
world-readable; it is placed outside the build directory, so archiving `target/` never captures it;
and it is registered for deletion when the build JVM exits, which works because the Maven JVM
outlives every Surefire fork. Nothing is staged when the password came from a file you own (the path
is forwarded as-is) or from the environment (nothing is forwarded, since the fork inherits it).

This asymmetry is invisible in your build files: the configuration surface is the same on both
tools, apart from `tiaDBServerId`, which exists only because `settings.xml` does.

**Encrypting the staged file was considered and rejected.** The fork has to decrypt it, so the key
would have to travel by one of the same channels and would end up beside the ciphertext under the
same permissions, captured by the same `tar target/`. It would deter a casual `cat` and nothing
more, at the cost of real complexity and a false sense of security. Owner-only permissions, a
location outside the build directory, and deletion on exit address the exposures that actually
exist.

## The null-vs-empty rule crosses the fork boundary

`null` means "not configured" and falls through to the next channel. `""` means "this database has
no password", is used verbatim, is never trimmed, and bypasses the environment fallback. H2 server
mode's default account is exactly this: user `tia`, empty password.

Both plugins therefore key their forwarding off **whether a password was configured at all**, not
off the resolved value being non-empty. Maven stages and forwards a file even for an explicitly
empty password; Gradle emits the worker environment entry even when the value is `""`. Forwarding
nothing there would let a `TIA_DB_PASSWORD` that happened to be set in the surrounding environment
win inside the fork while the build JVM used the empty value - and the two would connect as
different users, which is the kind of failure that shows up as an inexplicable permission error
halfway through a run.

For the same reason there is **no** "fail if a shared database has no password" precondition. An
empty password is legitimate for H2 server mode and for Postgres `trust` auth, so there is no honest
way to tell a missing password from an intended one.

## Two guards worth knowing about

**`ForkSystemProperties.write` refuses credential-shaped keys.** Any name containing `password`,
`passwd`, `secret`, `token` or `credential`, unless it ends in `File`, throws. This makes the rule
structural rather than conventional, because the failure it prevents is invisible: a credential
added to that file in future would be republished into the report XML with nothing failing or
warning. That is exactly how the original leak survived.

**An unresolved Maven expression fails the build.** Maven leaves an unresolvable `${...}` in place
rather than erroring, so `<tiaDBPassword>${env.TIA_DB_PASSWORD}</tiaDBPassword>` on a machine where
the variable is unset hands Tia the literal string and the build succeeds with it as the password.
Tia rejects that and says why. A password merely *containing* a dollar sign is not rejected.

**A `settings.xml` decryption failure fails the build.** `SettingsDecrypter` reports a failure only
through `getProblems()` and hands back the server with its password still encrypted, so ignoring the
problems would send `{pazwCbxc...=}` to the database. The message names the server id and points at
`settings-security.xml`, and deliberately does not echo the stored value.

## Related

- [Keeping the password out of checked-in config](../README.md#keeping-the-password-out-of-checked-in-config) - the user-facing setup, including the two indirections that do not work as they appear to
- [Embedded vs server-mode H2 connections](h2-connection-modes.md) - where credential resolution used to live
- [How Tia exchanges data with the test runner](test-runner-data-exchange.md) - the `fork.properties` mechanism this chapter constrains
- [Distributed test runs](distributed-test-runs.md) - the security note on `fork.properties` and the completion step's separate configuration
