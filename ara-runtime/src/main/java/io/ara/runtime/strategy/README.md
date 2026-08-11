# io.ara.runtime.strategy

Implementations of `ExecutionStrategy` (`ara-core`) — the pluggable reasoning loops that
drive an agent's `Think → Act → Observe` cycle — plus the machinery around them: the
shared loop internals (`ReactExecutionSupport`), strategy selection (`ExecutionPlanner`),
tool-call parsing (`ToolCallParser`), and system prompt formatting
(`ToolCatalogFormatter`).

## The `ExecutionStrategy` contract, as actually implemented here

```java
ExecutionResult execute(AgentTask task, LlmClient llm, MemoryManager memory,
                         ToolRegistry tools, AgentConfig config);
```

**One call, one complete pass.** `AgentInstance` invokes `execute(...)` **exactly once**
per task — it does not loop around it. Every strategy in this package owns its *own*
internal iteration loop up to `config.maxIterations()` and returns only once, with a
final `ExecutionResult.success(...)` or `.failure(...)`. (The `ExecutionResult`/
`ExecutionStrategy` Javadoc in `ara-core` describes a re-invoke-on-`goalAchieved==false`
protocol; none of the strategies here use `ExecutionResult.intermediate(...)` or the
`goalAchieved` field, and `AgentInstance` only ever reads `isSuccess()`. Treat that part
of the core Javadoc as aspirational/legacy, not the current contract.)

## Strategies

| Class | `strategyName()` | Shape |
|---|---|---|
| `ReactStrategy` | `"react"` | Standard ReAct (Yao et al., 2022) — think, optionally call a tool, observe, repeat. The default. |
| `ReSpActStrategy` | `"respact"` | ReSpAct (Verma et al., 2024): ReAct plus a third **speak** action — a conversational turn to the user that ends the `execute()` call without closing the task. |
| `ReflActStrategy` | `"reflact"` | ReAct plus **in-loop** self-correction: a failed tool call or a stalled streak injects a short critique into the *same* working memory and keeps going — no episode restart. |
| `PlanExecuteStrategy` | `"plan_execute"` | ReWOO-inspired: one planning call produces a numbered step list, each step executes in an isolated context, one synthesis call produces the final answer. |
| `ReflexionStrategy` | `"reflexion"` | Decorator (Shinn et al., 2023): wraps a delegate strategy, and on failure generates a verbal self-critique and retries the **whole episode** with all reflections injected into memory. |
| `RetrievalAugmentedStrategy` | `"rag+" + delegate.strategyName()` | Decorator: retrieves context once per task and transparently injects it into every LLM call the wrapped strategy makes. |

**`"reflexion"` vs `"reflact"`** — both add self-critique, at opposite granularities, and
they are complementary rather than alternatives:

| | `"reflexion"` | `"reflact"` |
|---|---|---|
| Trigger | the entire delegate pass failed | a tool call failed, or *N* consecutive iterations produced neither a tool call nor a final answer |
| Reaction | wipes working memory, restarts the episode from scratch | appends the critique to the **same** working memory and continues |
| Granularity | macro — between episodes | micro — inside one episode |
| Shape | decorator over another strategy | a self-contained loop |

Wiring in `AraRuntime.Builder.build()`: `ReflexionStrategy` wraps `ReactStrategy`;
`ReflActStrategy` and `ReflexionStrategy` share the same `LlmRouter`, so both can route
their critique call to a different provider than the main loop.
`RetrievalAugmentedStrategy` (when a `Retriever` is configured) wraps `ReactStrategy`,
`ReSpActStrategy`, `PlanExecuteStrategy` and `ReflActStrategy` as separate registered
strategies (`"rag+react"`, `"rag+respact"`, `"rag+plan_execute"`, `"rag+reflact"`) — the
plain, un-augmented variants stay registered too. Decorators compose freely because each
only needs an `ExecutionStrategy` to wrap.

### `ReactExecutionSupport` (shared loop internals)

Package-private home of everything the ReAct-shaped strategies do *identically*, so a
fix lands once instead of drifting between near-copies. `ReactStrategy` and
`ReflActStrategy` use all of it; `ReSpActStrategy` uses the dispatch/streaming half but
brings its own three-branch decision (see below).

