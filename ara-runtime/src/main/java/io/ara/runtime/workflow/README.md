# io.ara.runtime.workflow

A declarative workflow-graph layer (ADR-052 D1–D7, ADR-054): nodes, edges and a
dataflow scheduler that runs the graph to completion — the graph-based counterpart to
`io.ara.runtime.pipeline`'s strictly *sequential* chains. Where a pipeline is a list of
steps advancing one at a time, a workflow is a graph: parallel fan-in and fan-out,
branches of uneven depth, and cycles expressed through a single explicit edge kind. And,
since ADR-052 D7, a workflow can be hosted inside a real `AgentInstance` via
`WorkflowAgents`, becoming a first-class `AraAgent` exactly like a pipeline does.

## Why graphs, and why this engine specifically

Both LangGraph and Microsoft's frameworks converge on a *superstep* (BSP) scheduler: a
global step boundary — every node ready at step N runs, then every node ready at step
N+1. That boundary is the category's recurring defect: on a fan-in whose branches have
uneven depth, the short branch's token lands at step N and the long branch's at step
N+1, so the join **fires once per branch instead of once total**, the second time with a
partial input. ADR-052 D1's activation rule asks a different question — not *when* did a
token arrive, but *which edges carry one* — so a join on uneven branches fires once, on
the complete input, with no step boundary anywhere.

## Classes

| Class | Role |
|---|---|
| `Workflow` | The builder facade + run governor: declares the `WorkflowGraph`, the per-node occurrence cap and an optional `RunBudget` in one fluent place, and each `run(...)` constructs a fresh single-use `DataflowScheduler`. Deliberately *not* the `AgentPipeline.Builder → WorkflowGraph` compiler. |
| `WorkflowGraph` | The graph model itself (ADR-052 D1): `List<WorkflowNode>`, `List<WorkflowEdge>`, and — since D3 — `Map<String, BinaryOperator<Object>>` of declared reducers. Enforces referential integrity: no dangling edge endpoints, no duplicate ids. |
| `WorkflowNode` | One unit of work: `id`, `body` (composed input → output), optional routing `selector`, resume `UncertainResumePolicy`, `cost`, `Write`, `MapOverSpec`, `composer`. Deliberately opaque — `body` is a plain `Function`, not an `AraAgent`. |
| `WorkflowEdge` | A directed edge between two node *ids*. `back` is **the one place cycles are expressed**: a `back` edge carries OR-merge semantics, a forward edge AND-join. |
| `DataflowScheduler` | The engine (ADR-052 D1): dataflow activation, per-node two-phase journal, shared-state writes, budget charging, and a replay path for resume. Single-run, single-use. |
| `WorkflowResult` | The outcome of a run: the append-only `journal`, `ok`/`failureReason`, and `state` — every `Write` merged through its reducer, present even on a failed/partial run. |
| `JournalEntry` | Sealed two-phase journal entry: `Started` is written the moment an occurrence is submitted, `Finished` when it completes. The trace, the checkpoint, and the serialisation order all at once. |
| `NodeOutcome` | Sealed: how one node occurrence finished — `Completed(content, selectedTargets)`, `Failed(reason)`, `Suspended(reason)`. The triple D1 names for the journal. |
| `WorkflowNodeSuspendedException` | Thrown by a `body` to signal "waiting on something outside the graph" — caught by the scheduler and recorded as `NodeOutcome.Suspended` instead of `Failed`. |
| `UncertainResumePolicy` | What to do on resume with a `Started`-but-unfinished occurrence: `RETRY` (default), `FAIL`, or `SUSPEND`. Pushed onto whoever declares the node. |
| `Reducers` | The common combinators for `reduce(...)` — pre-declared, order-sensitive hotspots: `concatLists`, `concatStrings(delimiter)`, `lastWriteWins`. |
| `WorkflowPattern` | The one shape ADR-054 chooses (option O3): a declarative spec that *compiles onto* a `WorkflowGraph` through `Workflow.Builder` — never a special node type inside the scheduler. |
| `WorkflowStrategy` | Package-private `ExecutionStrategy` adapting a `Workflow` to run inside an `AgentInstance` (ADR-052 D7) — the same move `PipelineStrategy` makes for `AgentPipeline`. |
| `WorkflowAgents` | Public factory: `WorkflowAgents.of(workflow)` → an `AraAgent` backed by a real `AgentInstance` hosting a `WorkflowStrategy`. The only class most callers ever touch besides `Workflow` itself. |

