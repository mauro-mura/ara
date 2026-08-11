# ARA — Advanced usage

Topics here go beyond the main [README](../README.md): registering your own
`ExecutionStrategy` on the runtime, and how to actually see what gets sent to
the LLM when you're debugging one.

## Table of contents

- [Registering a custom `ExecutionStrategy`](#registering-a-custom-executionstrategy)
- [Tracing what actually reaches the LLM](#tracing-what-actually-reaches-the-llm)

---

## Registering a custom `ExecutionStrategy`

The built-in strategies (`react`, `respact`, `plan_execute`, `reflexion`,
`reflact`, and the `rag+*` variants) are registered on `AraRuntime.Builder`
through the exact same public API available to your own code:
`Builder.extraStrategies(ExecutionStrategy...)`.

### 1. Implement the interface

```java
public final class MyStrategy implements ExecutionStrategy {

    @Override
    public String strategyName() {
        return "my-strategy";   // the key AgentConfig.plannerStrategy() will use
    }

    @Override
    public ExecutionResult execute(
            AgentTask task, LlmClient llm, MemoryManager memory,
            ToolRegistry tools, AgentConfig config) {
        // your reasoning loop
    }
}
```

`AgentInstance` calls `execute(...)` **exactly once** per task — your strategy
owns its own internal loop up to `config.maxIterations()` and returns a single
`ExecutionResult.success(...)`/`.failure(...)`. See
[`io/ara/runtime/strategy/README.md`](../ara-runtime/src/main/java/io/ara/runtime/strategy/README.md)
for the full contract the built-in strategies follow.

### 2. Register it on the runtime builder

```java
AraRuntime runtime = AraRuntime.builder()
        .llmClient(myLlmClient)
        .extraStrategies(new MyStrategy())
        .build();
```

This lands in the same `ExecutionPlanner` the built-ins use
(`AraRuntime.Builder.buildExecutionPlanner()` calls
`extraStrategies.forEach(plannerBuilder::register)` after registering
`react`/`respact`/`plan_execute`/`reflexion`/`reflact`).

### 3. Select it per agent

```java
AgentConfig config = AgentConfig.defaults()
        .agentType("my-agent")
        .plannerStrategy("my-strategy")   // must match strategyName() exactly
        .build();
```

### 4. Compose with RAG (optional)

Like any other strategy, yours can be wrapped for retrieval augmentation
before registering it:

```java
.extraStrategies(RetrievalAugmentedStrategy.wrap(new MyStrategy(), retriever))
```

### Gotchas

- **Silent fallback on a name mismatch.** `ExecutionPlanner.select(...)` does
  not throw when `plannerStrategy()` doesn't match anything registered — it
  logs a `WARN` ("Strategy [...] not registered; falling back to default
  [react]") and runs `react` instead. If your strategy appears to be ignored,
  check the logs for that line before suspecting the loop logic itself — it's
  almost always a typo between `strategyName()` and the value passed to
  `plannerStrategy(...)`.

- **`StrategyConfig` is sealed** (`React` / `PlanExecute` / `Reflexion` /
  `ReflAct`, declared in `ara-core`) and cannot be extended with a new
  permitted type from outside that module. If your strategy needs typed,
  per-agent parameters, don't try to add a `StrategyConfig` variant for it —
  read what you need directly off `AgentConfig` (`enabledTools()`,
  `maxIterations()`, …) or via the agent's instance-parameter mechanism
  instead.

- **`ReactExecutionSupport` is package-private.** The shared ReAct loop
  machinery (`buildMessages`, `decideNextStep`, `dispatchParallel`,
  `maybeInjectSynthesis`, `streamAndCollect`, …) lives in
  `io.ara.runtime.strategy` and isn't part of the public API. A strategy
  implemented in your own application package cannot call it directly — you
  either re-implement the bits you need (tool dispatch, streaming) or place
  your class inside `io.ara.runtime.strategy` if you're extending ARA itself
  rather than consuming it as a library.

---

## Tracing what actually reaches the LLM

Two things are easy to misread when you turn logging on and expect to see
ARA's own ReAct scaffolding (the tool catalog text, the `FINAL_ANSWER`
sentinel, the "wrap up now" synthesis nudge) in the system prompt.

### Native function-calling suppresses the text protocol

`ReactExecutionSupport.buildMessages(...)` (and the equivalent in
`ReSpActStrategy`) only appends the tool catalog and the ReAct/ReSpAct system
suffix when the LLM client does **not** speak native function-calling:

```java
String toolCatalog = (nativeTools || resolvedTools.isEmpty()) ? "" : ToolCatalogFormatter.format(resolvedTools);
String reactSuffix = (nativeTools || resolvedTools.isEmpty()) ? "" : REACT_SYSTEM_SUFFIX;
```

`OpenAiLlmClient` and `AnthropicLlmClient` both report
`supportsNativeTools() == true`. With either of them, the system message sent
on every call is **exactly** what your `AgentContract`'s `PromptShaper` chain
produced — no ReAct instructions, no inline tool list, added by the loop
itself. This is intentional: that text scaffolding would be redundant with,
and can actively compete against, the structured tool-calling channel. The
same gating applies to the two optional clauses of the synthesis nudge that
fires near `maxIterations` — the `file_write` persist clause (only added when
that tool is actually enabled) and the `Action: FINAL_ANSWER` sentinel (only
added for non-native clients).

If tools are enabled, they aren't missing — they travel in the request's
structured `tools`/`toolSpecifications` field (built by
`ToolConversionUtils.toolSpecificationsFor(context)`), not as text in the
system message. Check that field in the logged payload, not the system
message content, when verifying tool visibility for a native client.

### Two independent logging paths — don't conflate them

- **ARA's own message-level log**: `LoggingLlmClient`, wired in by
  `DefaultWiringFactory.build(...)` only when `AgentConfig.logLlmIo()` is
  `true`. It logs the full `List<LlmMessage>` — the exact conversation ARA
  built, before any provider-specific conversion — at `INFO`, truncated to
  `logLlmIoMaxChars()`. This is the log to enable if you want to see precisely
  what a strategy assembled.
- **The provider adapter's own request/response log**: e.g.
  `OpenAiLlmClient.Builder.logRequests(true).logResponses(true)`, which is
  forwarded straight to LangChain4j's `OpenAiChatModel` and logs the raw HTTP
  payload actually sent to the provider. It reflects the same final content,
  but through a completely different logger and mechanism than
  `LoggingLlmClient` — enabling one does not enable, or imply, the other.

If you only enabled the provider-level flag and see no ReAct suffix in the
system message, that's consistent with a native-tools client, not a
misconfiguration — see the section above. Enable `AgentConfig.logLlmIo(true)`
as well if you want ARA's own view of the message list for comparison.
