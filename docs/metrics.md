# Using assay's metrics

The README defines each metric. This guide explains why each one matters,
how to read it, and what you can do when it flags something.

## Metrics are signals

A metric points at code worth a second look. It doesn't decide whether the
code is good. Before you change code to satisfy a threshold, read it and ask
whether the metric found a real problem. When a team chases the numbers
themselves, the code gets worse in ways the numbers can't see: a complex
function split into three tangled helpers has lower complexity and is
harder to read.

Some habits make the metrics useful:

- **Look at combinations.** One high number is often fine. A brick that is
  large, has low cohesion, and many dependents is a much stronger signal
  than any one of those alone.
- **Watch the trend.** With `--base`, assay shows what a change made worse.
  A function whose complexity went from 4 to 9 in one pull request deserves
  more attention than one that has sat at 11 for a year.
- **Compare bricks with their peers.** The `:std-devs` rule flags a brick
  that stands out from the others of its type, which suits metrics where
  no absolute limit makes sense, such as size.
- **Treat warnings as a to-do list.** Warnings never fail a run. Use them
  to pick refactoring work, and keep errors for limits you want enforced.

## Adopting assay in an existing workspace

An existing workspace usually breaks some rules on the first run. You don't
have to fix them all before you start:

1. Run `bb assay --warnings` and read the report to see where the workspace
   stands.
2. Adjust the thresholds in `assay.edn` to fit the code you consider
   acceptable. Make a rule a warning rather than turning it off when you
   still want to see it.
3. Run assay with `--base` in the Git hook and in CI, so that only new
   violations fail. The existing ones stay visible in the report.
4. Fix existing violations when you work in that code anyway, and tighten
   the thresholds as the workspace improves.

## Function metrics

Function metrics point at code that is hard to read, test, or change.
Assay reports each violation with the function's location, so you can go
straight to it.

### Complexity

Cyclomatic complexity counts the independent paths through a function.
Each path is a case a reader must keep in mind and a case a test must
cover, so a function with complexity 12 needs at least 12 tests to try
every path.

High complexity usually means a function makes several decisions that it
could make separately. To reduce it:

- Extract a predicate with a name for a compound condition, such as
  `(and (seq xs) (not (:disabled opts)) ...)`.
- Replace a long `cond` that picks behavior with a map lookup, a `case`, or
  a multimethod, so each case lives in its own place. A `case` counts as
  one decision however many clauses it has, because its tests are
  constants dispatched in one step.
- Validate inputs in one function and do the work in another, rather than
  checking at each step.
- Use `cond->`, `some->`, and `when-let` where they fit. They don't remove
  decisions, but they keep each one beside the step it guards.
- Give each arity its own job. Each extra arity adds a path, and an arity
  that does more than fill in defaults is often a separate function.

### Nesting depth

Deep nesting makes a reader track many open forms at once to know what a
value is and where it goes. It often hides a sequence of steps inside one
expression.

To flatten a function:

- Use `->`, `->>`, or `as->` for a chain of transformations.
- Name intermediate values in a `let`. Assay starts each binding value
  again at depth 1, so a `let` that names the steps of a computation
  reads as flat as it is.
- Extract a deeply nested anonymous function into a named one.

### Forms

Forms count the size of a function. A long function usually does several
things, and each one is harder to name, test, and reuse while it stays
inside the others.

Look for seams: a `let` whose bindings fall into groups, a comment that
introduces a step, or a block that uses only some of the parameters. Each
is a candidate for its own function. Large literal data, such as a lookup
table, may belong in a `def` of its own.

### Parameters

Every caller must pass positional parameters in the right order, and
nothing checks that two parameters of the same type aren't swapped. Each
added parameter also makes the function harder to call and to change.

To reduce them:

- Pass an options map for optional or rarely used parameters.
- Group parameters that travel together, such as a start and an end, into
  one map.
- Look at what the function does with them. Many parameters can mean the
  function does several jobs, each needing a few of them.

Interface functions matter most, since callers in other bricks depend on
the order. See connascence of position below.

## Brick metrics

Brick metrics describe a brick as a whole. Few of them have a right value,
so compare them with the brick's peers and with the brick's history.

