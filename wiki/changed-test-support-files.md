# Changed test support files

Test dirs hold more than test suites: base classes every suite extends, helpers and fixtures, API
clients and test data builders. A change to one of these can break, or fix, every suite that uses
it. The coverage mapping can't see that, since it only records which source methods each suite
runs, and test code is never in it. So Tia finds the suites that use a changed test support file
from the test sources, and selects them.

### Which changed files count

A changed `.java` or `.groovy` file under a configured test dir counts as a test support file
when it isn't a tracked suite:

- **Modified** - a helper, base class or fixture. A real test Tia hasn't tracked yet counts too;
  it runs anyway, since only tracked suites are ever ignored.
- **Added** - only when its compiled class proves it isn't a test (an interface, an abstract class,
  or a class with no test methods). A new abstract base class can be added in the same change as
  the existing suites that start using it. Any other new file is selected as a new suite.
- **Deleted** - by its old class name. The suites that used it still refer to it in their sources
  until they are changed too, and if they no longer compile the build fails anyway.

A modified tracked suite is selected as a modified test file, as before, and doesn't count.

### How a dependency is found

`TestSourceDependencyIndex` reads every `.java` and `.groovy` file under the test dirs. It reads the
sources rather than the compiled classes because selection often runs before the tests are compiled:
Maven selects in `prepare-agent`, bound to `initialize`, and a distributed plan step may run on its
own. The sources are always there.

Each file counts as its top-level class, named from its path under the test dir. A file refers to a
test class `p.X` when it:

- imports `p.X`, a nested class or static member of it (`import p.X.Builder`,
  `import static p.X.anOrder`), or `p.X.*`;
- is in package `p`, or imports `p.*`, and has `X` as a whole word anywhere in its text;
- has the qualified name `p.X` anywhere in its text.

The index maps each test class to the classes that refer to it. From each changed support class,
Tia walks those references breadth first, so the walk is transitive: suite `OrderTest` extends
`BaseTest`, and `BaseTest` uses `OrderApi`, so a change to `OrderApi` selects `OrderTest`. Every
tracked suite reached is selected. Nested classes fold into their top-level class, so a tracked
`@Nested` suite `OrderTest$WhenPaid` is selected when `OrderTest` is reached, including when
`OrderTest` is the changed file itself.

### What is logged

```
[INFO] Selected 42 test(s) to run from changed test support files: [com.example.orders.OrderTest, ...]
[DEBUG] Changed test support file com.example.orders.OrderTestHelper is used by 42 suite(s)
[INFO] Changed test file(s) not used by any tracked suite: [com.example.util.UnusedHelper]
```

The INFO selection line lists a sample of the suites, since a base class change can select most of
them. The last line names every changed support file no tracked suite uses, so no changed test file
is dropped silently.

The number of suites selected this way is recorded on the run's history row as
`num_test_support_dependants` and shown as "Test support files" in the run's selection breakdown.
See [Run history details](run-history-details.md).

### Cost

The test sources are only read when at least one changed test file is a support file. Builds that
change only suites or source code never read them.

When they are read, the cost grows with the size of the test tree. Measured in a fresh JVM, as
selection runs:

| Test tree | Index build | Walk |
| --- | --- | --- |
| 562 files, about 5 MB (this repo) | 282 ms (about 70 ms once warm) | under 1 ms |
| 5,000 files of about 240 lines each, 98 MB on disk (synthetic) | about 0.75 s | 8 ms |

### Limitations

- **Comments and strings aren't stripped**, so a class named only in a comment, or a same-package
  class whose simple name appears as a word, counts as a dependency. This can only select extra
  suites, never miss one.
- **A second top-level class in a file** isn't known by its own name, so references to it aren't
  followed.
- **A class's package comes from its path**, not its `package` line. They only differ in unusual
  Groovy layouts.
- **A changed tracked suite that other suites extend** selects only itself. Following it would mean
  reading the test sources on every build that changes a suite.
- **Test support code outside the configured test dirs**, such as a shared test library jar, isn't
  tracked.