The pattern specs live in `io.ara.runtime.workflow.patterns`:

| Class | Pattern |
|---|---|
| `Critic` | **Adversarial Verification** (ADR-054 D2): a reviewer after a worker approves its output or sends it back with feedback. Compiles to one routing node, a forward edge to the approval target, and a `back` edge — the review loop is an ordinary cycle. |
| `Filter` | **Generate-and-Filter**: a policy check that lets a candidate through or discards it, each to its own declared destination. `rejectTo` is required — a discard branch that goes nowhere cannot be expressed. |
| `Supervisor` | **Supervisor / Router** (ADR-054 D4): decides which worker handles the task next, choosing from an alphabet declared at build time, with a mandatory `orElse`. |
| `Tournament` | **Tournament** (ADR-054 D1): the same task attempted N times in parallel, each attempt varied, with a judge that picks one winner. |
| `Gate` / `Verdict` | Sealed outcome types the critiquing/filtering patterns hand to their selectors. `Gate` (`Pass`/`Reject`) is forward-only — a rejection moves to a discard terminal, never retried. `Verdict` (`Approved`/`Rejected`) is backward — a rejection goes back to the worker as its next input. Two shapes, two types, because Generate-and-Filter and Adversarial Verification differ in exactly that. |

## The ADRs behind the code

References like "ADR-052 D4" or "ADR-054" point at design documents
of the ARA project. The ADRs carry the reasoning — why the
activation rule is what it is, why option O3 won — and each reference below is a pointer
to that story, not a prerequirement to reading the code:

| Reference | What it is, in one breath |
|---|---|
| ADR-016 | Agent sessions: per-session state/isolation and the `SessionBusyPolicy` on concurrent calls to one session — inherited for free by anything hosted in a real `AgentInstance`. |
| ADR-050 | The classify-and-act shape: a router decides from an alphabet declared at build time and must always have an else-arc (`orElse`) — a label can never route into a void. |
| ADR-051 | The "don't re-derive the agent lifecycle" directive: documents the hand-rolled-`AraAgent` anti-pattern (mutable `volatile` state, `RunContext` discarded at the boundary, zero token accounting) and the fix — host a strategy inside a real `AgentInstance`. |
| ADR-052 | This engine's source: the workflow-graph design. Its D-numbers (D1…D7) are a decision log recording individual choices — D1 the activation rule and journal, D3 shared state, D4 `mapOver`, D5 the structural controls, D6 suspension/resume status, D7 hosting in `AgentInstance`. |
| ADR-054 | Pattern specs: `WorkflowPattern` (option O3) — declarative specs that *compile onto* the graph instead of becoming scheduler special-cases. |
| ADR-0108 | A measured ARA result the `Tournament` pattern cites: a judge seeing only the N candidates did no better than chance on date-arithmetic answers, until the source was added. |

## The graph model: nodes and edges

`WorkflowNode` is the unit of work. Its `body` is an opaque function from its composed
input to its output — deliberately *not* an `AraAgent`; wiring agent-shaped nodes is
ADR-052 D2's much larger job (it is what the `AgentPipeline.Builder → WorkflowGraph`
compiler needs, and it does not exist yet). D1 only has to prove the scheduler's
activation rule, and an opaque function is the smallest thing that exercises it.

- **`selector`** — output → the subset of outgoing edges to activate. `null` = activate
  every outgoing edge.