- **Decision logic** — `StepDecision` (`FinalAnswer` / `DispatchTools` / `Continue`) and
  `decideNextStep(...)`, checked in priority order each iteration: a structured tool call
  from the LLM adapter → an inline `{"tool_id":...}` JSON or `<|channel|>` blob found in
  the raw text (`ToolCallParser`) → the `FINAL_ANSWER` sentinel or a natural
  `finishReason == "stop"` with no tool call → otherwise, an intermediate reasoning step
  (loop again).
- **Message building** (`buildMessages`) and the `REACT_SYSTEM_SUFFIX` / tool-catalog
  injection, both skipped when the client speaks native function-calling.
- **Synthesis nudge** (`maybeInjectSynthesis`): fires once, `tail` iterations before the
  hard stop, where `tail = max(2, maxIterations / 4)` — proportional to loop length (the
  last ~25%), never fewer than the last 2 iterations, and skipped entirely when
  `maxIterations < 5`, where a "wrap up now" message at iteration 2 of 5 would only
  confuse the model. The nudge leaves tools available (it asks the model to *persist*
  unsaved work first) — distinct from the forced-final iteration, which withholds them.
- **Parallel tool dispatch** (`dispatchParallel`): multiple tool calls in one completion
  run concurrently on virtual threads, bounded by the remaining execution deadline;
  results are appended to memory in submission order regardless of completion order, for
  deterministic transcripts. Workers that miss the deadline (or an interrupt) are
  themselves interrupted, so a tool blocked on I/O gets the same cooperative unblock
  signal `AgentSession.requestCancel()` uses instead of holding its connection open.
  Each spawned `Runnable` is wrapped with `tools.wrapForPropagation(...)` **before** the
  thread starts, so a `tool.execute` span from `TelemetryToolRegistry` still nests under
  the caller's `agent.execute` span — tracing context is thread-local and does not
  otherwise survive the hop onto a new thread (virtual or platform). Wrap any *new*
  thread-spawning code path the same way.
- `dispatchSingle`/`dispatchParallel` **return `true` when any dispatched call failed** —
  an explicit signal for `ReflActStrategy`'s reflect-on-tool-failure trigger, so it never
  has to parse the formatted observation text (whose wording is free to change).
- **Streaming** (`streamAndCollect`): when `config.streamingEnabled()` and
  `task.tokenCallback()` are both set, tokens are forwarded live. If the underlying
  stream comes back blank (some adapters fall back to blocking `complete()` internally
  but only forward `.text()`, silently dropping tool-call metadata), it retries once with
  a blocking `complete()` call to recover the full `LlmCompletion`. On timeout or
  interrupt the `Flow.Subscription` is **cancelled** before giving up — otherwise the
  publisher keeps streaming into a dead task, holding the provider connection open and
  firing the SSE callback for a task the caller was already told had failed.
- **Cost-budget arithmetic** (`checkBudget`) — projected spend + next-call estimate
  against `AgentConfig.costBudget()`.

### `ReactStrategy`

The plain two-branch loop: think, optionally dispatch tools, repeat until a final answer
or `maxIterations`. All the mechanics above come from `ReactExecutionSupport`; this class
is just the loop's control flow.

- **Forced-final iteration**: on the last iteration(s) (`iterations >= maxIterations - 1`)
  tools are withheld from the LLM entirely, so it cannot emit a tool call and *must*
  produce plain text — guaranteeing termination even for models that never self-report
  `FINAL_ANSWER`. Any tool-call signal on this iteration is ignored.
- Cooperative cancellation is checked at the top of every iteration and honored inside
  `dispatchParallel`'s bounded wait — `AgentInstance.terminate(session)` interrupts the
  thread, not this class directly.

### `ReSpActStrategy`

ReAct with a third action type alongside *reason* and *act*: **speak** — a conversational
utterance (a clarifying question, a status update, a partial result) that does not close
out the task.

- **Protocol**: mirrors ReAct's sentinel format, adding
  `Action: SPEAK\nMessage: <text>`. `RESPACT_SYSTEM_SUFFIX` replaces
  `REACT_SYSTEM_SUFFIX` and teaches all three actions.
