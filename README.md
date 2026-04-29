# Senior Developer Assessment

Two deliverables:

- **Task 1** — `HierarchyFilter.filter(...)` plus tests, under `src/`.
- **Task 2** — code review of the `SimpleCache<K,V>` snippet, written up at the end of [`SimpleCache.md`](./SimpleCache.md).

The full brief is in [`Senior_Developer_Assessment.md`](./Senior_Developer_Assessment.md).

## Build & test

Java 21, Maven, JUnit 5. From the repo root:

```sh
mvn test
```

That compiles `src/main/java` and runs everything under `src/test/java` (Surefire picks up `*Test.java` automatically). `mvn package` builds the jar if you need it, but there's no main entry point — this is library code.

## Layout

```
src/main/java/com/team/hierarchy/
    Hierarchy.java            -- interface (provided by the brief)
    ArrayBasedHierarchy.java  -- straightforward array-backed impl
    HierarchyFilter.java      -- the actual deliverable
src/test/java/com/team/hierarchy/
    HierarchyFilterTest.java
SimpleCache.md                -- Task 2 review at the bottom of the file
```

I split the original single-file `Hierarchy.java` into one class per file and moved everything under a Maven layout so it's runnable out of the box.

## Task 1 — notes on the implementation

The hierarchy is a forest serialized in DFS pre-order across two parallel arrays (`nodeIds`, `depths`). The filter rule is the strict one: a node survives only if it *and every ancestor* pass the predicate; a rejected ancestor takes its whole subtree with it. Output depths are preserved verbatim — survivors don't get re-parented onto a closer ancestor.

The implementation is a single linear pass with one piece of state — `pruneAbove`, an `int` holding the depth at which the current "skip everything deeper than this" cut started, or `-1` when no cut is active. While we're cutting, every node with `depth > pruneAbove` is skipped; the moment we see a node at `pruneAbove` or shallower, the cut clears and that node is evaluated normally. No ancestor stack is needed: because the input is DFS pre-order, an entire rejected subtree is exactly the contiguous run of strictly-deeper nodes that follows the rejected node, and the first node not strictly deeper is by definition outside that subtree.

That gives O(n) time, O(k) extra memory (k = survivors). The predicate is invoked at most once per node, and never on descendants of a rejected ancestor — there's a test for that specifically.

A couple of smaller choices worth flagging:

- The output buffers are sized at `n` and trimmed via `arraycopy` at the end. If nothing was filtered out (`kept == n`), the trim is skipped. Either way the returned `Hierarchy` always owns its arrays — even when the predicate accepts everything, the result doesn't alias the input.
- Per the JavaDoc on `Hierarchy`, the depth invariants are assumed, not validated. The filter trusts its inputs; defensive checks would be misplaced here.

The test class covers the spec example, empty/single-node/all-roots edge cases, rejecting at the root vs. mid-tree vs. leaf, broken chains, sibling-after-deep-subtree (the case where a naive implementation tends to over-prune), multiple rejections in the same tree, predicate call counting, and array non-aliasing.

## Task 2

See [`SimpleCache.md`](./SimpleCache.md). The review is below the snippet, in prose. Short version: missing eviction is the dealbreaker, and a few of the other issues only become visible once you start reasoning about it under real load.