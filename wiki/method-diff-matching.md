# Matching a diff to tracked methods

Dynamic test selection turns a source diff into a set of impacted methods, then selects every suite
whose coverage includes one of them. This chapter covers the first step: which line numbers Tia
stores for a method, which line numbers it reads from the diff, and how the two are compared. The
code is `MethodImpactAnalyzer`, called from `FileImpactAnalyzer.getMethodsForFilesChanged` for each
changed source file.

### What is stored

Each method in `tia_source_method` has `line_number_start` and `line_number_end`: the first and last
line JaCoCo reports for it. JaCoCo, like any bytecode library, reads them from the class file's
`LineNumberTable`, which only maps executable instructions to lines. So the stored start is the
method's **first line with code**, not its signature, and lines with no instructions - the signature,
annotations, blank and comment lines - never appear in it. The end is the last line with code; for a
`void` method that is the closing brace, where the implicit `return` sits.

The lines are in the coordinates of the commit the mapping was sealed at, and only a mapping run
refreshes them (see [Persist flow and crash safety](persist-flow-and-crash-safety.md) for why the
catalogue can never be ahead of the stored commit). Constructors and static initializers whose range
is split by other members also store exact line ranges - see
[Constructor and static initializer line ranges](initializer-line-ranges.md).

### What is read from the diff

For each changed file the VCS provider supplies the original content and the new content. For
committed changes the original is the file at the stored commit; for local workspace changes it is
the file at the workspace's base revision (Git `HEAD`). `MethodImpactAnalyzer` diffs them with java-diff-utils, with no context lines, and reads
only the **original-side** line numbers of each hunk (`-N,M`), so they share the stored lines'
coordinate space. A hunk covers original lines N to N+M-1. A pure insertion (`-N,0`) puts its lines
before original line N; start-end matching treats it as line N.

### The matched range

A method with exact line ranges is matched against those (see the initializer chapter). Every other
method is matched against a range built from its stored start and end:

1. **Padding** (`MethodImpactTracker.getMatchedLineRanges()`): one line either side,
   `[start - 1, end + 1]`, so the line before the first code line (normally the signature) and the
   line after the last (the closing brace of a value-returning method) count.
2. **Begin widening at diff time** (`MethodImpactAnalyzer.widenStartToDeclaration`): the begin is
   moved up to the top of the method's declaration, using the original file content the diff is
   already built from. See below.

A hunk impacts the method when it overlaps that range: its first or last line falls inside it, or
it spans the whole range.

### Widening the begin to the declaration

Without widening, these edits wouldn't impact the method:

```java
    /**
     * Finds a car.                         // Javadoc - not widened over
     */
    @Transactional(readOnly = true)         // line 6  <- widened begin
    public synchronized Car find(           // line 7
            long id) {                      // line 8
                                            // line 9
        // look up the cache first          // line 10 <- padded begin
        return cars.get(id);                // line 11 <- first code line
    }
```

- an annotation change (`@Transactional`, `@Cacheable`, ...) on line 6,
- a modifier change on line 7 (an earlier line of a multi-line signature),
- code added after line 8 or written over line 9.

The widening is two walks upwards from the padded begin:

1. Over blank and comment-only lines (`//`, `/*`, `*` - shared by Java, Kotlin, Groovy and Scala),
   stopping at and including the first line with anything else on it - normally the signature.
   A statement block opener with no code on its own line - `try {`, `do {`, or a `while`/`for` loop
   such as `while (true) {` - is the exception: when the method body starts with one, JaCoCo's first
   code line is inside the block, so the walk lands on the opener. It is stepped over and the walk
   repeated. No declaration line starts with those keywords, so this never skips past a signature.
2. Unless that line ends the previous member (it ends with `;` or `}`), on over the declaration
   lines directly above it: annotations, including multi-line ones, and earlier signature lines.
   It stops at a blank line, at a comment (so a Javadoc edit doesn't select the method), and at a
   line ending with `;` or `}` (the previous member) or `{` (e.g. the class declaration). A trailing
   `//` comment is ignored when checking a line's ending, and an annotation line ending with `{` (an
   array argument such as `@SuppressWarnings({`) still counts as part of the declaration.

Nothing is stored: the widened begin exists only while matching a diff, and `tia_source_method` keeps
JaCoCo's first code line. Matching at diff time keeps the walk in the same file version as the hunk
line numbers, and keeps source reading off the coverage-collection path. The cost is a few line checks
per tracked method of each changed file, on content already in memory.

### Over-selection

The widening heuristics only ever widen, so their failure mode is selecting extra tests:

- For a method whose first code line sits on its signature line (a one-line method, or a constructor
  whose implicit `super()` call is attributed to its signature), walk 1 starts above the method and
  can cross its Javadoc and blank lines to the previous member, so a Javadoc edit selects it.
- In Kotlin and Groovy, where semicolons are optional, a property declared directly above a method
  with no blank line between them is pulled into the method's range.
- A `//` inside a string literal is treated as a comment start, which can only leave a line looking
  unterminated.
- An implicit default constructor (placed on the class declaration line) or a class annotated on its
  declaration line can pull the class annotations into the first member's range.
- Code inserted directly above a method's declaration (a pure insertion before its first line) counts
  as a change to it, as it always has for the signature line.

### Limitations

- **Class-level annotations** apply to every method and aren't attributed to any one method.
- **Block comment lines without a leading `*`** stop the blank/comment walk, so code added above them
  isn't matched - the same as without the widening.
- **Lambdas.** A lambda compiles to its own synthetic method whose lines sit inside its enclosing
  method; widening its begin can reach the enclosing method's earlier statements, a harmless
  over-selection.
- **Reports.** The HTML method detail page's "Lines matched for changes" shows the padded range: the
  report doesn't read the source, so the begin widening isn't reflected there.