- **A SPEAK action ends the current `execute()` call**, exactly like a final answer.
  This is a deliberate adaptation, not the paper's literal design: the paper's reference
  setup pauses the loop mid-episode for a live "user simulator" reply, but
  `AraAgent.execute(AgentTask)` is synchronous and one-task-per-call — there is no
  mechanism for a strategy to suspend and be resumed with a reply spliced into the same
  execution. Instead the turn ends, `AgentInstance` records it into `ConversationHistory`
  as an assistant turn, and the user's reply arrives as the next `AgentTask` on the same
  `SessionId`, replayed into working memory like any other turn. Several Think → Act
  rounds can still happen inside one `execute()` before either terminal branch fires.
- **Callers distinguish the two** via the last step in `ExecutionResult.steps()`:
  `StepType.SPEAK` for a turn awaiting a reply, `StepType.FINAL_ANSWER` for a completed
  task. `AgentTask.speakCallback()` additionally delivers the message in real time, the
  way `toolCallCallback()` already does for tool dispatch.
- **One deliberate divergence from ReAct**: a natural stop with no sentinel and no tool
  call resolves to an **implicit SPEAK**, not an implicit final answer. For a
  conversational agent, "I produced text and have nothing more to do right now" means "I
  said something to the user", not "this task is permanently closed" — the latter should
  be an affirmative signal, not the fallback for every model that doesn't emit sentinels.
- Speak and act are mutually exclusive within one completion (v1): a completion carrying
  both a tool call and speak text has the **tool call win**, mirroring how `ReactStrategy`
  already prioritises a tool-call signal over final-answer text.

### `ReflActStrategy`

ReAct plus in-loop self-correction — the micro-granularity counterpart to
`ReflexionStrategy`'s whole-episode retry (see the comparison table above).

- **Two triggers**, both configurable via `StrategyConfig.ReflAct`:
  - a dispatched tool call **failed** (`reflectOnToolFailure`, default `true`);
  - **`unproductiveStreak`** consecutive iterations (default 2) produced neither a tool
    dispatch nor a final answer — the model is thinking in circles. A successful dispatch
    resets the counter.
- **Nothing is ever reset.** The critique is appended to the same working memory as a
  `"user"` turn (`SELF-REFLECTION: …`, the same idiom the synthesis nudge uses) and
  recorded as a `StepType.REFLECTION` step; the very next Think call simply sees one more
  message in context. `ReflActStrategyTest` enforces this with a `MemoryManager` whose
  `clearWorkingMemory()` throws.
- **Reflection calls are capped** at `maxReflections` (default 3) for the whole task and
  do **not** consume the main loop's `maxIterations` budget — same treatment
  `ReflexionStrategy` and `PlanExecuteStrategy`'s replan step give their meta-level calls
  — but their token usage **is** folded into the reported totals.
- **Suppressed on forced-final iterations**: injecting a fresh course-correction exactly
  when the model needs to wrap up would work against the termination guarantee that
  withholding tools provides.
- **Dual-model critique**: `StrategyConfig.ReflAct.reflectionProvider()` plus the
  `ReflActStrategy(LlmRouter)` constructor route the critique call to a different provider
  than the main loop — same rationale and same fallback chain as
  `ReflexionStrategy.reflectionProvider()` (see below).
- The reflection prompt sees a **bounded tail** of the trace (the last 8 steps), not the
  full transcript, so its cost stays flat no matter how long the episode has run.
- `maxReflections = 0` degenerates to plain ReAct.

### `PlanExecuteStrategy`

- Three phases, each a single or bounded set of LLM calls: **Planning** (one call, numbered
  step list, capped at `pe.maxPlanSteps()`) → **Execution** (each step in an isolated
  message context — system prompt + task + compact plan status + compact prior-step
  summaries, *not* the full growing transcript — capped at `pe.maxStepRoundsPerStep()`
  Think/Act/Observe rounds) → **Synthesis** (one call over task + plan + all step
  results).
- Per-step isolation keeps token cost roughly
  `O(maxPlanSteps × STEP_RESULT_TRUNCATE_CHARS + stepLocalHistory)` regardless of how
  many total iterations the whole task consumes — unlike `ReactStrategy`, where the
  transcript grows with every iteration.
- **Re-planning** (`StrategyConfig.PlanExecute.replanPolicy() == "on_failure"`): a step
  that produces no usable output triggers up to `MAX_REPLAN_ATTEMPTS` (2) re-plans of
  only the *remaining* steps, keeping completed ones untouched.