### Size: files, forms, and functions

Size alone isn't a problem, but a large brick is more likely to hold
several responsibilities. When a brick grows well past its peers, look for
parts that don't use each other, which suggest where to split it. A
brick that grows suddenly, with `--base` showing how much, often means a
feature landed in the wrong place.

### Mean function complexity

The function rules report the brick's worst functions. The mean says more
about the brick: a high mean means complex code throughout, often because
the brick works at too low a level or handles many special cases. That can
mean its data needs a better shape, so that the cases go away rather than
move.

The report outlines a brick whose mean stands out from its peers, and a
`:std-devs` rule can flag one. A rising mean, which `--base` shows, means
a brick getting harder to work in over time.

### Mean nesting depth

A high mean nesting depth means the brick's functions tend to bury their
steps in nested expressions, rather than one function doing so. Treat it as
a habit to change across the brick, as described under nesting depth for
functions.

### Outliers

The report's brick tables mark any value that is 2 or more standard deviations
from the mean of all bricks, in either direction: the HTML report outlines
it, and the GitHub report sets it in bold. Afferent coupling, instability,
abstractness, and cohesion compare components only, since every base has
no interface and no dependents, and is glue by design. A marked value isn't a
violation. It shows where a brick differs from the rest of the workspace,
which is worth understanding: a brick much larger than the others, or with
far more dependents, may be doing more than its
share.

## Dependency metrics

Dependency metrics show how a change in one brick spreads to others. The
ideas come from Robert C. Martin's package principles.

### Afferent coupling

Afferent coupling (Ca) counts the bricks that depend on this one. Each is a
brick that a change to this brick's interface can break, so a brick with
high Ca carries responsibility. Change its interface with care: add new
functions rather than changing existing ones, and keep the implementation
free to change behind it.

### Efferent coupling

Efferent coupling (Ce) counts the interfaces this brick depends on. Each is
a reason this brick might have to change, and something a test might need
to set up. A component with high Ce may do too much, or may be coordinating
work that belongs in a base. Bases often have high Ce by design, since they
wire components together.

### Instability

Instability, `Ce / (Ca + Ce)`, combines the two. A brick near `0` has many
dependents and few dependencies: it's stable, because it's hard to change.
A brick near `1` depends on others and nothing depends on it: it's easy to
change. Nothing can depend on a base, so every base is at `1`, and the
dependencies table leaves bases out. The graph shows what each base
depends on.

The Stable Dependencies Principle says to depend in the direction of
stability, so that hard-to-change bricks never rely on easy-to-change
ones. Assay flags a brick that depends on a less stable brick, and draws
that edge in red. To fix one:

- Move the part that the stable brick needs into a new component that
  both bricks can depend on.
- Invert the dependency: have the stable brick accept a function or a
  protocol implementation from its caller rather than calling the
  unstable brick itself.
- Ask whether the stable brick should depend on it at all. Sometimes the
  call belongs in a base that wires the two together.

### Abstractness

Abstractness measures how much of a component sits behind its interface,
by counting definitions. A small interface over a large implementation
hides most of its code, so the implementation can change without affecting
other bricks.

Low abstractness means the interface is large compared with what it hides.
At `0.5`, each interface definition hides only one more, so the component
is a thin layer, a shallow module in John Ousterhout's terms. Either the
interface namespace holds implementation code, which belongs in an
implementation namespace, or the component exposes more than its callers
need, or it does too little to earn its own brick. Abstractness matters
most for stable components: a brick with many dependents and a large
interface is hard to change in any way.

### Merge candidates

Every brick costs something: an interface to keep, a place in the
dependency graph, and one more thing to name and find. A small component
that only one other component uses may not repay that cost, and could live
inside the component that uses it. Assay warns about a component whose only
dependent is another component at least four times its size, by default.

The warning asks a question rather than answering it. Merge the two when
the small component exists only to serve the larger one. Keep it separate
when it has a clear job of its own, when you expect other bricks to use it,
or when it isolates a library or a side effect, as assay's `git` component
does. A component that only a base uses doesn't count. That's the usual
shape of a Polylith workspace, and moving its code into the base would put
logic where Polylith says it doesn't belong.