- **`composer`** — how to combine the tokens of several *forward* incoming edges into
  one input, in **edge-declaration order, never completion order** (keeps joins
  deterministic under concurrency). Runs on the scheduler's **control thread**, so it
  must be cheap — shaping, never judging. A multi-predecessor node without one is
  refused at build time (control #10).
- **`cost`** — output → the `Spend` this occurrence drew, charged to the run's budget
  (ADR-054 D6). `null` = "declares no cost" (still counts as one activation).
- **`Write`** — D3's declarative replacement for `ara-graph`'s retired `SharedWorkspace`
  (see "Shared state" below).
- **`onUncertainResume`** — see "The journal and resume" below.

Edges are purely declared relationships between node ids. The `back` flag is **the only
mechanism for expressing cycles**, and it carries the merge semantics with it: a `back`
edge is OR-merge (the target fires as soon as a single token arrives on it), a forward
edge is AND-join. That distinction — not a separate cycle check — is what lets the
scheduler treat cycles as ordinary occurrences.

## `Workflow` builder

```java
Workflow wf = Workflow.of()
        .node("plan",  in -> planFor(in))
        .node("exec",  in -> execute(in)).cost("exec", out -> Spend.of(price(out), tokens(out), 1))
        .edge("plan", "exec")
        .maxOccurrences(50)                                    // per-node cap, default 100
        .budget(RunBudget.of().maxTokens(200_000).maxCost(2.00).maxActivations(500))
        .terminal("exec")
        .build();

WorkflowResult result = wf.run("goal", pool);                  // or wf.run(input, pool, priorJournal)
```

- `node(id, body)` and `routingNode(id, body, selector)` declare nodes; the rest of the
  builder attaches per-node properties to already-declared ids (`cost`, `onUncertainResume`,
  `writes`, `composer`, `mapOver`) — configuring a never-added node throws, naming the id.
- `edge(from, to)` is a forward edge; `backEdge(from, to)` the OR-merge one. `reduce(key,
  reducer)` declares how two writes to the same shared-state key combine. `pattern(...)`
  compiles a pattern spec (see below).
- `terminal(...)` declares intended exits — purely declarative bookkeeping for the
  structural checks. `build()` refuses a workflow with no nodes, a terminal naming an
  unknown node, or any failed structural check (below).
- A `Workflow` is **reusable**: every `run(...)` builds a fresh single-use
  `DataflowScheduler` — the invariant that class documents. To resume from a prior run's
  journal, pass it to `run(input, pool, priorJournal)`.

## `DataflowScheduler` — dataflow, not superstep

The heart is D1's activation rule (`enablingInput`): a node is ready when either

- a `back` edge carries a token — **OR-merge**, the cycle case: fires without waiting on
  anything else; or
- every non-`back` incoming edge carries a token **or is dead**, and at least one carries
  a token — **AND-join / barrier**.

"It's correct on branches of uneven depth precisely because it asks *which* edges hold a
token, never *when* the token arrived." Composed input is every forward edge's token in
edge-declaration order (never completion order), combined by the declared `composer`, or
`String.join(" | ", ...)` when none is declared — a placeholder fallback only reachable
by single-predecessor nodes (control #10 refuses multi-predecessor nodes without one). A
`composer` returning `null` throws rather than read as "not ready yet" and stall forever.

Other mechanics:

- **Deadness is local, not a global fixpoint.** An edge is dead once its source completes
  without selecting it; a node is dead once all its non-back incoming edges are dead, at
  which point its outgoing edges are marked dead too. O(edges) incremental closure —
  the thing superstep schedulers need epochs for.
- **A single control thread.** Every mutation of scheduler state — token deques, dead
  edges, occurrence counters, journal, shared state — happens on the thread that called
  `run()`, never inside a worker. Workers only evaluate `body()` and `selector()`, pure
  functions of their input. The scheduler is therefore **lock-free**: a sequential
  control flow around the state eliminates the whole class of races a concurrent map
  would need locking to avoid.
- **First completion, not all.** `drive` uses an `ExecutorCompletionService` and waits
  for the *first* finished task, re-looping: submit everything ready right now, and when
  nothing is in flight the run is `ok = true`.
- **Fail-fast.** The first `Failed`/`Suspended` outcome stops the run immediately even
  with other nodes still in flight. Tolerating partial failure at scale is D4's job via
  `mapOver`'s `AgentChain.FailurePolicy`; "D1 only has to behave safely, not flexibly."
- **The occurrence cap is the runtime backstop.** `maxOccurrences` (default 100) caps how
  many times a *single node* may fire in one run; exceeding it fails the run instead of
  spinning forever on a malformed cycle. ADR-052 D5's control #8 — a mandatory
  `maxVisits` on back edges — would turn that into a build-time error, and is one of the
  deferred controls (every existing back edge would need to grow the field).

## The journal and resume

Each occurrence writes two journal entries (ADR-052 D1): a `Started` the moment it is
submitted, a `Finished` when it completes. One-phase (write-only-on-completion) had one
hole — an in-flight node at crash leaves no trace, so resume can't distinguish "never
started" from "started, unknown outcome". Harmless for idempotent nodes; a bug for ones
with external effects (an email, a charge). The two-phase write closes it: a `Started`
with no matching `Finished` is exactly what the node's `UncertainResumePolicy` reacts to
*"instead of the scheduler guessing"*:

- `RETRY` (default) — fire again with the same recorded input. Correct for idempotent,
  side-effect-free bodies.
- `FAIL` — fail the resume outright rather than risk running a non-idempotent node twice.
- `SUSPEND` — stop the resume like a suspension. Distinct from `FAIL` only in the
  recorded `failureReason`; un-suspending automatically is ADR-052 D6's job.

`run(input, pool, priorJournal)` replays the journal: `Started` entries restore the
occurrence counters and consume the enabling tokens; `Finished.Completed` re-deposits
tokens on the selected edges (or marks the rest dead) and re-applies budget charges and
state writes; a prior `Finished.Failed`/`Suspended` entry stops the resume — replaying
past a recorded failure would fabricate progress that never happened.

`NodeOutcome` is the completed/failed/suspended triple those `Finished` entries carry,
and `WorkflowResult` keeps the whole append-only journal plus `ok`/`failureReason` and
`state`. One honesty note from its Javadoc: `ok`/`failureReason` is a *placeholder pair*,
not a finished status model — both node failure and suspension report `ok == false`,
distinguishable only by string-matching the reason. Giving suspension its own branchable
status is ADR-052 D6's job, deliberately deferred rather than built prematurely.

## Shared state: `Write` + reducers

A node never mutates shared state directly. It *declares* a contribution via
`writes(id, key, extractor)`: when the node completes, `extractor` maps its output to a
value stored under `key`, and if two or more nodes ever write the same key the graph's
declared reducer decides how a second write merges in (ADR-052 D3). A second write to a
key with **no declared reducer fails the run** — the same ambiguity control #10 refuses
to guess at for edges is refused here too. (`single writer per key` is the free case:
the first write is stored as-is.)

`Reducers` provides the common combinators, and one thing about them matters more than
anything else in a concurrent fan-in: **first arg = the value already in shared state,
second = the one just written** — *not* whichever body finished first. The scheduler's
single-control-thread invariant makes the merge deterministic regardless of how the
worker executions interleaved. `lastWriteWins()` is the explicit opt-out from ADR's
default — "I know two nodes may write this key, and I don't care which wins."

This is the declarative replacement for `ara-graph`'s retired `SharedWorkspace`: the
same channel `RunState` will serve once D2 makes nodes agent-shaped.

## Dynamic fan-out: `mapOver`

`mapOver(sourceId, workerId, elements, workerBody, collectInto, maxActivations,
onPartialFailure)` makes a node a dynamic fan-out source (ADR-052 D4): once it
completes, `elements` reads a runtime-determined list from its output, and `workerBody`
runs **once per element — each with its own input**, individually journaled under
`workerId + "[occurrence.index]"`. `collectInto`, when set, is where each child's output
lands — as `List.of(output)`, merged through `Reducers.concatLists()` unless `reduce(...)`
declared something else.

Two deliberate design points:

- **`maxActivations` is mandatory.** Exceeding it fails the run naming the construct —
  never silently truncates. Same reasoning as the Claude Agent SDK's dynamic-workflow
  cap: an unbounded multiplier hidden behind a data-dependent list is how a workflow
  quietly explodes.
- **`workerBody` is a plain function, not a node id.** A node named purely to be a
  mapOver template, with no incoming edges of its own, would otherwise read as a *second
  entry point* (every node with no incoming edges is seeded and run). `workerId` is
  bookkeeping for the journal, not a real graph node.

`onPartialFailure` reuses `AgentChain.FailurePolicy`: `FAIL_FAST` and `REQUIRE_ALL`
collapse to the same behavior here (the scheduler always waits for every child; only the
error-message shape differs), while `PARTIAL_OK` keeps the successes and fails only when
*every* child failed.

## The run governor: `budget`

`Workflow.Builder.budget(RunBudget)` attaches ADR-054 D6's single-run governor. The
scheduler charges it **once per node occurrence** — one journal entry, one
`RunBudget.charge` — using the node's declared `cost()` for the money/token spend, every
occurrence counting as one activation regardless. A breach stops the run with a
`failureReason` naming both the axis and the node that was firing. An unset budget means an
ungoverned run (only the per-node `maxOccurrences` backstop applies).

## Structural checks — four today, six deferred

`Workflow.Builder.build()` runs **four** of the ten control-style checks ADR-052 D5 names
(each reported as an `IllegalStateException` with detail):

- **Control #3 — dead ends.** When terminals were declared, a node with no outgoing
  edges that was never declared terminal is an error. Skipped entirely when no terminal
  is declared (backward compat — old graphs always have a sink).
- **Controls #1/#2 — reachability.** Every node must have a path to some declared
  terminal (a fixpoint over `to → from`). Skipped when no terminal is declared.
- **Control #9 — join inside a cycle.** A node inside a cycle (mutual reachability
  computed per node, not a Tarjan pass) cannot have a *forward* incoming edge from
  outside the cycle. Bare self-loops and a node owning its own back edge are exempt: an
  OR-merge fires the node on that incoming token every lap.
- **Control #10 — ambiguous fan-in.** A node with more than one forward predecessor and
  no declared `composer` is refused. Runs after #9 so the more specific diagnostic wins.

The other six are documented deferrals, each with its filed reason in `Workflow.java`: #4
(routing shape / mandatory else-arc — needs an `IntentRouter`-like abstraction), #5 (HITL
presence) and #6 (tool declaration) need agent-shaped nodes, which `WorkflowNode`
deliberately is not; #7 (state-key compatibility) needs declared reads before D3's channel
exists; #8 (termination: mandatory `maxVisits` per back edge) would break every existing
back edge — the per-node `maxOccurrences` backstop covers it at coarser grain.

