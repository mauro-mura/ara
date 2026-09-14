# Configuration reference

[← back to README](../README.md)

- [AgentConfig — identity](#identity--who-the-agent-is)
- [AgentConfig — LLM](#llm--which-models-to-use-and-how-to-select-them)
- [AgentConfig — execution](#execution--how-tasks-run)
- [AgentConfig — memory](#memory--working-memory-and-conversation)
- [Sessions & concurrency](#sessions--concurrency)
- [Agent Instance Context](#agent-instance-context--private-per-agent-data)
- [Agent scheduling](#agent-scheduling)

---

`AgentConfig` is an immutable record that fully describes how an agent is built and
behaves at runtime. It is composed of four sub-records with single responsibility —
`AgentIdentity`, `LlmConfig`, `ExecutionConfig`, `MemoryConfig` — but you always build it
through the flat builder: `AgentConfig.defaults()...build()`.

## Identity — who the agent is

| Builder method | Default | Description |
|---|---|---|
| `agentId(AgentId)` | auto-generated | Unique identifier of the agent instance |
| `agentType(String)` | `"generic"` | Logical type/role of the agent (e.g. `"translator"`, `"analyst"`) |
| `name(String)` | `""` | Human-readable display name |
| `description(String)` | `""` | Free-text description of what the agent does |
| `version(String)` | `"1.0.0"` | Version tag of the agent definition |
| `tags(List<String>)` | empty | Arbitrary labels for grouping/lookup |
| `systemPrompt(String)` | `"You are a helpful AI agent."` | System prompt sent on every LLM call (further shaped by `PromptShaper`s) |
| `promptCatalogId(String)` | `null` | Resolve the system prompt from a prompt catalog instead of inlining it |

## LLM — which model(s) to use and how to select them

| Builder method | Default | Description |
|---|---|---|
| `primaryLlm(LlmProfile)` | empty profile | Primary LLM profile; its `modelId` must match a client registered on the runtime |
| `fallbackLlm(LlmProfile)` / `fallbackLlms(List)` | empty | Fallback profiles used according to the selection policy |
| `llmSelectionPolicy(LlmSelectionPolicy)` | `PRIMARY_ONLY` | `PRIMARY_ONLY` — never use fallbacks · `FAILOVER` — on any `shouldFailover()` failure, try the next fallback in declaration order, each candidate carrying a circuit breaker · `ROUND_ROBIN` — distribute calls sequentially across all profiles |
| `logLlmIo(boolean)` | `false` | Log every LLM request/response and each tool call/result at `INFO` |
| `logLlmIoMaxChars(int)` | `1500` | Truncation limit for logged payloads; `0` = no truncation |

Each `LlmProfile` (built with `LlmProfile.builder()`, or `LlmProfile.of("modelId")` for
the shorthand) carries the per-model settings:

| `LlmProfile` field | Default | Description |
|---|---|---|
| `modelId` | `""` | Name of the `LlmClient` registered on the runtime (`AraRuntime.builder().llmClient("name", …)`) |
| `temperature` | `0.4` | Sampling temperature, range `[0.0, 2.0]` |
| `topP` | `null` | Nucleus sampling, range `[0.0, 1.0]`; `null` = provider default |
| `maxTokens` | `null` | Max output tokens per completion; `null` = provider default |
| `baseUrl` / `apiKey` / `modelName` | `null` | Optional per-profile overrides of the underlying client's connection settings |
| `streamingEnabled` | `false` | Request streaming completions when the adapter supports it |
| `nativeJsonSchema` | `false` | Use the provider's native structured-output / JSON-schema mode |
| `costInputPer1kTokens` / `costOutputPer1kTokens` (`Money`) | `Money.zero("EUR")` | Unit prices used for cost accounting |
| `costBudget` (`Budget`) | `Budget.unlimited()` | Spending cap for the agent, denominated in `costCurrency` (defaults to `"EUR"`) |

## Execution — how tasks run

| Builder method | Default | Description |
|---|---|---|
| `plannerStrategy(String)` | `"react"` | Execution strategy: `"react"`, `"respact"`, `"reflact"`, `"plan_execute"`, `"reflexion"`, `"rag+…"` |
| `strategyConfig(StrategyConfig)` | `null` | Typed per-strategy configuration; when set, it also overrides `plannerStrategy` with its own strategy name |
| `enabledTools(List<String>)` | empty | Tool IDs this agent may call — tools are always opt-in per agent |
| `mcpServerIds(List<String>)` | empty | MCP servers whose tools are exposed to this agent |
| `maxIterations(int)` | `10` | Max reasoning-loop iterations (LLM calls) per task before aborting |
| `executionTimeout(Duration)` | 5 minutes | Wall-clock limit for a single task execution |
| `maxTokensPerStep(int)` | `4096` | Token cap requested per LLM call |
| `humanApprovalRequired(boolean)` | `false` | When `true` and an `ApprovalGate` is configured on the runtime, every tool call is routed through the gate before dispatch — the virtual thread parks until a human decision arrives or the request times out |
| `retrieverId(String)` | `null` | Which registered `Retriever` a `"rag+…"` strategy uses; `null` = the runtime's default retriever. Setting it with a non-`rag+` strategy is rejected at construction |
| `knowledgeBaseId(String)` | `null` | Knowledge base the agent searches *as a tool*: combined with `search_documents` in `enabledTools`, it attaches a `KnowledgeBasePromptShaper` |
| `sessionBusyPolicy(SessionBusyPolicy)` | `REJECT` | Same-session concurrency: `REJECT` fails fast with `"Session busy"`, `ENQUEUE` queues FIFO |

## Memory — working memory and conversation

| Builder method | Default | Description |
|---|---|---|
| `workingMemoryTokenBudget(int)` | `0` | Token budget for working memory; `0` = unbounded |
| `workingMemoryEviction(String)` | `"drop_middle"` | Eviction policy when the budget is exceeded: `"drop_oldest"`, `"drop_middle"`, or `"summarize"` |
| `maxConversationTurns(int)` | `0` | Max conversation turns kept per session; `0` = unbounded |
| `maxReflections(int)` | `2` | **Inert — no strategy reads it.** Superseded by `StrategyConfig.Reflexion.maxReflections()` / `StrategyConfig.ReflAct.maxReflections()`; kept only for source compatibility |
| `reflectionPrompt(String)` | `null` | **Inert — no strategy reads it.** Superseded by `StrategyConfig.Reflexion.reflectionPrompt()`; kept only for source compatibility |

> To configure reflection behaviour, pass a `StrategyConfig` — the flat `maxReflections` /
> `reflectionPrompt` builder methods above are leftovers from before `StrategyConfig`
> existed and setting them has no effect:
>
> ```java
> .strategyConfig(new StrategyConfig.Reflexion(3, myPrompt, "critic-model"))   // reflexion
> .strategyConfig(new StrategyConfig.ReflAct(3, 2, true, "critic-model"))      // reflact
> ```

---

## Sessions & concurrency

Every task runs inside a **session**. Each `SessionId` owns an isolated state machine and
working memory, so different sessions of the same agent never interfere. Passing no
session id runs in a fresh ephemeral session.

```java
AgentTask t = AgentTask.of("Hello").withSessionId(SessionId.of("user-42"));
AgentResponse r = agent.execute(t);   // synchronous, blocks the caller
```

### Parallel execution

`AraRuntime.submit` runs a task on the shared virtual-thread executor and returns an
`AgentFuture`, so multiple sessions execute concurrently:

```java
AgentFuture f1 = runtime.submit(agent, AgentTask.of("A").withSessionId(SessionId.of("s1")));
AgentFuture f2 = runtime.submit(agent, AgentTask.of("B").withSessionId(SessionId.of("s2")));
f1.get(); f2.get();   // both ran in parallel
```

### Same-session policy — reject vs queue

Two tasks that target the **same** session are governed by
`AgentConfig.sessionBusyPolicy()`. Different sessions always run concurrently regardless
of the policy.

| Policy | Behaviour when the session is already busy |
|---|---|
| `REJECT` (default) | second task fails fast with `"Session busy"` |
| `ENQUEUE` | second task is queued and runs after the first (FIFO) |

```java
AgentConfig cfg = AgentConfig.defaults()
        .agentType("chat")
        .sessionBusyPolicy(SessionBusyPolicy.ENQUEUE)
        .build();
```

### Cancellation & termination

- `agent.terminate(SessionId)` cancels the in-flight task of **one** session without
  affecting other sessions or future tasks. Cancellation is cooperative: the running
  strategy stops at its next boundary and returns a `"Cancelled"` failure.
- `agent.terminate()` shuts the whole agent down permanently (also used by
  `runtime.destroyAgent`).

---

## Agent Instance Context — private per-agent data

Sometimes an agent needs private data — an API key, a tenant id — that must be readable
by **both** prompt shaping and tool execution, but must **never** reach the LLM (not in
the prompt text, not in a tool's JSON argument schema). `AgentInstanceContext` gives every
agent a live, per-agent key-value view backed by a shared `InstanceContextStore`; values
can be updated at any time without recreating the agent.

```java
InstanceContextStore store = new InstanceContextStore();

AraRuntime runtime = AraRuntime.builder()
        .llmClient(llmClient)
        .instanceContextStore(store)
        .toolRegistryFactory(agentCfg -> {
            AgentInstanceContext ctx = store.forAgent(agentCfg.agentId());
            return new SimpleToolRegistry(new MyPrivateApiTool(ctx));
        })
        .build();

store.set(AgentId.of("buddy"), Map.of("api_key", "...", "tenant_id", "acme-corp"));

AgentContract contract = AgentContract.builder()
        .addPromptShaper(PromptTemplate
                .withInstanceContext(store.forAgent(AgentId.of("buddy")))
                .delimiters("{{", "}}"))
        .build();

runtime.createAgent(agentConfig, contract);

// hot update — no reload, no agent recreation; both the shaper and the tool see it
// on their very next invocation
store.set(AgentId.of("buddy"), Map.of("api_key", "...", "tenant_id", "globex-inc"));
```

`MyPrivateApiTool(ctx)` reads `ctx.get("api_key")` inside `execute(...)` — never part of
the tool's `argumentSchema()`, never seen by the LLM. `PromptTemplate.withInstanceContext`
reads the same live view on every `shape()` call (unlike `withDefaults(Map)`, which
freezes its values at construction time).

`AraRuntime.Builder.toolRegistryFactory(...)` is mutually exclusive with the simpler
`toolRegistry(...)` (a single registry shared by every agent) — use it when different
agents need different tool instances. `runtime.instanceContextStore()` exposes the shared
store (auto-created if not supplied); entries are cleared automatically when their agent
is destroyed via `destroyAgent(...)` or `stop()`.

---

## Agent scheduling

`LocalAgentScheduler` is created by the runtime, but agent schedules must be registered
explicitly via `AraRuntime.scheduler()`. A schedule fires either on a fixed interval or on
a cron expression:

```java
// fixed interval
runtime.scheduler().register(AgentSchedule.builder()
        .scheduleId("heartbeat")
        .agentId(agentId)
        .every(Duration.ofMinutes(10))
        .withInput("ping")
        .build());

// cron — every weekday at 09:00
runtime.scheduler().register(AgentSchedule.builder()
        .scheduleId("morning-report")
        .agentId(agentId)
        .cron("0 9 * * MON-FRI")
        .withInput("Generate the daily report")
        .build());
```

The 5-field cron format is `minute hour day-of-month month day-of-week`. Every field
supports the standard syntax — `*`, single values, ranges (`1-5`, `MON-FRI`, wrapping for
day-of-week), steps (`*/15`, `0-30/10`) and comma-separated lists (`1,15,30`,
`MON,WED,FRI`). Day-of-week accepts symbolic names and both `0` and `7` for Sunday. When
day-of-month and day-of-week are both restricted, standard cron OR semantics apply (fires
when either matches). `LocalAgentScheduler` holds schedules in memory — they do not
survive a process restart.