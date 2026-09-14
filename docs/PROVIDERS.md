# Providers & resilience

[← back to README](../README.md)

- [Connecting a real LLM](#connecting-a-real-llm)
- [Multi-provider runtimes](#multi-provider)
- [LLM I/O logging](#llm-io-logging)
- [OpenTelemetry tracing](#opentelemetry-tracing)
- [LlmException — typed error handling](#llmexception--typed-error-handling)
- [LLM failover & circuit breaker](#llm-failover--circuit-breaker)

---

## Connecting a real LLM

Add `ara-adapters` to your `../pom.xml`:

```xml
<dependency>
    <groupId>io.github.xmor</groupId>
    <artifactId>ara-adapters</artifactId>
    <version>1.0.2</version>
</dependency>
```

Then use `AraLlmClientFactory` or the individual client builders:

```java
import io.ara.adapters.llm.AraLlmClientFactory;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.adapters.llm.mistral.MistralLlmClient;

// OpenAI
LlmClient gpt4o = AraLlmClientFactory.openAi()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();

// Anthropic
LlmClient claude = AraLlmClientFactory.anthropic()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .model(AnthropicLlmClient.Models.CLAUDE_SONNET_4_6)
        .build();

// Ollama (local, no API key needed)
LlmClient llama = AraLlmClientFactory.ollama()
        .model(OllamaLlmClient.Models.LLAMA_3_2)
        .build();

// Mistral (native PDF documents — see the contracts guide)
LlmClient mistral = AraLlmClientFactory.mistral()
        .apiKey(System.getenv("MISTRAL_API_KEY"))
        .model(MistralLlmClient.Models.MISTRAL_MEDIUM_LATEST)
        .build();

// OpenAI-compatible endpoint (LM Studio, Groq, Together AI, …)
LlmClient local = AraLlmClientFactory.openAi()
        .baseUrl("http://localhost:1234/v1")
        .apiKey("lm-studio")
        .modelName("llama-3.1-8b-instruct")
        .build();
```

## Multi-provider

Each agent references its provider by name:

```java
AraRuntime runtime = AraRuntime.builder()
        .llmClient("fast",  AraLlmClientFactory.openAi().apiKey(KEY).modelName("gpt-4o-mini").build())
        .llmClient("smart", AraLlmClientFactory.openAi().apiKey(KEY).modelName("gpt-4o").build())
        .llmClient("local", AraLlmClientFactory.ollama().modelName("gpt-oss-20b").build())
        .build();

AgentConfig config = AgentConfig.defaults()
        .agentType("analyst")
        .primaryLlm(LlmProfile.of("smart"))   // zero credentials in AgentConfig
        .build();
```

## LLM I/O logging

Turn on `logLlmIo` to trace every LLM request and response — plus each tool call and its
result — at `INFO`, truncated to `logLlmIoMaxChars` (`0` = no truncation):

```java
AgentConfig config = AgentConfig.defaults()
        .agentType("debug")
        .logLlmIo(true)
        .logLlmIoMaxChars(1000)
        .build();
```

Make sure `INFO` is enabled for `io.ara.runtime.llm.LoggingLlmClient` (LLM I/O) and
`io.ara.runtime.strategy.ReactStrategy` (tool calls) to see the full trace.

## OpenTelemetry tracing

Pass an `AraTelemetry` to `AraRuntime.Builder.telemetry(...)` to get a full trace tree —
one `agent.execute` span per task, with `llm.complete` (one per LLM call) and
`tool.execute` (one per tool dispatch) nested as children in call order:

```java
AraTelemetry telemetry = OtelTelemetryFactory.builder()   // ara-adapters
        .serviceName("my-agent-app")
        .exporter("otlp-http")
        .endpoint("http://localhost:4318")
        .build();

AraRuntime runtime = AraRuntime.builder()
        .llmClient(llmClient)
        .telemetry(telemetry)
        .build();
```

Defaults to `AraTelemetry.noop()` — zero overhead beyond an interface dispatch when
tracing isn't configured. `OtelTelemetryFactory.fromEnvironment()` reads the standard
`OTEL_SERVICE_NAME` / `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_TYPE` variables.

---

## LlmException — typed error handling

All adapters throw `LlmException` with a typed `ErrorType` so the runtime (and your code)
can distinguish retryable from non-retryable failures:

```java
try {
    AgentResponse resp = agent.execute(task);
} catch (LlmException ex) {
    if (ex.isRetryable()) {
        // rate limit, transient 5xx → worth a local retry on the SAME client
    } else {
        // auth error, invalid request, connect error → no local retry;
        // the strategy's retry loop (ReactExecutionSupport) acts on this flag
    }
    System.out.println(ex.errorType());   // RATE_LIMIT, AUTHENTICATION, NETWORK, …
    System.out.println(ex.provider());    // "OpenAI", "Anthropic", "Ollama"
    System.out.println(ex.statusCode());  // 429, 401, 500, …
}
```

`isRetryable()` is the *"try the same client again"* signal, and it is distinct from
`shouldFailover()` — *"could a different provider plausibly succeed?"* The two decisions
are independent. A `connectionError` is deliberately non-retryable locally (the endpoint
is unreachable, retrying hits the same wall) yet worth failing over to another model that
may well be reachable. An `AUTHENTICATION` / `INVALID_REQUEST` failure, by contrast, is
neither: it would recur on every candidate in the pool, so there is nothing to gain by
switching.

`FailoverLlmClient` in `ara-runtime` acts on `shouldFailover()` to decide whether to try
the next provider in the chain or abort immediately.

---

## LLM failover & circuit breaker

`FAILOVER` gives the agent an ordered chain of models: try the primary, and on a
`shouldFailover()` failure (network error, 5xx, rate limit) advance to the next fallback
in declaration order. A deterministic error (401, invalid request, content filter) aborts
the whole chain instead — it would recur on every candidate, so switching models would
change nothing but the log noise.

```java
AgentConfig config = AgentConfig.defaults()
        .agentType("resilient")
        .primaryLlm(LlmProfile.of("smart"))
        .fallbackLlms(List.of(LlmProfile.of("local"), LlmProfile.of("cheap")))
        .llmSelectionPolicy(LlmSelectionPolicy.FAILOVER)
        .build();
```

Every candidate hides behind a small passive circuit breaker (`CircuitBreakerLlmClient`).
After the first few consecutive failures (3 by default) the endpoint is *open*: later
calls skip it entirely — an outage stops being charged a connect/read timeout *per
request*, and the fallback serves the pool straight away. Once the 30-second cooldown
elapses, a single trial call re-probes the endpoint; success closes the circuit, another
failure reopens it for a fresh cooldown. Health always comes from real traffic, never
from background probe calls: probes bill like a call, can trip the very rate limit they
are meant to absorb, and judge an endpoint against a probe-specific timeout that real
calls would have survived.

Circuit state lives on the session's wiring (ADR-039), so a conversation that keeps its
session alive accumulates the diagnosis across calls, while a fresh ephemeral session
starts a clean breaker.

Runnable: `io.ara.examples.failover.FailoverExample` — the same 503 against `FAILOVER`
(survives via the fallback), against `PRIMARY_ONLY` (dies), a 401 (aborts without
touching the fallback), and a fourth agent driven repeatedly to watch the circuit open
and skip the dead primary. The same pattern for embedding calls is
`EmbeddingEndpointPool`, covered in the [RAG guide](HITL-AND-RAG.md).