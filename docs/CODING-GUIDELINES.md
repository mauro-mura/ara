# Coding guidelines — simplicity and readability

Operating rules for a development agent, distilled from an engineering practice that
puts code simplicity above everything else. Every rule is imperative, with a
rationale (*why*) and a concrete check (*verify*) you can apply to yourself before
shipping code.

Guiding principle above all others: **write code that another developer (or you in
six months) can hold entirely in your head.** If they can't, the problem is the
code, not the reader.

---

## A. Simplicity and scope

**A1 — Simplicity is a feature. Defend it even at the cost of performance.**
- *Why:* complexity is the real cost of software over time; execution speed almost
  never is. A sequential flow, with no interleaving and no locks, eliminates entire
  classes of race conditions by itself.
- *Verify:* if a faster solution doubles the complexity for a gain nobody has
  measured as necessary, pick the simple one and say so in the comment.

**A2 — Say no. Don't add what wasn't asked for.**
- *Why:* every extra feature, option, or configuration parameter is code you
  maintain forever. The default of a good design is "no".
- *Verify:* before adding an abstraction, a flag, or an option, ask "who asked for
  this, and what real case uses it?". If the answer is "it might be needed", don't
  add it.

**A3 — No premature abstractions.**
- *Why:* an interface, a factory, or a layer introduced "for the future" locks the
  code into a shape before you know if it's the right one.
- *Verify:* introduce an abstraction only when you have **at least two** real,
  concrete consumers. With only one, write direct code. Three similar lines beat one
  wrong abstraction.

**A4 — Opinionated design: pick sensible defaults yourself.**
- *Why:* offloading every decision to a config file shifts the complexity onto the
  user instead of resolving it.
- *Verify:* for every configurable parameter, ask whether you could instead pick the
  right default and drop it. Fewer knobs = less surface to understand.

---

## B. Structure and readability

**B1 — Short functions, single responsibility.**
- *Why:* a function that fits on one screen can be read and verified at a glance.
- *Verify:* if understanding what a function does requires scrolling through it more
  than once, or holding more than 3-4 pieces of state in mind, split it — or make
  the flow explicit.

**B2 — Few data structures, well chosen. The rest follows.**
- *Why:* "bad programmers worry about the code; good programmers worry about data
  structures". The shape of the data determines how simple the code can be.
- *Verify:* if the code is convoluted, the data structure is almost always the
  wrong one. Fix the data before the logic.

**B3 — Readability before "cleverness".**
- *Why:* code that's too clever (dense one-liners, tricks) costs the reader more
  than it saves the writer.
- *Verify:* if a line takes a moment of mental decoding, rewrite it explicitly.
  Never write code you'd be "proud of because it's hard to read".

**B4 — Honest, descriptive names.**
- *Why:* the right name removes the need for a comment.
- *Verify:* if you're about to add a comment explaining *what* a variable
  represents, try renaming it first. Avoid excuse-names that compensate for a
  function doing too much.

---

## C. Comments — the heart of the style

Core principle: **a good comment raises the level of abstraction.** It lets you
understand a block without having to reconstruct it from the code. A comment is not
a failure of the code: code + comments together communicate more than code alone.
Write the comment for the future reader, not to fill space.

Before shipping, **mentally label every comment** with one of these types. Keep it
only if it's one of the "keep" kinds.

### Keep (high value)

**C1 — Design comments** *(the most valuable)*: at the top of a file/module or
function, explaining the chosen approach, the algorithm, and **the alternatives
discarded, with why**.
> *Why:* they make clear that the simplicity is the result of a process, not
> laziness. Open a new module with 10-20 lines explaining how it works and why this
> path won over the others.

**C2 — "Why" comments**: explain the *reason* for a non-obvious choice, not the
*what* (which the code already says).
> E.g.: `// we don't reclaim unused quota: otherwise a silent client could burst
> massively on wake-up`.

**C3 — "Teaching" comments**: teach the reader domain context they might not have
(a mathematical property, a protocol detail, an external constraint).

