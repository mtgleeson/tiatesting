# Matching a diff to tracked methods

Dynamic test selection turns a source diff into a set of impacted methods, then selects every suite
whose coverage includes one of them. This chapter covers the first step: which line numbers Tia
stores for a method, which line numbers it reads from the diff, and how the two are compared. The
code is `MethodImpactAnalyzer`, called from `FileImpactAnalyzer.getMethodsForFilesChanged` for each
changed source file. Only `.java` and `.groovy` files are diffed (the VCS providers filter on those
extensions), so the line heuristics below only need to handle Java and Groovy syntax.

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
the file at the workspace's base revision (Git `HEAD`, Perforce `#have`); see
[Local changes and a workspace behind the mapping](local-changes-behind-mapping.md) for what happens
when that base is older than the stored commit. `MethodImpactAnalyzer` diffs them with java-diff-utils, with no context lines, and reads
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

1. Over blank and comment-only lines (`//`, `/*`, `*` - shared by Java and Groovy),
   stopping at and including the first line with anything else on it - normally the signature.
   A statement block opener with no code on its own line - `try {`, `do {`, or a `while`/`for` loop
   such as `while (true) {` - is the exception: when the method body starts with one, JaCoCo's first
   code line is inside the block, so the walk lands on the opener. It is stepped over and the walk
   repeated. With the brace on its own line (Allman style), the lone `{` is taken together with the
   keyword line above it, so `try` then `{` is stepped over too, while a signature then `{` is not.
   No declaration line starts with those keywords, so this never skips past a signature.
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

### Class-level declaration changes

Some lines change a whole class rather than one method, and no method's line range covers them:

- the **class header** - class annotations (`@Transactional`, `@RequestMapping("/v1")`, `@Profile`),
  the declaration line, and a multi-line `extends`/`implements` clause;
- **field declarations** - a field's annotations (`@Value("${timeout}")`, `@Qualifier`,
  `@Column(length = 50)`) and, for a field with no initializer, its declaration line (type and
  modifiers). A field with no initializer has no code, so no method covers it.

`ClassDeclarationScanner` finds these lines in the original file and `MethodImpactAnalyzer`
impacts **every tracked method of the class** when a hunk touches them. Each impacted method becomes
its own source-method trigger, so these changes show up in the run history (and each method's
"runs triggered" count) like any method change.

The scanner is one pass over the file that tracks brace depth, skipping braces inside comments,
string and character literals and text blocks, and keeps a stack of the enclosing classes. That lets
it attribute a nested class's lines to it: the scanner keys classes by their nesting path in the file
(`Outer$Inner`), which is the tracked method name's class part without the package. Local and
anonymous classes aren't attributed. At class-body level each member runs from its first code line
(annotations included, Javadoc excluded) to the `;` or `{` that ends it:

| Member ends with | Example | Class-level lines |
|------------------|---------|-------------------|
| `{` after a `class`/`interface`/`enum`/`record`/`@interface`/`trait` declaration | `@Service public class Cars extends Base {` | all of it (the header) |
| `;` with no initializer | `@Value("${limit}") private int limit;` | all of it |
| `;` with a constant initializer, `static final` or in an interface | `static final int MAX = 5;` | all of it |
| `;` or `{` with any other initializer | `@Inject private Clock clock = Clock.systemUTC();` | only the lines above the `=` line (its annotations) |
| `;` after a parameter list | `abstract void process(Order o);` | none - implementers change with it |
| `{` with no initializer | a method, constructor or initializer block | none - matched to the method itself |
| an enum constant list without arguments | `FAST, SLOW;` | all of it |

An initializer compiles into the constructor or static initializer, whose line ranges already match
it, so treating it as class-level too would select the whole class for every initializer edit. The
exception is a constant: a `static final` field built from literals and other constants (and such an
interface field, being implicitly `static final`) is inlined by the compiler and has no initializer
code, so without this an edit to it would match nothing. An initializer that makes a call, uses `new`
or opens a block (`LoggerFactory.getLogger(...)`, `new HashMap<>()`, `{2, 3, 5}`) can't be inlined
and compiles into the static initializer, so it is treated like any other initialized field.

A hunk that changes or removes original lines counts when it overlaps the class-level lines. A pure
insertion (`-N,0`) counts only when line N is a class-level line and the inserted lines continue its
declaration rather than add a member: they must hold code, and every code line must be a declaration
line (an annotation or modifier - nothing ending with `;` or a brace). So an annotation added above a
field or the class counts, while a new field or method added directly above an existing one doesn't:
it can't change behaviour until something uses it, and that change is matched on its own. A comment
added above a field holds no code and doesn't count either.

The scan costs about 1.7 ms for a 5,000-line file, once per changed file that has tracked methods,
on content already in memory.

### Over-selection

The widening heuristics only ever widen, so their failure mode is selecting extra tests:

- For a method whose first code line sits on its signature line (a one-line method, or a constructor
  whose implicit `super()` call is attributed to its signature), walk 1 starts above the method and
  can cross its Javadoc and blank lines to the previous member, so a Javadoc edit selects it.
- In Groovy, where semicolons are optional, a property declared directly above a method
  with no blank line between them is pulled into the method's range.
- A `//` inside a string literal is treated as a comment start, which can only leave a line looking
  unterminated.
- An implicit default constructor (placed on the class declaration line) or a class annotated on its
  declaration line can pull the class annotations into the first member's range.
- Code inserted directly above a method's declaration (a pure insertion before its first line) counts
  as a change to it, as it always has for the signature line.

### Limitations

- **Class-level changes select the whole class.** Every tracked method of the class is impacted,
  even when an annotation only matters to some of them. An abstract or interface method declaration
  ends with `;`, so changing one impacts the class's tracked (default or concrete) methods.
- **Groovy fields without a semicolon** run into the next member, so a field declared directly above a
  method isn't recognised as class-level (and one with an initializer pulls the method's signature in).
- **Constants used from other classes** are inlined into those classes too; only the declaring class's
  methods are impacted.
- **A constant initializer with parentheses** (e.g. a cast, `(long) 5`) looks like a call, so the
  constant is treated as an initialized field and only its annotation lines are class-level.
- **Anonymous and Groovy closure classes** (`Outer$1`, `Outer$_closure1`) aren't impacted by their
  enclosing class's class-level changes; their own lines are still matched as methods.
- **New members aren't class-level.** A new field or method only matters once something uses it, and
  that change is matched on its own.
- **Block comment lines without a leading `*`** stop the blank/comment walk, so code added above them
  isn't matched - the same as without the widening.
- **Lambdas.** A lambda compiles to its own synthetic method whose lines sit inside its enclosing
  method; widening its begin can reach the enclosing method's earlier statements, a harmless
  over-selection.
- **Reports.** The HTML method detail page's "Lines matched for changes" shows the padded range: the
  report doesn't read the source, so the begin widening isn't reflected there.