- Falls back to concatenating raw step results (`buildFallbackAnswer`) if the synthesis
  call itself returns an empty response (context overflow or provider hiccup).
- **Records a full execution trace** (`thought` for the plan, `tool_call`/`observation`
  per dispatch, `final_answer` for the synthesis) and fires `AgentTask.notifyToolCall(...)`
  — parity with `ReactStrategy`. Failure results carry the partial trace accumulated so
  far rather than an empty list.
- Internally structured around two carriers instead of long positional parameter lists:
  `Run` (immutable per-pass collaborators) and `Tally` (mutable iteration/token/step
  accumulators).

### `ReflexionStrategy`

- Snapshots the system prompt and any recalled-episodic-context `"system"` entries
  *before* the delegate's first attempt, so it can cleanly reset and re-seed working
  memory on every retry without losing that context.
- On failure: one extra LLM call generates a reflection (falls back to a canned
  templated message if that call itself fails), which is persisted and re-injected —
  **all** prior reflections, not just the latest — as a single system block on the next
  attempt, so retry *N* sees every lesson learned from attempts `1..N-1`.
- Aborts immediately on cooperative cancellation **without** generating a reflection —
  a cancelled attempt isn't a genuine failure worth learning from.
- `maxReflections = 0` degenerates to a single pass through the delegate with no retry.
- **Reflection can be routed to a different LLM than the agent's own** —
  `StrategyConfig.Reflexion.reflectionProvider()` plus the
  `ReflexionStrategy(ExecutionStrategy, LlmRouter)` constructor (wired by default in
  `AraRuntime.Builder.build()`, reusing the same instrumented client map the main
  `AgentFactory` registry uses — no separate, uninstrumented path for reflection calls).
  When both a router and a non-blank `reflectionProvider` are present, the reflection
  call is resolved via `router.select(LlmConfig.of(reflectionProvider), ctx)` instead of
  reusing `llm`; it falls back to `llm` — logging a warning — if resolution fails (unknown
  provider id), and falls back silently (by design, this is the pre-existing behavior) if
  either the router or the provider id is absent. This exists because self-critique and
  other-critique are not the same skill: models are markedly better at catching mistakes
  in someone else's output than in their own ("The Self-Correction Illusion: LLMs Correct
  Others but Not Themselves", 2026) — pointing `reflectionProvider` at a different model
  (or even just a different profile of the same model) is a cheap approximation of an
  external critic instead of pure same-model self-reflection.

### `RetrievalAugmentedStrategy`

- Retrieval happens **once per task**, not once per LLM call — the delegate strategy
  (and every iteration it runs) sees the same retrieved context.
- Injection is via a private `AugmentingLlmClient` decorator that prepends the context
  block to the first `"system"` message it sees (prepending a *new* system message if
  none exists) on every `complete`/`stream` call — the wrapped delegate strategy is
  completely unaware RAG is happening; it just receives an `LlmClient` that already
  answers with context baked in.
- If retrieval returns zero chunks, the delegate runs against the plain, un-augmented
  `llm` — no empty context block is ever injected.

## When to use which strategy

