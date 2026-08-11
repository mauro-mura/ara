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
- [ ] Does every comment stand on its own, with no mandatory external references?
- [ ] Did I honestly state the trade-offs and known limitations?
