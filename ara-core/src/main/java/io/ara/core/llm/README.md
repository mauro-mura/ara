# io.ara.core.llm

Provider-agnostic abstraction over LLM completions: the interfaces, data model, and
configuration types that let `ara-runtime` drive any model (OpenAI, Anthropic, Ollama, …)
without depending on a specific SDK. Concrete adapters live in `ara-adapters`.

## Core interfaces

- **`LlmClient`** — the single entry point to a model. Primary method is
  `complete(List<LlmMessage>, LlmCallContext)`. The `complete(List, AgentConfig)` /
  `stream(List, AgentConfig)` overloads are deprecated bridges (ADR-017) kept for
  backward compatibility; new code should use the `LlmCallContext` overloads.
- **`LlmRouter`** — selects which `LlmClient` to use for a call, applying
  `LlmSelectionPolicy` (`PRIMARY_ONLY`, `FAILOVER`, `ROUND_ROBIN`). Default
  implementation is `DefaultLlmRouter` in `ara-runtime`.
- **`LlmClientFactory`** — functional interface for building an `LlmClient` from a full
  `LlmProfile`, wired by `AraPlatformFactory`. Takes the whole profile (not just
  `baseUrl`/`apiKey`/`modelName`) so implementations can honor `streamingEnabled()`,
  `nativeJsonSchema()`, and the cost fields on the inline-override path too.

## Data model

- **`LlmMessage`** — one turn in the conversation. `role` is a plain `String`
  (`"system"`, `"user"`, `"assistant"`, `"assistant_tool_call"`, `"tool"`) rather than
  an enum; use the static factories (`system`, `user`, `assistant`, `assistantToolCall`,
  `tool`) instead of constructing roles by hand.
- **`LlmCompletion`** — the result of a call. Supports both a single legacy tool call
  (`toolCallJson` / `toolCallId`) and multiple parallel tool calls (`toolCalls`, a list
  of `ToolCallEntry`). When `toolCalls` is non-empty it takes precedence; use
  `hasToolCall()` rather than checking either field directly.
- **`ToolCallEntry`** — one normalised tool invocation inside a multi-call completion.
- **`LlmException`** — typed failure with `ErrorType` and `isRetryable()`. Adapters map
  provider errors to it via factory methods (`rateLimit`, `authenticationError`,
  `networkError`, `serverError`, …); `FailoverLlmClient` uses `isRetryable()` to decide
  whether to try the next profile or propagate.

## Configuration hierarchy

Three layers, increasing in specificity — later layers win:

1. **`LlmProfile`** — static per-model config (modelId, temperature, topP, cost, base
   URL/API key, streaming/JSON-schema support). One profile per model.
2. **`LlmConfig`** — an agent's `primary` profile plus `fallbacks` and the
   `LlmSelectionPolicy` used to choose between them. Part of `AgentConfig`.
3. **`LlmCallContext`** — per-call overrides (output JSON schema, temperature, stop
   sequences, seed, provider override) layered on top of an `AgentConfig`/`LlmProfile`
   via `LlmCallContext.of(config, task)`. Built fresh by the strategy for every
   invocation; never reused across tasks.

`LlmExecutionHints` is the channel (`AgentTask.hints()`) a caller uses to request
per-call overrides without touching the data contract (`AgentContract`) — it describes
*how* the model is called, not the shape of the output.

`MemoryConfig` is unrelated to model selection: it's the working-memory/conversation
sub-record of `AgentConfig` (token budget, eviction strategy, reflection settings).

## Notes for implementers

- `temperature`/`topP` can be set on `LlmProfile`, overridden per-call on
  `LlmCallContext`, and are also exposed via `AgentConfig` delegate methods — when
  reading effective values, always go through `LlmCallContext.temperature()` (applies
  the override) rather than reaching into `LlmProfile` directly.
- Adapters must never return `null` from `aiMessage().text()`-style calls — the
  `LlmCompletion` constructor rejects a null `text`; fall back to `""`.
- `LlmCallContext` also carries `resolvedTools` (a `List<AraTool>`) when the calling
  strategy has already resolved tools for the step — clients must use these instead of
  resolving from their own registry when present (`hasResolvedTools()`).

### Architectural note: `resolvedTools` on `LlmCallContext`

Tool availability is conceptually a different concern from the sampling parameters
(temperature, schema, stop sequences, seed) that otherwise make up `LlmCallContext`.
It lives there anyway because every adapter (OpenAI, Anthropic, Ollama) already reads
tool specs off the call context it's given, and `AraTool` is itself a `ara-core` domain
type — so this isn't a module-boundary violation, just a widening of the class's
responsibility. The cleaner long-term shape is a dedicated parameter on
`LlmClient.complete(messages, context, tools)`, but that means changing the primary
`LlmClient` signature across every adapter and strategy call site. Do that split
together with the next change that already has to touch every adapter, rather than as
an isolated refactor.

Note also that `LlmCallContext` used to carry a second, redundant tool-related field —
`enabledTools` (`List<String>`, copied straight from `AgentConfig`) — that no strategy
or adapter ever read from the call context (they read `AgentConfig.enabledTools()`
directly and resolved into `resolvedTools` themselves). It was dead weight and has been
removed; `resolvedTools` is the only tool-related field on this class now.