| Use case | Strategy | Why |
|---|---|---|
| General-purpose assistant, chatbot, single tool calls, short-to-medium tasks | `"react"` | The default for a reason: lowest overhead, no upfront planning latency, handles the common case (a handful of tool calls then an answer) well. Start here; only reach for something else once you can name the specific failure mode ReAct hits on your task. |
| Long, multi-step tasks (data pipelines, multi-document report generation, research-then-write workflows) where the transcript would otherwise grow unbounded | `"plan_execute"` | Per-step isolated context keeps token cost bounded regardless of total task length — `ReactStrategy`'s single growing transcript becomes a liability past a certain number of iterations, both for cost and because early context can crowd out the model's attention on later steps. Also a better fit when the task naturally decomposes into named, checkable steps (e.g. "fetch data → transform → validate → write report"). |
| Multi-turn conversational agents that need to ask clarifying questions, confirm before acting, or report progress mid-task | `"respact"` | The only strategy where "say something and wait for the user" is a first-class outcome instead of being conflated with "the task is done". A plain ReAct agent that needs a missing detail has no way to express that: it either guesses, or emits a final answer that reads like a question and closes the turn ambiguously. Use `"respact"` when the UI can keep a composer open and route the reply back on the same `SessionId`; stick with `"react"` for fire-and-forget tasks where there is no user to answer. |
| Tasks with a cheap, automatic failure signal (a test suite, a schema validator, a compiler, an `OutputProcessor` rejection) where a retry-with-critique measurably helps | `"reflexion"` (wrapping `"react"` or `"plan_execute"`) | Verbal self-critique is worth the extra LLM call specifically when failure is *detectable* and the root cause is often something the model can articulate and correct (wrong approach, missed constraint) rather than pure bad luck (a flaky external API). Don't reach for it as a generic "make it more reliable" knob — see the `reflectionProvider` note above: same-model self-critique is measurably weaker than other-critique, so pair it with `reflectionProvider` pointed at a different model/profile where correctness actually matters. |
| Tool-heavy tasks where individual calls fail recoverably (bad arguments, wrong tool picked, a transient API error) and restarting the whole episode would throw away good work | `"reflact"` | Correcting course mid-episode is much cheaper than `"reflexion"`'s wipe-and-retry when most of the trajectory was fine and only one step went wrong — the completed work stays in context instead of being re-derived from scratch. Also the better fit for the "model loops without acting" failure mode, which `"reflexion"` cannot see at all (a loop that runs to `maxIterations` is one failure, detected only at the very end). Reach for `"reflexion"` instead when failure is only detectable *after* a complete pass (a test suite over the final artifact); the two compose — `"reflexion"` can wrap `"reflact"` for both granularities. |
| Anything requiring accurate answers grounded in a private knowledge base, internal docs, or a domain corpus the base model doesn't know | `"rag+react"` / `"rag+plan_execute"` (needs a `Retriever` configured) | Guarantees retrieved context reaches the model on *every* LLM call in the pass, including later ReAct iterations — a plain `search_documents` tool only guarantees retrieval when the model *decides* to call it, which it may skip on a confident-but-wrong turn. Use `RetrievalAugmentedStrategy` when grounding is a correctness requirement, not just a nice-to-have; use a plain search tool when retrieval should be the model's own judgment call. |
| Anything needing higher confidence at the cost of more LLM calls, with a scoreable/comparable final answer (numeric answers, classification, short factual questions) | *(not yet implemented — see below)* | Self-consistency / best-of-N: run N independent attempts in parallel (the same virtual-thread + `wrapForPropagation` pattern `ReactStrategy.dispatchParallel` already uses, applied to whole task attempts instead of tool calls) and vote/rerank. Cheap to reason about, expensive in tokens (N×); good fit when task-level parallelism is acceptable and wrong answers are costly. |
| Complex sequential plans that fail deep into execution, where re-running the *whole* remaining plan (today's `"on_failure"` replan policy) is wasteful | *(not yet implemented — see below)* | ADaPT-style recursive, as-needed decomposition: retry and decompose only the sub-task that actually failed, leaving already-completed and not-yet-reached steps untouched — a more surgical evolution of `PlanExecuteStrategy`'s existing coarse-grained replan. |

## Helpers

- **`ReactExecutionSupport`** — the shared loop internals described in its own section
  above (decision logic, message building, synthesis nudge, tool dispatch, streaming,
  budget check). Package-private, stateless, all-static. When adding a ReAct-shaped
  strategy, reuse it rather than copying: the parallel-dispatch interrupt propagation and
  the streaming-subscription-cancel-on-timeout were both real bugs, and a hand-copied
  second implementation is exactly what drifts out of sync on the next fix.
- **`ToolCallParser`** — the single place that turns an `LlmCompletion` into
  `ToolCallRequest`s, used by every strategy in this package (previously duplicated
  between `ReactStrategy` and `PlanExecuteStrategy`). Extraction priority: native tool call
  (`LlmCompletion.toolCallJson()`/`toolCalls()`) → inline ARA JSON
  (`{"tool_id":...,"arguments":{...}}`, found anywhere in the text, `"name"` accepted as
  an alias for `"tool_id"`) → the `<|channel|>...to=TOOL...<|message|>{...}` format some
  local models (e.g. LM Studio / gpt-oss) emit instead of standard ReAct text.
  `extractAll` handles the multi-tool-call-per-completion case (parallel native function
  calling), preserving each call's own `toolCallId`. Also strips namespace prefixes some
  models add (`functions.get_current_time` → `get_current_time`) and unwraps
  double-wrapped `{"arguments":{"arguments":{...}}}` payloads some providers produce.
- **`ToolCatalogFormatter`** — the one place that renders the tool list into the system
  prompt (`- toolId: description\n  Arguments: <schema>`). Centralized specifically to
  kill a prior inconsistency where `ReactStrategy` rendered an empty string for zero
  tools while `PlanExecuteStrategy` rendered an explicit "no tools available" sentence;
  the canonical behavior is now the empty string in both — the LLM infers "no tools" from
  the section's absence.
- **`ExecutionPlanner`** — O(1) name → strategy lookup (`AgentConfig.plannerStrategy()`).
  Falls back to `"react"` with a warning if the requested name isn't registered; throws
  `IllegalStateException` at *selection* time (not build time) if even `"react"` is
  missing. Immutable once built — there is no runtime re-registration; hot-swapping is a
  "build a new planner and re-wire the factory" operation, not a planner API.

## `StrategyConfig` (companion, defined in `ara-core`)

A sealed type (`React` / `PlanExecute` / `Reflexion` / `ReflAct`) carrying exactly the
parameters each strategy needs, replacing what used to be flat nullable fields directly
on `AgentConfig`. Each strategy implementation reads its own config defensively:

```java
StrategyConfig.PlanExecute pe = (config.strategyConfig() instanceof StrategyConfig.PlanExecute p)
        ? p : StrategyConfig.PlanExecute.defaults();
```

— so an agent configured for `"plan_execute"` but carrying no `strategyConfig()` (or the
wrong variant) still runs with sane defaults rather than throwing a `ClassCastException`.

| Variant | Parameters (defaults) |
|---|---|
| `React` | none |
| `PlanExecute` | `replanPolicy` (`"never"`), `maxPlanSteps` (8), `maxStepRoundsPerStep` (3) |
| `Reflexion` | `maxReflections` (2), `reflectionPrompt` (`null`), `reflectionProvider` (`null`) |
| `ReflAct` | `maxReflections` (3), `unproductiveStreak` (2), `reflectOnToolFailure` (`true`), `reflectionProvider` (`null`) |

`ReactStrategy` and `ReSpActStrategy` have no `StrategyConfig` variant of their own —
their knobs (`maxIterations`, `maxTokensPerStep`, cost budget) live directly on
`AgentConfig`.

## Conventions across this package

- **Every strategy is stateless and safe to share** across concurrent tasks/agents — all
  per-task state (iteration counters, accumulated tokens, `stepResults`) lives in local
  variables inside `execute(...)`, never in a field.
- **Cost-budget and timeout checks are the strategy's own responsibility**, checked
  inline at iteration/step boundaries (`ExecutionTimeoutException` on deadline overrun,
  an early `ExecutionResult.failure(...)` on projected cost-budget overrun) — there is no
  external watchdog thread; a strategy that doesn't check its deadline can run past it.
- **Thread-hop + tracing context**: any code in this package that spawns a new thread
  mid-execution (currently only `ReactExecutionSupport.dispatchParallel`) must wrap the
  spawned `Runnable` via `tools.wrapForPropagation(...)` before starting the thread — see
  the `ReactExecutionSupport` section above. This is not automatic and easy to regress
  silently: the span will still be created and exported, just as a disconnected root span
  instead of a child, with no compile-time or obvious runtime signal that anything is
  wrong.
- **Every strategy records an execution trace.** `ExecutionResult.steps()` must reach the
  caller populated — including on failure paths, where the *partial* trace is often the
  only diagnostic available. `StepType` is the shared vocabulary (`thought`, `tool_call`,
  `observation`, `final_answer`, plus `speak` for ReSpAct and `reflection` for ReflAct);
  its `wireValue()` is the serialisation contract, so add a constant rather than
  repurposing an existing one.
- **Meta-level LLM calls** (reflection, replanning) stay off the `maxIterations` budget
  but **must** be added to the reported token totals — a critique call is as billable as
  any other, and dropping it silently under-reports the real cost of every retry cycle.
