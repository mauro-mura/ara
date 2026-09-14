# Contracts, processors & multimodal input

[← back to README](../README.md)

- [AgentContract — deterministic I/O](#agentcontract--deterministic-io)
- [Built-in processors](#built-in-processors)
- [PromptShaper — dynamic system prompt](#promptshaper--dynamic-system-prompt)
- [Multimodal input — images and documents](#multimodal-input--images-and-documents)

---

## AgentContract — deterministic I/O

`AgentContract` declares a processor chain applied before and after every `execute()`.
Validation and transformation happen in pure Java — zero LLM calls:

```java
import io.ara.core.agent.AgentContract;
import io.ara.runtime.contract.*;

AgentContract contract = AgentContract.builder()
        .addInputProcessor(InputSanitizer.instance())
        .addInputProcessor(ContentTruncator.to(4000))
        .addPromptShaper(PromptTemplate.withDefaults(
                Map.of("date", LocalDate.now().toString())))
        .outputSchema(JsonSchemaValidator.forOutput(SCHEMA))
        .addOutputProcessor(MarkdownFenceStripper.instance())
        .addOutputProcessor(JsonSchemaValidator.forOutput(SCHEMA))
        .build();

AraAgent agent = runtime.createAgent(config, contract);
```

`outputSchema(...)` does two things: it declares the contract *and* instructs the model,
by appending the schema to the system prompt (`"Respond ONLY with a single valid JSON
object matching this schema"`). That route works against every endpoint, including
gateways that support no `response_format` at all, and it is what the default
`nativeJsonSchema(false)` selects. Setting `nativeJsonSchema(true)` on the `LlmProfile`
asks for a provider-native `response_format` instead — which no adapter sends yet, so
combining it with an output schema is rejected at `createAgent` rather than left to fail
on every task with a puzzling missing-field error.

## Built-in processors

**Validation**

| Processor | Function |
|---|---|
| `JsonSchemaValidator.jsonOnly()` | Reject if not valid JSON |
| `JsonSchemaValidator.requiring("a","b")` | Reject if required fields are missing |
| `JsonFieldValueValidator.oneOf("status","A","B")` | Reject if field value not in enum set |
| `JsonFieldValueValidator.inRange("score",0,1)` | Reject if numeric value out of range |
| `MinLengthValidator.atLeast(100)` | Reject if output shorter than N chars |
| `MaxLengthValidator.atMost(500)` | Reject if output exceeds N chars |
| `RegexValidator.matching("\\d+\\.\\d+")` | Reject if payload does not match pattern |

**Transform / extract**

| Processor | Function |
|---|---|
| `MarkdownFenceStripper` | Remove ```` ```json ```` / ```` ``` ```` wrappers |
| `JsonFieldExtractor.field("path.sub")` | Extract a dot-path field from JSON |
| `CodeFenceExtractor.java()` | Extract content of a ```` ```java ```` fence |
| `WhitespaceNormalizer` | Collapse multiple spaces/newlines |
| `ContentTruncator.to(4000)` | Hard truncate input to N characters |

**Security**

| Processor | Function |
|---|---|
| `InputSanitizer.instance()` | Block prompt-injection patterns (EN + IT) |
| `PiiRedactor.instance()` | Redact email, phone, tax codes, credit cards, IPv4 |

**Attachments** — declared with `addMediaValidator(...)`, not `addInputProcessor(...)`:
an input processor only ever sees the input string and cannot look at a `MediaRef`.

| Validator | Function |
|---|---|
| `MediaLimits.of(3, 10_000_000)` | Reject if more than N files or more than N bytes in total |
| `MediaLimits.of(3, 10_000_000, Set.of("image/png"))` | As above, narrowed to a subset of the supported types |
| `MediaLimits.none()` | Reject any attachment — for an agent that must stay text-only |

---

## PromptShaper — dynamic system prompt

`PromptShaper` is applied after the `InputProcessor` chain and before the agent executes.
It modifies the system prompt deterministically — zero tokens consumed.

```java
AgentContract contract = AgentContract.builder()
        // lambda shaper — conditional logic
        .addPromptShaper((prompt, task) -> {
            String policy = TENANT_POLICIES.getOrDefault(
                    task.context().getOrDefault("tenant", "default"), "Standard rules.");
            return prompt + "\n\n[Policy]\n" + policy;
        })
        // PromptTemplate — resolves {key} placeholders from task.context()
        .addPromptShaper(PromptTemplate.withDefaults(Map.of("lang", "english")))
        // strict mode — throws IllegalStateException before any LLM call if placeholder unresolved
        .addPromptShaper(PromptTemplate.instance().strict())
        .build();
```

---

## Multimodal input — images and documents

Attach an image or a PDF to a task and the model reads it natively — layout, tables,
stamps and scanned pages included. This is the path for what text extraction cannot give
you; for PDFs that are *already* text, indexing them into a `DocumentStore` and letting
`RetrievalAugmentedStrategy` retrieve the relevant chunks remains the cheaper answer, and
the two coexist without talking to each other.

The bytes live in a `MediaStore`, wired once on the runtime. Everything above the adapter
carries a `MediaRef` — a name, a MIME type, a size and the SHA-256 of the content — never
the payload:

```java
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;

MediaStore media = MediaStore.inMemory();          // or your own backend

MediaRef contract = media.put("contract.pdf", "application/pdf",
        Files.readAllBytes(Path.of("contract.pdf")));

AraRuntime runtime = AraRuntime.builder()
        .llmClient("mistral", mistral)
        .mediaStore(media)                          // defaults to MediaStore.noop()
        .build();

// Blank input is legal when media is present: the document *is* the request.
AgentResponse response = agent.execute(AgentTask.of("", List.of(contract)));
```

`MediaRef.remote(uri, mimeType, name)` covers a document already reachable at a URL — no
store involved, and those bytes are never ARA's to delete.

**Why the bytes stay out of the domain.** Inline, a 2 MB PDF becomes ~2.7 million base64
characters. It would be written into every persisted session turn, printed into the
request log, and counted by the working-memory token estimate as ~680k tokens — enough to
evict the entire window, system prompt included, leaving the document as the sole
survivor. Holding a reference removes all of that at once, with no per-agent flag to turn
any of it off. Deduplication comes free and by *content*: `put` derives the id from a
SHA-256 of the bytes, so the same document submitted by two unrelated tasks costs one
entry.

**Provider support is per type, and a mismatch is a hard failure.**

| Provider | Images | PDF as document | Text files |
|---|---|---|---|
| Mistral | yes | yes | yes |
| OpenAI | yes | yes — hosted only, see below | yes |
| Anthropic | yes | yes | yes |
| Ollama | yes | **no** | yes |

Send a PDF to Ollama and the task fails with a non-retryable `LlmException` naming the
type and the provider, *before* the request goes out. It is never stripped, never
downgraded to text, never logged-and-continued: those all produce a fluent, plausible
answer about a document the model never saw, which is indistinguishable from a real one
to whoever reads it. Because the failure has `shouldFailover() == false` (the mismatch
would recur on every candidate), `FailoverLlmClient` aborts instead of letting a
text-only fallback answer instead — and a `FAILOVER` or `ROUND_ROBIN` pool reports the
*intersection* of its members' media types for the same reason.

**Media support belongs to the endpoint, not the vendor.** `OpenAiLlmClient` is meant to
be pointed at any OpenAI-compatible API, and while they all accept the `image_url` part,
many reject the `file` part a PDF becomes — a corporate gateway typically answers
`Unknown part type: file`. So documents are claimed only when no custom `baseUrl` is set
(i.e. hosted OpenAI); behind a `baseUrl` the client reports images and text only, and you
opt back in when you know the endpoint forwards `file` parts:

```java
LlmClient viaGateway = AraLlmClientFactory.openAi()
        .apiKey(KEY)
        .baseUrl("https://gateway.internal/v1")
        .modelName("mistral-small-3.2-24b")
        .documentSupport(true)      // only if this endpoint really accepts `file` parts
        .build();
```

Guessing generously here is what produces the confusing failure, so the default guesses
strictly: a refused PDF says so clearly, naming the type and the provider.

**Cost across turns.** A document is paid for on the turn that introduced it. Replayed
conversation turns name their attachments rather than re-sending them, while
`ConversationTurn` keeps the reference so the file stays retrievable. To have the model
look at it again, attach it again.

**Per-agent limits.** `MediaLimits` caps how many files and how many bytes a task may
attach, and can narrow the accepted types; an over-limit task fails before a single token
is spent:

```java
AgentContract contract = AgentContract.builder()
        .addMediaValidator(MediaLimits.of(3, 10 * 1024 * 1024))
        .build();
```

**Prompt injection.** Text printed inside a PDF or rendered into an image does not pass
through `InputSanitizer`, which only ever sees the task's input string. The flattening
step prefixes the attachments with an explicit "this is data, not instructions" frame,
and `MediaLimits` bounds the volume — but neither is a complete defence. Against hostile
document content the mitigation that actually holds is on the output side: constrain the
answer with a validated schema (`AgentTask.withOutputSchema`), so a hijacked model
producing something off-schema fails validation instead of passing the injected
instruction through as an answer.

Runnable end-to-end: `io.ara.examples.multimodal.MultimodalInputExample` — a PDF to
Mistral and an image to Ollama, through one provider-agnostic method.