### Co-change

Bricks that keep changing in the same commits depend on each other,
whatever their requires say. When one requires the other, that's expected.
When neither does, the source hides the coupling: the bricks share an
assumption, a data format, or a feature split between them, and a change
to one keeps needing a change to the other. Adam Tornhill calls this change coupling
in *Your Code as a Crime Scene*, and it measures what John Ousterhout
calls change amplification.

Assay reads the last 12 months of history by default and warns about a
pair with no dependency path between them that shares at least 5 commits,
covering at least half of the less changed brick's commits. It leaves out
commits that touch more than 5 bricks, since a reformat or a rename
across the workspace says nothing about coupling. The graph joins each
pair with a dotted amber line.

To fix it, find what the two bricks agree on. Make that knowledge one
brick's job, and have the other depend on it, so the coupling is visible
in the graph. When the two bricks are two halves of one responsibility,
consider merging them. Two report formats that change together because
they render the same report is the kind of pair to look for.

### New dependencies

With `--base`, the dependency graph dashes each dependency between bricks
that the base didn't have. A new dependency isn't wrong, so it isn't a
violation, but it changes the architecture, so it deserves a deliberate
decision in code review.

## Cohesion metrics

Cohesion metrics show whether a brick's parts belong together.

### Cohesion

Cohesion is the share of a brick's references to workspace code that point
inside the brick. A low value means the brick mostly calls other bricks.
That's expected of a base, but a component that is mostly glue may not
earn its place. Consider moving its logic into the bricks it calls, or
merging it with the brick it uses most. By default, a component below
`0.5` gets a warning. For a base, read it the other way:
high cohesion means the base does work of its own, which usually belongs
in a component. Averages and outliers compare components only.

### Shared keywords

Shared keywords count the keywords a brick uses that another brick also
uses. Most are map keys that both bricks must agree on, which is
connascence of meaning. Passing maps between bricks is normal in Clojure,
so this is off by default, but a high count shows where a change to a data
shape could ripple. To make those agreements explicit:

- Use namespaced keywords, so each key has one owning brick.
- Describe the shape with a spec or schema in the owning brick's interface.
- Expose constructor and accessor functions for data that other bricks
  shouldn't build or take apart themselves.

## I/O and mutability metrics

How much I/O a brick does isn't a problem in itself: a database component
is all I/O, by design. These metrics look instead at where the I/O lives,
and at state that code hides from the functions that use it.

### Library spread

For each library outside the workspace, assay counts the bricks that
require it, and by default warns at each require of a library that more
than one brick requires. When one brick wraps a library, you can upgrade
it, replace it, or fake it in tests in one place. A database driver that
four bricks require means four bricks know the schema, and a missing
gateway component.

To fix it, give the library one owner: a component whose interface offers
what the other bricks need, in their terms rather than the library's. Some
libraries are fine anywhere: a logging library, say, or a small utility.
Raise `:max-bricks`, or accept the warnings, for those.

### Host interop

Java interop couples code to the host: method calls, field access,
constructors, and static members such as `System/getenv`. A component
that wraps a Java API is dense with interop by design. Interop spread
through domain logic is harder to test, harder to read as Clojure, and
harder to port to another host, such as ClojureScript.

Interop density, interop forms per 100 forms, compares bricks of
different sizes. A brick that stands out is worth a look: move its
interop behind a small set of functions, or into a component whose job
is the Java API, so the rest works with Clojure data.

### Mutable state

A top-level atom, ref, agent, or volatile is state that every function in
the brick can reach without taking it as an argument. Tests that touch it
interfere with each other, and when it sits in the interface, every brick
that uses the component shares it. Dynamic vars and `alter-var-root` have
the same problem: behavior that depends on something the call doesn't
show.

Pass the state in instead: create it in a base, or in a system started by
a library such as Integrant or Component, and give it to the functions
that need it. A cache can live in a value the caller holds. Bases don't
count, since they're the imperative shell where state belongs.

## Error handling metrics

### Error surface