**C4 — "Checklist" comments**: coordinate changes across distant locations — "if you
change X here, also update Y".
> *Why:* they actively guard against future bugs caused by partial changes.

**C5 — Function comments**: at the function boundary, describing its contract
(what it does, input/output, side effects) so the body doesn't need to be read.
> Always flag surprising side effects (e.g., "this method consumes quota").

### Avoid

**C6 — Trivial comments**: restate what the code already says (`i++; // increment
i`). Delete them.

**C7 — Code commented out "just in case"**: never. That's exactly what git is for.

**C8 — Guide comments** *(use sparingly)*: add no information, they only guide the
eye. Allowed only in long blocks; if you can split the block into functions with
clear names, do that and remove the comment.

**C9 — Debt markers (TODO/FIXME)**: acceptable but don't let them rot. If you add a
TODO, explain *what* is missing and *why* it was deferred, not just "TODO: fix".

### Practical rule on comments
A comment must stand **on its own**: never point to an external document ("see
ADR-xyz") in place of the explanation. Cite the reference if you like, but write the
rationale right there, in the comment.

---

## D. Honesty and process

**D1 — The design process is part of the code.**
- Document what you considered and discarded. It makes the final choice defensible
  and teaches the next person why not to retry the path already ruled out.

**D2 — Be honest about trade-offs.**
- When you make an approximate choice or one with a known limitation, **state it**
  in the comment, with the reason behind it, and instructions for whoever comes
  next ("a provider that returns X should override this").

**D3 — Report results faithfully.**
- If a test fails, say so with the output. If a step was skipped, say so. Don't
  claim "done and verified" for something you haven't verified.

---

## E. Concurrency and thread safety

ARA runs on virtual threads, executors, futures and shared agents. These rules are not
optional: most of the subtle runtime failures in this codebase are concurrency bugs that
compile cleanly and only show up under load.

**E1 — State the thread-safety contract of every type you add.**
- *Why:* a reader must know, without tracing every call site, whether a type is immutable,
  thread-safe, or single-threaded. Memory, registries, sessions and schedulers are shared
  by construction; guessing wrong is a data race.
- *Verify:* on a new class, write one line of Javadoc — "immutable", "thread-safe", or
  "not thread-safe; confine to one thread". Prefer immutable records and final fields;
  document any mutable shared field and the lock that guards it.

**E2 — Never block inside a monitor or a lock.**
- *Why:* on Java 21 a virtual thread that does blocking I/O while holding a
  `synchronized` monitor **pins its carrier OS thread** (JEP 444); enough pinned carriers
  stall the whole runtime, not just one task. Even a `ReentrantLock` (which does not pin)
  held across I/O serialises every other thread that needs it.
- *Verify:* no `http.send`, socket, file, `Thread.sleep`, `await`, `join`, `get`, or
  `MCP` call may appear inside a `synchronized` block or method. Do the I/O first, then
  take the lock only for the state mutation. `ConcurrentHashMap.computeIfAbsent` runs its
  mapping function under a bin monitor: never build a resource (open a connection) inside
  it.

**E3 — One lock per concept, always in the same order.**
- *Why:* two unrelated locks taken together, or two locks taken in opposite orders on
  different paths, are a deadlock waiting for the wrong interleaving.
- *Verify:* each lock guards exactly one thing; if you must hold two, document the order
  at both acquisition sites and keep it identical everywhere.

**E4 — Prefer immutable data and message passing over shared mutable state.**
- *Why:* a sequential flow with no shared writes eliminates entire classes of races by
  itself, which is A1 applied to memory. This is why ARA's scheduler keeps a single
  control thread and why strategies keep all per-task state in locals.
- *Verify:* before adding a shared mutable field, ask whether an immutable snapshot, a
  `record`, or a copy handed across a boundary removes the need for the lock entirely.

**E5 — Handle interrupts honestly.**
- *Why:* swallowing `InterruptedException` (or leaving the flag set and moving on) breaks
  cooperative cancellation, and a leftover flag poisons the *next* task on a reused thread.
- *Verify:* never catch `InterruptedException` without either rethrowing, restoring the
  flag (`Thread.currentThread().interrupt()`), or converting it to a cancellation result.
  Clear the interrupt flag (`Thread.interrupted()`) at the start of any reused worker.

**E6 — Every wait is bounded.**
- *Why:* an unbounded `get()`/`join()`/`await()`/`take()` on a peer that never completes
  is an infinite hang; when the caller holds a session lock, it stalls every later task on
  that session too.
- *Verify:* every blocking wait carries a timeout derived from the task deadline. Note that
  `CompletableFuture.join()` ignores interrupts — use `get(timeout, unit)` when
  cancellation must work.

**E7 — Virtual threads are cheap, not free.**
- *Why:* "one virtual thread per message/request/token" with no ceiling is a resource
  exhaustion vector under load, and an unbounded wait queue is one too.
- *Verify:* any per-item thread spawn (a bus message, a tool call, a notification) has a
  configurable concurrency ceiling or a bounded queue. Reuse a shared executor instead of
  creating one per call where the lifetime allows it.

---

## F. Errors, null-safety, logging, testing

**F1 — Errors are information: never swallow them.**
- *Why:* a caught-and-ignored exception turns a loud failure into a silent one, which is
  the hardest kind to diagnose in production.
- *Verify:* every `catch` either handles the failure explicitly, wraps it with context
  (message + cause), or rethrows. Never `catch (Exception ignored)`. Preserve
  `InterruptedException` and timeout semantics (see E5). Use the existing taxonomy
  (`AraException`, `ExecutionTimeoutException`, `LlmException`, `FailureKind`) rather than
  raw `RuntimeException` when a dedicated type exists.

**F2 — Validate at the boundaries; `Optional` is for returns, not sentinels.**
- *Why:* compact constructors and public method entries are the one place where an
  invalid argument should be rejected, before it propagates into shared state.
- *Verify:* validate required parameters at the boundary (`Objects.requireNonNull`,
  range checks in `record` compact constructors). Do not return `null` to mean "absent"
  when a dedicated type or `Optional` exists; do not use `Optional` for fields or
  parameters.

**F3 — Logging is code: guard allocation, keep secrets out.**
- *Why:* SLF4J evaluates arguments eagerly, so an unguarded `log.debug(...)` that
  truncates, serialises JSON, or streams a list allocates on every call even with DEBUG
  off. Logs also leak PII and credentials if written carelessly.
- *Verify:* guard any allocating `debug`/`trace` body with `if (log.isDebugEnabled())`.
  Use parameterised `{}` formatting, never string concatenation in the call. Choose the
  level deliberately (`debug` = developer detail, `info` = lifecycle, `warn` = degraded but
  handled, `error` = needs attention). Never log secrets, tokens, or raw user PII.

**F4 — New production code ships with tests.**
- *Why:* the build enforces a JaCoCo coverage floor per module and it is a ratchet; untested
  production code both fails the gate and regresses silently later.
- *Verify:* every new unit has a focused test. Tests run offline (no real providers — use
  `ScriptedLlmClient`/fakes, as the existing suite does). Name tests after the behaviour,
  not the method. Do not lower a coverage floor to make a build pass.

---

## G. Size limits and naming

**G1 — Functions are short; these are the limits, not aspirations.**
- *Why:* B1 says "one screen" but a limit nobody quantifies drifts — the ReAct strategies
  in this repo grew to 160-188-line `execute` methods under that unstated rule.
- *Verify:* aim for a method ≤ ~40 lines and a class ≤ ~400 lines; treat a method over
  ~80 lines as a required split, and more than 3-4 parameters as a smell that the method
  does too much or needs a carrier type. Files over ~500 lines are a candidate for a
  package/class split. Exceptions must be justified in a comment.

**G2 — Names are honest and unabbreviated.**
- *Why:* B4 asks for descriptive names; abbreviations that only their author recognises
  are the most common way that rule is quietly broken.
- *Verify:* no non-obvious abbreviations outside a loop's few lines — `v`, `e`, `ex`,
  `tcr`, `pal`, `vs`, `le` and the like must be spelled out (`value`, `exception`,
  `toolCallRequest`, `palette`, `versionState`, `lease`). Single letters are acceptable
  only as loop indices or lambda parameters with an obvious meaning from context.

---

## H. Documentation must stand alone

**H1 — Write the rationale; never outsource it to a reference.**
- *Why:* a comment that says only "see ADR-052" gives a future reader nothing unless they
  can find and read that document — and the point of the comment is to save them from
  having to. This is the "Practical rule" of section C, made explicit and enforced.
- *Verify:* every design/why comment states the reasoning in place. Citing an ADR or doc is
  allowed **in addition**, never as the only content.

**H2 — Never reference a document that is not versioned in this repository.**
- *Why:* `docs/adr/...`, "ara-private", and anonymous "source §x.y" citations pointed at a
  corpus absent from the tree; such a reference is unverifiable for anyone reading only
  this checkout, and it rots silently when that corpus moves.
- *Verify:* search the repo for the target before you cite it; if it is not here, either
  bring the reasoning into the comment (H1) or drop the citation. A tag like `(ADR-052 D2)`
  is acceptable only when the surrounding comment already explains the decision on its own.

---

## Reconciling this style with the codebase

Three tensions are worth stating so they are not read as rules being broken:

- **A1 vs. hot-path care.** A1 favours simplicity over speed, but this codebase guards
  `debug` logging and precomputes strings that never change — both are *zero-complexity*
  wins, not traded against readability. If an optimisation adds branching or state, it
  needs a reason stronger than "faster"; if it adds none, it is within A1.
- **A3 vs. the public SPI.** `ara-core`'s interfaces (`LlmTransport`, `ToolRegistry`,
  `Span`, …) exist as **intentional public extension points**, not premature abstractions:
  the second consumer is an external implementer. A3 applies to internal code; a public
  SPI needs no internal second caller.
