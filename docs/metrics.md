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
  large, has several clusters, and many dependents is a much stronger
  signal than any one of those alone.
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
- Replace a long `cond` or `case` that picks behavior with a map lookup or
  a multimethod, so each case lives in its own place.
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
several responsibilities. When a brick grows well past its peers, check its
clusters: more than one suggests where to split it. A
`:max-increase-percent` change threshold catches a brick that grows
suddenly, often because a feature landed in the wrong place.

### Mean and max function complexity

Max function complexity points at the brick's worst function, which the
function rules already report. The mean says more about the brick: a high
mean means complex code throughout, often because the brick works at too
low a level or handles many special cases. That can mean its data needs a
better shape, so that the cases go away rather than move.

The default `:std-devs` warning flags a brick whose mean stands out from
its peers. A rising mean, which a `:max-increase` change threshold
catches, shows a brick getting harder to work in over time.

### Max nesting depth

This covers every top-level form, not just functions, so it also catches
deeply nested data and large `def` forms. A deep data literal may be fine.
Otherwise, treat it as you would a deeply nested function.

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
change, as bases usually are.

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

Abstractness measures how much of a component sits behind its interface.
A small interface over a large implementation hides most of its code, so
the implementation can change without affecting other bricks.

Low abstractness means the interface is large compared with what it hides.
Either the interface namespace holds implementation code, which belongs in
an implementation namespace, or the component exposes more than its callers
need. Abstractness matters most for stable components: a brick with many
dependents and a large interface is hard to change in any way.

### Cycles

Bricks in a cycle can't change, test, or deploy independently, since each
depends on the others. Assay treats them as errors by default. To break a
cycle, extract what the bricks share into a new component, or invert one of
the dependencies as described under instability.

### New dependencies

With `--base`, assay warns about each dependency between bricks that the
base didn't have. A new dependency isn't wrong, but it changes the
architecture, so it deserves a deliberate decision in code review.

## Cohesion metrics

Cohesion metrics show whether a brick's parts belong together.

### Cohesion

Cohesion is the share of a brick's references to workspace code that point
inside the brick. A low value means the brick mostly calls other bricks.
That's expected of a base, but a component that is mostly glue may not
earn its place. Consider moving its logic into the bricks it calls, or
merging it with the brick it uses most.

### Clusters

A cluster is a group of implementation definitions that refer to each
other. Two clusters share nothing, so they could live in separate bricks
without either noticing. More than one cluster suggests a split: move each
cluster to its own component, or to the existing brick it fits best.

A small brick of unrelated helpers can have many clusters and still be
fine. Splitting it would add bricks without making anything clearer.

### Unused interface

An interface definition that no other brick refers to is code to maintain
with no caller. Remove it, or move it out of the interface if the brick
uses it internally. Assay reads only `src`, so a function that only tests
call counts as unused. If it exists for tests, consider testing through
the functions that callers do use.

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
