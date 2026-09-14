# RAG & human-in-the-loop

[← back to README](../README.md)

- [Knowledge base / RAG retrieval](#knowledge-base--rag-retrieval-in-memory-or-qdrant)
- [Embedding endpoint failover](#embedding-endpoint-failover)
- [RAG-as-a-strategy vs. a search tool](#rag-as-a-strategy-vs-a-search-tool)
- [Human-in-the-loop (HITL) — approval gate](#human-in-the-loop-hitl--approval-gate)

---

## Knowledge base / RAG retrieval (in-memory or Qdrant)

Retrieval enters the runtime through one port, `Retriever`. `InMemoryDocumentStore` needs
no external infrastructure (brute-force cosine over the indexed chunks — fine up to ~10k
chunks); `DocumentStore` is the Qdrant-backed equivalent, behind the same contract. Both
also implement `KbStore`, which adds indexing and document management on top of
`retrieve`.

```java
EmbeddingClient embeddings = AraEmbeddingClientFactory.openAi()
        .modelName("text-embedding-3-small")
        .dimensions(1536)
        .endpoint(System.getenv("OPENAI_API_KEY"))
        .build();

InMemoryDocumentStore kb = new InMemoryDocumentStore("ara-docs", embeddings);
kb.ensureCollection();
kb.indexDocument("adr-016", "Session isolation", "…");

AraRuntime runtime = AraRuntime.builder()
        .llmClient("live", gpt4o)
        .retriever(kb)                 // or .retriever("docs", kb) to name it
        .build();
```

For persistent vector search, swap the store — nothing else changes:

```java
QdrantConfig qdrant = QdrantConfig.builder()
        .host("localhost").port(6334)
        .collectionName("my-docs")
        .build();

DocumentStore kb = new DocumentStore(qdrant, embeddings);
```

(`QdrantSemanticStore` is a different thing — agent *episodic memory*, a separate
collection — and is not a `Retriever`.)

**Resilience across endpoints of the same model.** Chaining more than one `.endpoint(...)`
call on an embedding builder fails over across them in declaration order:

```java
EmbeddingClient embeddings = AraEmbeddingClientFactory.openAi()
        .modelName("text-embedding-3-small")
        .dimensions(1536)
        .endpoint(System.getenv("OPENAI_API_KEY"))                           // primary
        .endpoint("https://my-gateway.internal/v1", System.getenv("GW_KEY")) // failover
        .build();
```

Every endpoint on one builder must reach the *same* model — that is what the API
structurally enforces by only ever giving you an endpoint list, never a list of
separately built clients. Two embedding vectors from different models are not comparable
even at equal `dimensions()`, because they live in different latent spaces; a pool that
could mix models would silently corrupt whatever vector store it feeds the moment it
failed over.

## Embedding endpoint failover

`EmbeddingEndpointPool` wraps several embedding clients of the *same* model and applies
the same rule as the [LLM chain](PROVIDERS.md#llm-failover--circuit-breaker): on a
`shouldFailover()` failure (network, 5xx, rate limit) it advances to the next endpoint in
declaration order, and `lastUsedEndpoint()` names the one that served the call. A
deterministic error (401, invalid request) aborts without touching the fallbacks, for the
same reason it aborts the LLM chain — it would recur on every endpoint.
(`EmbeddingException` mirrors `LlmException`: both reduce to the same provider-agnostic
`ErrorCategory`, so `shouldFailover()` reasons about the two identically.) Both are
exercised end-to-end in `io.ara.examples.failover.FailoverExample`.

The stores themselves can be pooled too, for a primary/replica setup:
`io.ara.adapters.resilience.FailoverRetriever` wraps several `Retriever`s (read-only,
ordered failover), and `FailoverSemanticStore` wraps several `SemanticStore`s (writes fan
out to every replica, reads fail over in order). Both fail over only on an exception,
never on an empty result — a healthy store with no hits is a correct answer, not a
failure.

Registering at least one retriever makes `AraRuntime` auto-register the RAG-wrapped
strategies — `"rag+react"`, `"rag+respact"`, `"rag+plan_execute"` and `"rag+reflact"` —
each backed by a `RetrieverRouter` over everything registered. An agent opts in by naming
one:

```java
AgentConfig config = AgentConfig.defaults()
        .agentType("kb-agent")
        .plannerStrategy("rag+react")   // retrieval before every LLM call
        .retrieverId("docs")            // optional; the default retriever otherwise
        .build();
```

`retrieverId` without a `rag+…` strategy is rejected at construction rather than silently
ignored.

## RAG-as-a-strategy vs. a search tool

The above retrieves *before every LLM call*, with no tool involved and nothing for the
model to decide. The alternative is letting the agent search on its own: set
`knowledgeBaseId(...)` and enable a `search_documents` tool, and the runtime attaches a
`KnowledgeBasePromptShaper` that tells the model how to call it. The tool implementation
itself is yours — ARA ships the prompt shaping and the stores, not the tool.

---

## Human-in-the-loop (HITL) — approval gate

Agents with `humanApprovalRequired(true)` route every tool call through an `ApprovalGate`
before dispatch. The calling virtual thread parks cheaply (Project Loom) until a human
decision arrives or the request times out.

**Setup:**

```java
ApprovalGate gate = new InMemoryApprovalGate();

AraRuntime runtime = AraRuntime.builder()
        .llmClient(llmClient)
        .approvalGate(gate)
        .build();

AraAgent agent = runtime.createAgent(AgentConfig.builder()
        .agentId("payment-agent")
        .agentType("finance")
        .humanApprovalRequired(true)
        .enabledTools(List.of("process_payment", "refund"))
        .build());
```

When the agent calls a tool, `ApprovalToolRegistry` intercepts and:

1. Creates an `ApprovalRequest` (UUID, agentId, toolId, arguments, expiry).
2. Calls `gate.requestApproval(request)` — returns a `CompletableFuture<ApprovalDecision>`.
3. The virtual thread parks on `.join()` until the future resolves.
4. On `Approved` → dispatches the tool normally.
5. On `Rejected` → returns a failed `ToolResult` (the agent sees "Human rejected action: …").
6. On `Modified` → dispatches with the revised payload.
7. On timeout → returns a failed `ToolResult`.

**Resolving approvals programmatically:**

```java
List<ApprovalRequest> pending = gate.getPendingRequests();

gate.submit(requestId, new ApprovalDecision.Approved());
gate.submit(requestId, new ApprovalDecision.Rejected("Too expensive"));
gate.submit(requestId, new ApprovalDecision.Modified(newArgumentJson));
```

**Resolving approvals via HTTP** — with `ara-gateway`, the optional HTTP layer that ships
separately from this build:

```bash
# List pending approvals
curl http://localhost:8080/approvals

# Approve
curl -X POST http://localhost:8080/approvals/{requestId}/decision \
  -H "Content-Type: application/json" \
  -d '{"decision": "approved"}'

# Reject with reason
curl -X POST http://localhost:8080/approvals/{requestId}/decision \
  -H "Content-Type: application/json" \
  -d '{"decision": "rejected", "reason": "Amount exceeds limit"}'

# Modify payload
curl -X POST http://localhost:8080/approvals/{requestId}/decision \
  -H "Content-Type: application/json" \
  -d '{"decision": "modified", "newPayload": {"amount": 50.00}}'
```

Enable the `APPROVALS` surface in the gateway config (`ara.yml`):

```yaml
ara:
  gateway:
    enabled: true
    surfaces: [system, discovery, runs, control, sessions, approvals]
```

**Notifications:** wire a notifier to alert operators when a request is pending:

```java
WebhookApprovalNotifier notifier = WebhookApprovalNotifier.builder()
        .url("https://slack-hook.example.com/hitl")
        .header("Authorization", "Bearer " + slackToken)
        .build();
```

> **Design note:** the approval gate is opt-in at two levels —
> `AraRuntime.Builder.approvalGate(gate)` enables the feature globally, and
> `AgentConfig.humanApprovalRequired(true)` enables it per agent. If no gate is
> configured, the flag is inert (no error, no approval). If the gate is configured but
> `humanApprovalRequired` is `false`, tool calls bypass the gate entirely.