## Patterns — specs that compile, never scheduler cases

`WorkflowPattern` is ADR-054's option O3: a `@FunctionalInterface` whose single method,
`compileInto(Workflow.Builder)`, adds nodes, edges and declared per-node properties — and
nothing else. A pattern implemented as a scheduler case *"turns the engine into a
collection of special cases, and every special case is a build-time control that quietly
stops holding"* (ADR-054 DR-3 — the way LangGraph arrived at `defer=True`). Because a
pattern-as-spec is just more nodes and edges, every structural control applies to it for
free, and the scheduler keeps its FF-6 property: **no `case`/`instanceof` on node type,
anywhere**.

Each implementation carries its own early build-time checks, raised at spec construction
when possible:

### `Critic` — Adversarial Verification

```java
Workflow.of()
        .node("write", writer)
        .pattern(Critic.of("review", "write", reviewer)      // verifier: Function<String, Verdict>
                .maxRounds(3)
                .approveTo("publish"))
        .node("publish", publisher)
        .terminal("publish")
        .build();
```

A reviewer sits after a worker: it runs the verifier over the worker's output and either
approves it (forward edge to `approveTo`) or sends it back with its feedback as the
`back` edge's token — which is exactly what makes the rejection the worker's *next
input*. The round counter lives in the spec, not in scheduler per-run state (node bodies
can't access that), so **a `Workflow` carrying a `Critic` is effectively single-run** —
the count carries across `run` calls, and `maxRounds`/`approveTo` are mandatory *by
construction* (you cannot obtain a `Critic` without passing through both): an uncapped
review loop is control #8's unbounded cycle, and a loop with no approval target has no
way out. The durable form — control #8's `maxVisits` on the back edge — stays deferred.
The verifier's `Verdict` is a sealed type rather than a string "for the reason ADR-054
gives: the node that decides which edge is taken decides it on a value the compiler can
check exhaustively. A string verdict is one typo away from silently taking the wrong
branch."

### `Filter` — Generate-and-Filter

```java
Workflow.of()
        .node("generate", generator)
        .pattern(Filter.of("check", validator,             // validator: Function<String, Gate>
                "pass", "discard"))
        ...
```

A policy check that lets a candidate through (`Gate.Pass`) or discards it with a stated
reason (`Gate.Reject`), each routed to its own declared destination. `rejectTo` is
**required, not optional** — "a discard branch that goes nowhere is a dead end, which
control #3 refuses to build. Requiring the target here means the graph cannot be
expressed wrongly in the first place — the same reason ADR-050 makes `orElse` mandatory."
The contrast with `Critic` is the whole distinction between `Gate` and `Verdict`: a
rejection moves *back* to the worker for retry in Adversarial Verification, but *forward*
to a recording terminal, never retried, in Generate-and-Filter. Two shapes, two types.

### `Supervisor` — Router

```java
Workflow.of()
        .node("intake", in -> in)
        .pattern(Supervisor.of("dispatch", decide, Set.of("billing", "tech", "sales"))
                .orElse("general"))
        ...
```

A node that decides which worker handles the task next, choosing from an **alphabet
declared at build time** rather than from whatever string it produces. The alphabet is
what makes it a router: every target is a real edge before the run starts, so
reachability/livelock/termination controls apply. `orElse` is the mandatory terminal
method — the ADR-050 `IntentRouter` shape — so a supervisor can never route into a void.
One declared weakening: ADR-054 D4 wants the choice constrained by the provider's
structured output so an out-of-alphabet name is *unrepresentable*; nodes are still plain
functions, so what this enforces today is a **runtime** check — a decision outside the
alphabet routes to `orElse` rather than failing the run. Written down "so the gap is
visible at the point where someone would otherwise assume the stronger guarantee."

### `Tournament` — N attempts, one winner

```java
Workflow.of()
        .node("prepare", preparer)
        .pattern(Tournament.of("prepare", "solve", 5, i -> input -> solveAt(temperature(i), input))
                .judge("pick", candidates -> bestOf(candidates)))
        .terminal("pick")
        .build();
```

The same task attempted N times **in parallel, on the same input**, each attempt varied,
with a judge that picks one winner rather than combining them (ADR-054 D1). Two design
points from the Javadoc:

- **Why real nodes and not `mapOver`:** `mapOver` is one activation per element of a
  list; a tournament is N activations *on the same input*. N declared nodes each get
  their own edge from the same source, so each receives the identical token, and
  variation per contender is a trivial closure built by `variant(i)` — nothing smuggled
  through the element text. The degree `replicas` is fixed at build time, so **the
  declaration *is* the cap**.
- **Why the judge is not a scheduler feature:** the fan-in is an ordinary node with
  several forward predecessors and a declared `composer` — control #10's opt-in. The
  composer only *shapes* the N candidates into one value (on the control thread); the
  judging happens in the body, on the pool, where expensive comparison belongs. So "a
  fan-in that chooses" needs no new scheduler concept at all.
- **The judge can see the source too.** `of(...)` hands the judge only the N candidates —
  right for relative comparison ("which is more polite"), wrong when correctness is only
  checkable against the original problem. `ofJudgingSource(...)` adds a source → judge
  edge so the judge body receives the problem first (ADR-0108 measured a live tournament
  where a judge seeing only the candidates did no better than chance on date-arithmetic
  answers). Candidate outputs are framed with `"\0"` — NUL, which no LLM or tool output
  carries in practice, safe where a printable delimiter would not be.

## `WorkflowAgents` / `WorkflowStrategy` — workflow as a real agent

ADR-052 D7 is the same move `PipelineStrategy`/`PipelineAgents` make for `AgentPipeline`,
and for the same reason: **don't re-derive the agent lifecycle, host inside the one that
already exists.** `WorkflowStrategy` is a normal `ExecutionStrategy` — ARA's
per-strategy extension point: `ReactStrategy`/`PlanExecuteStrategy`/`PipelineStrategy`
implement the same interface, and an `ExecutionPlanner` picks one per agent by the
`plannerStrategy()` name in the agent's config — and
`WorkflowAgents.of(workflow)` builds a real `AgentInstance` around it — per-session
isolation and `sessionBusyPolicy()` (ADR-016), cooperative cancellation, the
`agent.execute` telemetry span, and the interceptor chain all fall out of `AgentInstance`
for free. This retires the hand-rolled `GraphAgent` — mutable `volatile` state,
`RunContext` discarded at the boundary, token/cost always `0, 0, 0.0` — the anti-pattern
ADR-051 documents.

```java
AraAgent workflowAgent = WorkflowAgents.of(workflow);
AgentResponse response = workflowAgent.execute(AgentTask.of("start the run"));
```

`execute(...)` runs `workflow.run(task.input(), pool)` on a fresh virtual-thread executor.
Output selection: D1's graph has no designated single output node, so the result is
the **last `JournalEntry.Finished` to actually finish** (chronological journal order) —
the same "best answer so far" convention `PipelineStrategy` uses for its own last step,
extended to a concurrently-run graph. Iterations are counted per completed node; each is
recorded as an `ExecutionStep.observation` (`nodeId#occurrence: content`); `ok` maps to
`ExecutionResult.success`/`failure(reason)`.

Like `PipelineStrategy`, it ignores `llm`, `memory` and `tools` — the real work happens
inside nodes. And like `PipelineAgents`, the strategy is **package-private** and
`WorkflowAgents` builds a fresh single-strategy `ExecutionPlanner` per agent, registering
the strategy under whatever name `config.plannerStrategy()` already carries, so
`planner.select(config)` always finds an exact match instead of falling through to
`"react"` (the convenience `of(workflow)` sets `"workflow"` itself). One documented
limitation today: `body()` is a plain function, so there is no `AgentResponse` to read
token usage from — `promptTokens()`/`outputTokens()` are always `0`. A node's declared
`cost()` is still charged to the run's `RunBudget` if configured; surfacing that spend
through `ExecutionResult` is a separate, later increment (it needs `Workflow.run` to hand
back the budget's final `Spend`).

## Usage

Classify the same shape with a routing node:

```java
WorkflowResult result = Workflow.of()
        .node("classify", classifier)
        .routingNode("route", out -> out, out -> Set.of(out.equals("BILLING") ? "billing" : "general"))
        .node("billing",  billingAgent::execute)
        .node("general",  generalAgent::execute)
        .edge("classify", "route")
        .edge("route", "billing")
        .edge("route", "general")
        .terminal("billing", "general")
        .build()
        .run("ticket text", pool);
```

Fan-out with `mapOver`, collecting into shared state:

```java
WorkflowResult result = Workflow.of()
        .node("fetch", fetcher)
        .mapOver("fetch", "scrape", this::linksIn,
                this::scrapeOne, "scraped", 100, AgentChain.FailurePolicy.PARTIAL_OK)
        // collectInto registered Reducers.concatLists() for "scraped" automatically.
        .terminal("fetch")
        .build()
        .run(url, pool);
```

A workflow as an agent, delegated to or nested like any other `AraAgent`:

```java
AraAgent wf = WorkflowAgents.of(workflow);
AgentConfig cfg = AgentConfig.defaults().agentType("research-flow").build();
AraAgent named = WorkflowAgents.of(AgentId.of("research-flow"), cfg, workflow);
```

## Conventions / gotchas

- **`WorkflowNode` carries no identity or lifecycle beyond its id and properties.** It is
  a pure computation descriptor, testable without fabricating an `AgentId`. Identity and
  lifecycle belong to `WorkflowAgents`/`AgentInstance`, added by composition — the same
  split ARA already uses for `ExecutionStrategy` (pure algorithm) vs `AgentInstance`
  (identity + lifecycle).
- **A body that throws anything other than `WorkflowNodeSuspendedException` is a
  failure** — there is no dedicated "fail" signal to throw deliberately; an ordinary
  exception already means "this went wrong". Throwing `WorkflowNodeSuspendedException`
  records `Suspended` instead, and today stops the run exactly like a failure — its value
  ahead of D6 is an **honest journal**: a reader can tell "waiting on a decision" from
  "broke".
- **The scheduler is single-use.** Construct one `DataflowScheduler` per `run` call;
  `Workflow.run(...)` does that for you. Reusing a scheduler instance across runs is not
  a supported mode.
- **A `back` edge's target fires on the token immediately** — no AND-join, no barrier.
  That is the cycle mechanism, and it is also why control #9 exempts the edges a cycle
  *owns*: the OR-merge fires the node every lap by design.
- **Composer and body run on different threads.** The composer runs on the scheduler's
  control thread (cheap shaping — keep it that way); the body runs on the pool (expensive
  judging belongs here). The `Tournament` judge is the canonical example: composer joins,
  body picks.
- **Every reducer is written `(alreadyInState, justWritten)`**, not "whichever finished
  first". `Reducers.concatLists()` and `concatStrings` are order-sensitive in exactly that
  way; the single-control-thread invariant is what makes the result deterministic.
- **A second write to a declared key with no reducer fails the run**, and a
  multi-predecessor node with no composer is refused at build — both are the same policy:
  the engine never guesses how to combine. Declare the combination.
- **`Critic` workflows are single-run.** The round counter lives in the pattern spec (it
  must — node bodies can't reach scheduler state), so it carries across `run` calls.
  Build a fresh `Workflow` for a fresh run, the same way an attached `RunBudget` already
  requires.
- **Only *terminal*-declared graphs get the dead-end and reachability checks.** With no
  `terminal(...)`, `build()` skips controls #1/#2/#3 for backward compatibility with
  graphs that always had a sink — a missed `terminal(...)` there is not a build error.
- **`WorkflowStrategy` reports zero prompt/output tokens** until nodes become
  agent-shaped (D2): there is no `AgentResponse` to read from today. Declared `cost()`s
  still govern budgeted runs — they just are not surfaced through the produced
  `ExecutionResult` yet.