Error surface counts a component's interface definitions that can throw,
because their body throws or because something they call does, in any
brick. Every one is a failure each caller has to be ready for. John
Ousterhout's advice is to define errors out of existence: an interface
that returns `nil` for a missing key, or treats deleting what isn't there
as done, has fewer errors for callers to handle.

To shrink it, handle failures inside the component where it knows what
they mean, and make the remaining ones part of the interface's contract
rather than an accident of its implementation. Assay doesn't account for
catching, so a definition that catches everything it calls still counts.

### Untyped errors

Callers tell failures apart by what an error carries. A Java exception
such as `(IllegalStateException. msg)` carries a message and a class; an
`ex-info` without a `:type` key carries data, but nothing a caller can
dispatch on without knowing its shape. Both leave callers parsing
messages or guessing.

Throw `ex-info` with a `:type` key, ideally namespaced, such as
`{:type ::not-found :id id}`, or use `:cognitect.anomalies/category`. The
types a component throws are part of its interface, like its functions'
names.

### Catches and broad catches

Catching belongs where code can handle the failure, which is usually at
the edges: a base that turns errors into exit codes or HTTP responses. A
component that catches `Exception`, `RuntimeException`, `Throwable`, or
`Object` decides for every caller what any failure means, including ones
it didn't expect, such as a bug. Assay warns about broad catches in
components.

Catch the specific failure you can handle, such as
`clojure.lang.ExceptionInfo` with a known `:type`, and let the rest reach
code that knows what to do. A component that wraps a library sometimes
has to catch broadly, to turn the library's exceptions into its own;
then rethrow them as typed `ex-info` with the original as the cause.

## Test metrics

Assay reads each brick's `test` directory too. These metrics don't say
whether tests pass or what they cover line by line; they show the shape
of the tests.

### Focused tests

Assertions per test and forms per test are means over a brick's
`deftest` forms. A test with many assertions checks many behaviors, so
when it fails, its name says little about what broke, and the first
failure hides the rest. A large test usually builds a lot of state to
check one thing, which makes it slow to read and easy to break. Split it
into tests that each check one behavior, with names that say which, and
move shared setup into functions.

By default, assay warns about a brick whose mean, of either, is more
than 2 standard deviations above the mean of the other components. Bases
have tests too, and compare with the components, since a workspace
usually has too few bases to compare with each other. What
counts as a big test differs between workspaces, so the limit comes from
the workspace's own tests.

### Untested interface

An interface definition that no test anywhere in the workspace mentions
has no test at all, not even an indirect one through another brick. The
interface is what other bricks rely on, so it's where tests matter
most. Add a test through the interface, or, if nothing uses the
definition, remove it.

### Isolation hazards

Good tests don't affect each other. `with-redefs` and `alter-var-root`
change a var for every thread, so tests that run in parallel, or that
fail before restoring it, leak into each other. A top-level atom in a
test namespace carries state from one test to the next. `Thread/sleep`
makes a test depend on timing, so it's slow, and flaky when the machine
is busy. Pass dependencies in as arguments instead of redefining them,
create state inside each test, and wait on a condition rather than a
duration.

### Test boundary

A test that requires another brick's implementation namespace, rather
than its interface, breaks whenever that implementation changes, even
when its interface hasn't. It also tests the other brick from the wrong
side. Assay warns about each such require. Test through the interface,
or move the test to the brick it tests.

### Test ratio

Test forms per source form gives a crude sense of how much testing a
brick has. There's no right number, but a brick far below the others is
worth a look.

## Connascence

Connascence between bricks is coupling: code in one brick that must change
when code in another changes.

### Connascence of position

An interface function with many positional parameters makes every caller
in every other brick depend on their order. Reordering them, or adding one
in the middle, breaks all of those callers, and swapped arguments of the
same type fail at runtime, not at compile time. Take named parameters in a
map instead. Callers then depend only on the names.

### Connascence of algorithm

Duplicate code in more than one brick must stay in step: a fix to one
copy is a bug left in the other. To remove the duplication, move the code
into one brick's interface, or into a new component that both use.

Not every duplicate should become shared code. When the copies serve
different purposes and are likely to change for different reasons, joining
them couples bricks that were independent. In that case, leave them, or
raise `:min-forms` so that only larger duplicates trigger the rule.
