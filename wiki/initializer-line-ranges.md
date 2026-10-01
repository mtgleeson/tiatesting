# Constructor and static initializer line ranges

Tia maps a source diff onto tracked methods by line number: each method in `tia_source_method` has
the first and last line JaCoCo reports for it, and `MethodImpactAnalyzer` selects every method whose
range (padded by one line either side, for the signature and closing brace) overlaps a changed
hunk. That works for ordinary methods, whose lines are contiguous. It does not work for
constructors (`<init>`) and static initializers (`<clinit>`), and this chapter covers why and what
Tia records for them instead.

### The problem: field initializers are compiled into the constructor

The Java compiler folds every instance field initializer (and instance initializer block) into
each constructor, and every static field initializer (and `static {}` block) into `<clinit>`.
JaCoCo's first and last line for the method are therefore the lowest and highest of those lines,
wherever they sit in the file. A field declared after other methods stretches the range over them:

```java
public class CarService {
    private int b = 1;                 // line 8

    public CarService() {              // line 11
        System.out.println("ctor");
    }

    public void checkBrakes() { ... }  // lines 35-38
    private void temp4() { ... }       // lines 71-73

    private int e = 5;                 // line 74
}
```

`CarService.<init>()V` is recorded as lines 8-74. Every edit in the file - to `checkBrakes`, to
`temp4`, to anything - lands inside that range, so the constructor is always impacted and every
suite that constructs a `CarService` is selected. "Pick the narrowest matching range" is not enough
on its own: an edit to a method no test covers (`temp4`) has only the constructor as a candidate,
and lambdas legitimately nest inside their enclosing method's range.

### What Tia records: exact line ranges

When coverage is collected, `JacocoClient.collectMethodsCalled` computes exact line ranges for each
`<init>` and `<clinit>` (`InitializerLineRanges`). JaCoCo analyses the whole class, including methods
no test executed, so every member's line range is available:

1. Start from the initializer's range padded by one line either side (lines 7-75 above).
2. Remove the signature line (the line before its first code line) and the body of every
   non-initializer method in the class, and of every method of the other classes compiled from the
   same source file (nested, inner and anonymous classes). The line after a method's last code line
   is deliberately kept: for a void method that is the blank line after its closing brace, and with
   one blank line between members it is the only line a new field inserted between two methods can
   be matched against.
3. Remove the lines another constructor of the same class has code on that this one does not - the
   other constructor's body. Field initializer lines, which every constructor shares, stay. For
   `<clinit>` this removes the constructors' lines, and vice versa.
4. Add back every line the initializer itself has code on, so a member on the same line as a field
   initializer (e.g. a lambda in the initializer) can't remove it.

For the full fixture the constructor becomes `7-16,20-21,25,34,39,43-44,49,53,57,66,70,74-75`: its
own body, the field lines, and the blank lines between members (where a new field could be added).

If no line between the initializer's own first and last line was removed - nothing else sits inside
it, the common case - no ranges are recorded and the method keeps plain padded start-end matching,
exactly as before. A neighbour that only clips the one-line padding (e.g. a method signature directly
below an implicit constructor on the class declaration line) doesn't count.

The ranges ride on `MethodImpactTracker.getLineRanges()` as flat inclusive `[start, end, ...]`
pairs, and are stored in the nullable `line_ranges` column of `tia_source_method` and
`tia_distributed_run_method_stage` in the text form `start-end,line,start-end` (`LineRanges`; a
one-line range is written as just its line number). The column is null for every ordinary method
and for every initializer that isn't split, so it is only populated for classes that declare a
field (or initializer block) after another member.

### Performance

- **Coverage collection** (test JVM, every suite): for each initializer of an executed class, one pass
  over the class's methods and the lines in its range - roughly initializers x (methods + lines), so a
  class with many constructors costs constructors squared x lines. JaCoCo's per-line lookup is an
  array read. This is small next to JaCoCo decoding the class bytes, but it is repeated for the same
  class in every suite.
- **`select-tests` read path**: one extra, mostly-null column in the changed-files query; ranges are
  parsed only for the split initializers of changed files, and matching adds a null check per method.
- **Seal**: each catalogue row's insert gains `, NULL` or a short range string.
- **Migration**: one `ADD COLUMN IF NOT EXISTS` per datastore instance (memoized). On Postgres this
  takes a table lock even when the column exists, so it can briefly queue behind a concurrent seal
  rewriting `tia_source_method` - the same behaviour as the existing `tia_test_suite` and `tia_core`
  migrations.

### How a diff is matched

`MethodImpactAnalyzer.findTrackedMethodsForSourceDiff` matches a method that has line ranges against
those ranges instead of its start-end range, with no extra padding (the ranges already include it).
`MethodImpactTracker.getMatchedLineRanges()` is the single definition of the lines a change must
touch: the stored ranges when present, otherwise start-end widened by one line either side. The
HTML method detail page shows it as "Lines matched for changes" next to the first and last code
line, so the one-line allowance is visible for every method, not just split initializers.
A pure insertion hunk is written by java-diff-utils as `-N,0`, meaning the new lines go before
original line N, so it touches a range that contains either neighbour, N-1 or N. That is what makes
a new field added directly after the last field still count as a constructor change.

With the example above:

| Change                                   | Impacted                    |
|------------------------------------------|-----------------------------|
| Edit inside `checkBrakes` (tracked)      | `checkBrakes` only          |
| Edit inside `temp4` (not tracked)        | nothing                     |
| Edit `private int e = 5;`                | the constructor             |
| New field added between two methods      | the constructor             |
| Rename `checkBrakes` (signature line)    | `checkBrakes` only          |
| Comment edited between two methods       | the constructor (harmless over-selection, as before) |

### Limitations

- **Existing rows.** A catalogue written before the column existed reads back with no ranges and
  keeps start-end matching until the next mapping update rewrites `tia_source_method`.
- **Nested classes a suite didn't execute.** Coverage is analysed only for the classes a suite
  executed (see the scoped analysis in `JacocoClient.analyze`), so a nested class that the suite
  didn't touch isn't removed from the outer constructor's range. That errs towards selecting more
  tests, and the stored ranges can differ depending on which suite's tracker ends up in the
  catalogue.
- **Members with no blank line between them.** When a method's closing brace is directly followed by
  the next method's signature, there is no gap line left between them, so a new field inserted there
  isn't matched to the initializer (the neighbouring method is selected instead). Line numbers alone
  can't tell "between two methods" from "inside one" here. Standard Java style puts a blank line
  between members.
- **Closing brace of a value-returning method.** Its last code line is the `return`, so the closing
  brace line after it stays in the initializer's ranges. A hunk touching only that brace also
  selects the initializer - a rare, safe over-selection.
- **Fields above the first initializer line.** A new field added more than one line above the
  constructor's first line falls outside its range, exactly as it did before this change.