- **A4 vs. builders.** `AraRuntime.Builder` and friends are legitimate because their
  configuration genuinely varies per deployment. A4 still forbids knobs added "just in
  case": every option must have a real caller, and sensible defaults are mandatory.

---

## Pre-delivery checklist

Run through this list on every diff before considering it done:

- [ ] Did I add the minimum amount of code/abstraction that solves the problem?
- [ ] Does every function fit on one screen and have a single responsibility?
- [ ] Are the data structures the right ones (the code isn't convoluted to
      compensate for them)?
- [ ] Is every name descriptive, with no comment needed to explain it?
- [ ] Is every comment of type C1–C5 (keep)? Did I delete trivial ones (C6),
      commented-out code (C7), unnecessary guide comments (C8)?
- [ ] Do the design comments explain the discarded alternatives?
- [ ] Do the non-obvious points have a "why" comment?
- [ ] Do changes that must stay in sync have a "checklist" comment?
- [ ] Does every comment stand on its own, with no mandatory external references (H1)?
- [ ] Did I honestly state the trade-offs and known limitations?
- [ ] Does the new type state its thread-safety contract (E1)?
- [ ] Is there any blocking I/O held inside a lock (E2)? Any unbounded wait (E6)?
- [ ] Do reused workers clear the interrupt flag, and is `InterruptedException` handled (E5)?
- [ ] Is every caught exception handled, wrapped with context, or rethrown (F1)?
- [ ] Are required arguments validated at the boundary (F2)?
- [ ] Is every allocating DEBUG log guarded, and is no secret/PII logged (F3)?
- [ ] Does new production code ship with an offline test (F4)?
- [ ] Does the method respect the size limits (G1), and are names unabbreviated (G2)?
- [ ] Do any new comments reference a document not versioned in this repo (H2)?
