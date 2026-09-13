# io.ara.runtime.auth

This package implements ARA's authorization model (ADR-033): it decides whether a
caller may reach a given agent or tool. The model has two layers — OAuth-style scope
checks that always run, and an opt-in ABAC layer — plus a human approval gate wired
between them. The enforcement code lives here (`io.ara.runtime.auth`); the attribute
types and the policy contract live in `io.ara.core.auth`, and this package is the
reference implementation of that contract.

The full rationale behind the model lives in ADR-033,
referenced per-file throughout. This README is written so the concepts
stand on their own: the ADR is a pointer to the origin story, not a prerequisite to
reading the code.

Cards-on-the-table about what this package *is not*: it does not turn an incoming HTTP
token into a `ScopeSet` — that is `ara-gateway`'s job, and `ara-gateway` is not part of
this repo. The model stops at "a caller supplies a `ScopeSet` and an `ExecutionContext`".

## The model in one page

Four foundational ideas make the rest of this file readable. None of them is ARA
inventing something new; the first two are how OAuth-style authorization works, the
third and fourth are how ABAC works.

**1. Scopes are opaque capability tokens.** A *scope* is a short string like
`"finance:read"`. ARA never parses it — only string equality matters. Every caller
subject to authorization holds a set of scopes; every agent or tool declares the scopes
a caller must present (`requiredScopes`) and the scopes a caller needs to even *see* it
(`visibleToScopes`). `ScopeSet` is the container for these sets, and four operations
cover the whole model:

- `grants(required)` — does my set contain *every* scope the target requires? This is
  the "may I call you" check.
- `visibleTo(effective)` — do my set and the target's visibility set share *at least
  one* scope? This is the "may I see you" check (discovery/catalog).
- `union` — add scopes. Used where authority may only grow: a temporary grant (Fase 8)
  or multi-source effective scopes.
- `intersect` — narrow scopes. Used where authority is meant to shrink: delegation
  attenuation (Fase 5).

An empty declared set is "open to everyone" on both sides — an agent with no
`requiredScopes` grants to anyone, an agent with no `visibleToScopes` is visible to
anyone. **Enforcement is opt-in by design**: it activates only the moment someone sets
one of those fields.

**2. Three layers, checked in a fixed order.** On every gated path the checks pile up,
each one *necessary but not sufficient* for the next:

1. **Scopes** (Fase 2) — always. The caller must be authorized and, on the bus, the
   target opted-in cases apply first.
2. **Human approval gate** (Fase 7) — only when the *target* opted in
   (`requiresApproval()`) *and* an approval gate is configured. An operator approves or
   rejects the act of invoking the agent, before it runs. Reuses ADR-048's
   `ApprovalGate`/`ApprovalDecision` machinery rather than building a parallel one.
3. **ABAC policy** (Fase 2b) — only when an `AbacPolicyEngine` is configured. A policy
   fold computes a final permit/deny from call attributes.

So the strongest possible evaluation is scopes **and** an operator **and** policy — and
each weaker configuration simply drops a layer, never reorders one.

**3. ABAC decides from attributes, not roles.** Attribute-Based Access Control computes
a decision on the fly from attributes of the call, grouped into the four categories the
XACML standard defined: **subject** (who is calling), **resource** (what is being
called), **action** (what operation), **environment** (context — time, hop depth). There
are no preassigned role-to-permission tables; a policy is a pure function of those
attributes, so the same call can be decided differently at 9am and at midnight. Those
four categories are exactly the four fields of `PolicyEvaluationContext`.

**4. Policies answer with three values, not two.** An ABAC policy returns `PERMIT`,
`DENY`, or `NOT_APPLICABLE` — the last meaning "not my case, look at the other
policies". This third value is what makes several policies composable: combined under
Deny-Overrides, each policy only has to speak when its concern applies (a clearance
policy stays quiet for public data), instead of every policy having to explicitly permit
every call. ARA's `CompositeAbacPolicyEngine` folds an ordered list of such trinary
answers into one final decision.

One more idea belongs here because it recurs: **delegation attenuation** (Fase 5). When
agent A delegates a sub-task to agent B, B runs with A's scopes — narrowed, never
widened — and the narrowing is applied *exactly once, at the delegation boundary*, not
re-intersected at every bus hop. The recipient's `ExecutionContext` records the
effective scopes, and the environment attribute `delegationDepth` counts how many hops
deep the call is, so a policy (`DelegationDepthPolicy`) can act as a circuit breaker on
runaway delegation chains.

## The ADR-033 phases at a glance

The model was built in phases (Fase 1–9 in ADR-033). Per-file references like "Fase 2b, S8" point at numbered sections of
that plan; here is the map so a reference never blocks reading:

| Phase | What it delivered | Where it lives |
|---|---|---|
| Fase 1 | The core attribute types and exception — the vocabulary | `io.ara.core.auth` |
| Fase 2 | The first code that actually enforces: `ScopeVerifier` | this package |
| Fase 2b | The opt-in ABAC layer | engine + `policy/` in this package |
| Fase 5 | Delegation attenuation — `ExecutionContext`, scopes narrowing at the hop | `LocalMessageBus`, `AgentDelegationTool` |
| Fase 7 | Human approval gate on *invoking* an agent | `ScopeVerifier.checkApproved` + ADR-048 gate |
| Fase 8 | Temporarily granted scopes, bounded in time/use-count | `TemporaryScopeRegistry` |
| Fase 9 | Multi-tenant isolation via the `"<tenant>:"` scope prefix | `TenantScopeHelper` |

## Classes

| Class | Role |
|---|---|
| `ScopeVerifier` | Stateless, `static`-method scope/enforcement checks (Fase 2): `checkAuthorized` (message *recipient*), `checkVisible` (discovery/catalog views), `checkTool` (tool invocations), `checkApproved` (Fase 7 — HITL gate on *invoking* an agent). |
| `AuthorizationService` | The facade a caller invokes explicitly: scope check unconditionally, then the configured `AbacPolicyEngine` when one is set (Fase 2b §2b.4). Exposed as `AraRuntime.authorizationService()`. |
| `CompositeAbacPolicyEngine` | Reference `AbacPolicyEngine`: a named, ordered list of policies folded by a combining algorithm (XACML-style, restricted to `DENY_OVERRIDES` / `PERMIT_OVERRIDES`). |
| `AbacPoliciesBuilder` | The fluent accumulator behind `AraRuntime.Builder.abacPolicies(...)`: `.add(name, policy).combineWith(algorithm)`. Defaults to `DENY_OVERRIDES`. |
| `TemporaryScopeRegistry` | Store of active `ScopeGrant`s per agent (Fase 8): consulted by `LocalMessageBus` on every dispatch, backed by `AraRuntime.grantTemporaryScope`. |
| `InMemoryTemporaryScopeRegistry` | Reference in-memory implementation — grants die with the process; method-level `synchronized` (rare, human-timescale operations). |
| `TenantScopeHelper` | Convention helper for the `"<tenant>:"` scope prefix (Fase 9) — builds scopes consistently so callers never hand-concatenate. |

The reference ABAC policies live in `io.ara.runtime.auth.policy`:

| Class | Policy | Nearest prior art |
|---|---|---|
| `ClearanceLevel` | The hierarchy `STANDARD < SENSITIVE < CONFIDENTIAL < SECRET`; declaration order *is* the comparison. | Military clearance tiers |
| `ClearancePolicy` | Denies unless subject clearance ≥ resource's declared `requiredClearance`; abstains when the resource declares none. | Bell–LaPadula-style "no read up" |
| `BusinessHoursPolicy` | Denies calls on `dataClassification = "critical"` resources outside a configured window. | AWS service control policies scoped in time |
| `DelegationDepthPolicy` | Denies past a configured delegation depth — a circuit breaker on delegation chains. | Extra-hop guard on ACM-style delegation |
| `TenantIsolationPolicy` | Denies when the subject's `tenantId` ≠ the tenant prefix of the resource's `agentId`. | Same `"<tenant>:"` convention as `TenantScopeHelper`, applied to agent ids |

## Scope checking: `ScopeVerifier`

`ScopeVerifier` is Fase 2, and it is deliberately *stateless and static* — there is
nothing to instantiate, no engine holding state, just four pure checks. Every check is a
**no-op today for anyone who has not opted in**: as covered above, an empty declared set
means "open to everyone" on both sides of `ScopeSet.visibleTo` / `ScopeSet.grants`, so
calling these methods against the current, entirely unconfigured agent population changes
nothing. Enforcement only activates the moment a caller sets one of those fields — the
checks exist to be wired, not to gate anything by default.

- `checkAuthorized(target, effective)` — `AGENT_NOT_AUTHORIZED` if `effective` does not
  grant `target`'s `requiredScopes` (i.e. `effective.grants(required)` is false). This is
  the chokepoint `LocalMessageBus` calls on *every* dispatch, request or fire-and-forget.
- `checkVisible(target, effective)` — `AGENT_NOT_VISIBLE` if `target`'s
  `visibleToScopes` shares nothing with `effective`. Used by `FilteredAgentView`'s
  discovery/catalog paths, which filter *by visibility alone*.
- `checkTool(tool, effective)` — `TOOL_NOT_AUTHORIZED` if `effective` does not grant the
  tool's `requiredScopes`. Called by `ScopeFilteringToolRegistry` when a tool is resolved
  by id — a defense-in-depth second layer under the visible tool outbox.
- `checkApproved(target, gate, actorId, effective)` — Fase 7 (S4): the HITL gate on
  *invoking* `target`. A no-op unless `config.requiresApproval()` is true *and* a
  non-null `ApprovalGate` is configured. Throws `APPROVAL_REQUIRED` if the gate rejects,
  times out (default 30 minutes), or otherwise fails to approve.

`checkApproved` is worth reading carefully, because it is **not** `ApprovalToolRegistry`:
that registry gates `target`'s own *outgoing* tool calls; `checkApproved` gates the act
of delegating *to* `target` in the first place, and runs only after `checkAuthorized`
has already passed — scopes are necessary but not sufficient. It reuses the existing
ADR-048 `ApprovalGate`/`ApprovalDecision` machinery rather than building a parallel
approval mechanism. Concretely it raises an `ApprovalRequest` (act described as
`"invoke:<agentId>"`, carrying the caller's scopes and a 30-minute deadline) and treats
the decision as: `Rejected` → denied; `Approved` or `Modified` → allowed — `Modified` is
approval here because this is an authorization gate, not a payload-rewriting one (that is
`ApprovalToolRegistry`'s job on the tool-call path).

## `AuthorizationService` — the explicit facade

`AuthorizationService` is Fase 2b §2b.4's facade: construct it with an `AbacPolicyEngine`
(anything) or `null` (ABAC disabled), then call

```java
service.authorize(callerScopes, targetAgent, policyContext);
```

which runs `ScopeVerifier.checkAuthorized` unconditionally, then — only when ABAC is
enabled — evaluates the configured engine, throwing `ABAC_POLICY_DENIED` on a `DENY`.
`abacEnabled()` reports whether a non-null engine was supplied.

Crucially, **nothing in the runtime calls `authorize` automatically.** `AraRuntime`
builds the service and exposes it via `authorizationService()`, but `AgentConfig` carries
no `dataClassification`/`clearanceLevel`/`tenantId` fields from which a
`PolicyEvaluationContext` could be populated at dispatch time — and inventing placeholder
values there would be worse than not integrating at all: *"a security check that looks
wired but silently evaluates fabricated data."* The scope-only checks (Fasi 1–9)
therefore run automatically in the bus, while a caller who has its own way of deriving
subject/resource attributes constructs the context and calls `authorize` explicitly.

## ABAC: the engine and its builder

As established in "The model in one page", ABAC means decisions computed from call
attributes, and policies answer with three values. `CompositeAbacPolicyEngine` is the
reference engine that turns an ordered list of policies into one final decision under a
combining algorithm (Fase 2b §2b.2) — the same *combining algorithms* the XACML standard
defined, restricted to the pair this ADR needs:

- `DENY_OVERRIDES` — a single `DENY` from any policy denies the whole evaluation; the
  result is `PERMIT` only if every policy permits (and policies that abstain don't count
  against that). The default.
- `PERMIT_OVERRIDES` — a single `PERMIT` from any policy permits the whole evaluation;
  the result is `DENY` only if every policy denies.

Intuitively: Deny-Overrides is the paranoid default (any one policy can block a call,
none is required to bless it), Permit-Overrides is the forgiving reverse (any one policy
can let it through). The fold runs in registration order with early return. `NamedPolicy`
pairs each policy with the name it was registered under — audit/debugging only, never
read by `evaluate`.

Build it through `AbacPoliciesBuilder`, the fluent API `AraRuntime.Builder.abacPolicies`
exposes:

```java
AraRuntime.builder()
    .llmClient(client)
    .abacPolicies(policies -> policies
        .add("clearance",      ClearancePolicy.standard())
        .add("business-hours", BusinessHoursPolicy.forZone("Europe/Rome"))
        .add("max-depth",      DelegationDepthPolicy.max(3))
        .combineWith(CompositeAbacPolicyEngine.CombiningAlgorithm.DENY_OVERRIDES))
    .build();
```

The default algorithm is `DENY_OVERRIDES` when `combineWith` is never called — the
safer default: *any one configured policy can block a call, none of them alone is
required to explicitly permit it.*

An `AbacPolicy` receives a `PolicyEvaluationContext` — one record holding the four ABAC
attribute categories as `subject`, `resource`, `action`, `environment`, plus the
`ExecutionContext` when one is available (nullable — a policy that reads it must handle
that case). `ActionAttributes` is a plain wrapped `String` rather than an enum, because
*"a deployment's own policies may need action names this type has no reason to know
about in advance"* — the three constants `INVOKE`/`DELEGATE`/`READ` cover only what ARA's
own chokepoints reason about.

## The reference policies

Each policy abstains (`NOT_APPLICABLE`) whenever the case it guards is absent — that is
what lets them be combined safely under `DENY_OVERRIDES` without every policy having to
pass on every call.

- **`ClearancePolicy`** — denies unless the subject's clearance is at least the
  resource's `requiredClearance`; abstains when the resource declares none (nothing to
  check on public data). It uses `ClearanceLevel`, whose hierarchy is generous: four
  tiers in declaration order so `Enum.compareTo` *is* the comparison — *"no separate
  ordinal table to keep in sync."* Its `parse` is deliberately defensive: `null`, blank,
  or unrecognized all parse to `STANDARD`, the lowest tier, never the highest — a caller
  with malformed data is never accidentally treated as more trusted than it claimed.
- **`BusinessHoursPolicy`** — denies an action on a `dataClassification = "critical"`
  resource outside a window; abstains for any other classification. `forZone(zoneId)`
  gives the common default (09:00–18:00 Monday–Friday), `of(...)` a fully custom window.
  It reads `EnvironmentAttributes.timestamp()` rather than `Instant.now()`, so a decision
  is reproducible and testable without mocking the clock.
- **`DelegationDepthPolicy`** — denies outright when `delegationDepth` exceeds the
  configured maximum; never abstains. This is the only always-applies policy, and that is
  its point: the depth counter is set by the delegation machinery (Fase 5, see "Where
  the checks actually run"), and *"a circuit breaker on how many hops a delegation chain
  may run, independent of whether every individual hop's scope attenuation is itself
  satisfied"* — going too deep is denied even when each hop's scopes looked fine.
- **`TenantIsolationPolicy`** — denies when the subject's `tenantId` differs from the
  tenant prefix of the resource's `agentId` (the same `"<tenant>:"` convention
  `TenantScopeHelper` applies to scopes, applied to agent ids). Abstains when the subject
  carries no `tenantId` (a pure M2M caller) or the resource id carries no recognizable
  prefix (a shared/global agent) — both cases meaning "nothing to isolate here".

## Temporary scope grants (Fase 8)

Fase 8 answers a concrete need: *let a caller do one extra thing, just for a while.*
`TemporaryScopeRegistry` stores active `ScopeGrant`s per agent, and a `ScopeGrant` is a
set of scopes bounded in **time** (`expiresAt`) and/or **use-count** (`remainingUses`,
`-1` = unlimited). `LocalMessageBus` consults the registry on every dispatch: the
caller's effective scopes are `staticScopes.union(effectiveTemporaryScopes(senderId))`.
Key semantics:

- **A temporary grant only ever widens.** `union`, never `intersect`, and a grant that
  expires or exhausts simply stops contributing — *"it never subtracts from what an actor
  already holds."*
- **Reading consumes one use.** `effectiveTemporaryScopes` consumes one use per grant it
  reads (Fase 8 §8.2): an unlimited grant is unaffected, a grant that reaches zero stops
  contributing from the next call onward.
- `AraRuntime.grantTemporaryScope(agentId, scopes, ttl, maxUses, reason)` issues and
  registers such a grant; `revokeAll(agentId)` discards everything held, valid or not.
- `TemporaryScopeRegistry.noop()` exists as the pre-Fase-8 shape — grants nothing,
  remembers nothing. The builder's default, by contrast, is a real
  `InMemoryTemporaryScopeRegistry` (so `grantTemporaryScope` works out of the box);
  `null` in a hand-constructed `LocalMessageBus` (every bus built before Fase 8)
  contributes nothing — identical to the no-op.
- `InMemoryTemporaryScopeRegistry` is the reference implementation. It is method-level
  `synchronized`, not per-agent locked, because *"grant/revoke are rare, human-timescale
  operations, so simplicity and correctness win over throughput here — the same trade-off
  `InMemoryApprovalGate` makes for the same reason."* Grants are lost on process restart —
  acceptable for a task-scoped, short-lived grant; a durable deployment supplies its own
  implementation.

## Tenant scoping (Fase 9)

`TenantScopeHelper` is a *convention* helper, and it is honest about being only that:
*"ARA does not parse or interpret this prefix anywhere in the authorization model."* A
scope like `"acme:finance:read"` is one opaque string to `ScopeSet` — isolation is a
consequence of that opacity, not a mechanism: two tenants' scopes never share a string,
so `grants`/`intersect`/`visibleTo` never cross a tenant boundary by construction. There
is no new enforcement code to write or forget. This helper exists purely so callers build
the prefix consistently instead of string-concatenating at every call site.

```java
// Agent scoped to tenant "acme":
AgentConfig.defaults()
    .grantedScopes(TenantScopeHelper.forTenant("acme", "finance:read", "ops").scopes().stream().toList())
    ...

// A scope valid across every tenant (e.g. a platform-operator role):
TenantScopeHelper.global("admin:audit")
```

## Where the checks actually run

Enforcement is wired at four chokepoints, in this order of frequency:

1. **`LocalMessageBus`** (every dispatch, both `request` and fire-and-forget) — resolves
   the sender's effective scopes (`ExecutionContext.effectiveScopes` if present, else
   `senderScopes`, unioned with any temporary grants), then runs `checkAuthorized` then
   `checkApproved`. Fase 5 relabels the incoming `ExecutionContext` for the recipient,
   carrying `effectiveScopes` through *unchanged* — deliberately not a second `intersect`
   against the recipient's own `grantedScopes`: the attenuation happens *once*, at the
   delegation boundary, and a wrong second intersect here was a real bug (a leaf agent
   that legitimately declares no scopes of its own got its caller's grant intersected
   down to empty *before* the authorization check ran, denying itself its own
   requirements). The recipient's ceiling still applies the moment *it* delegates, via
   `AgentDelegationTool` below.
2. **`AgentDelegationTool.delegate()`** — the delegation boundary (Fase 5) where the
   caller's scopes are actually attenuated: the delegation runs with the delegating
   agent's own `grantedScopes` as the ceiling, exactly once, at the hop. This is also
   where `delegationDepth` is counted.
3. **`ScopeFilteringToolRegistry`** — tool resolution by id re-checks
   `ScopeVerifier.checkTool`, a defense-in-depth layer under the already-visible tool
   outbox.
4. **`AuthorizationService`** — the explicit ABAC+scope facade for callers outside the
   bus (e.g. a gateway) who can build a `PolicyEvaluationContext` from *their* domain.
   Everything in this package that is not automatically wired lands here.

## Conventions / gotchas

- **Static, stateless checks.** `ScopeVerifier` and `TenantScopeHelper` are utility
  classes of static methods with private constructors — Fase 2 has nothing for an
  instance to hold, so none is fabricated (the plan's own sketch had a held field that
  *couldn't* exist; `AuthorizationService`'s Javadoc calls that deviation out).
- **Opt-in everywhere, on both sides.** An empty declared set is "open to everyone"; a
  check against unconfigured agents is a no-op. Enforcement activates only when a caller
  sets `visibleToScopes`/`requiredScopes`, or configures a gate/policies.
- **The gateway is out of scope.** Nothing here turns an incoming OAuth token into a
  `ScopeSet` — that is `ara-gateway`'s job, and `ara-gateway` is not part of this repo.
- **`ScopeGrant` composes by union or not at all.** `expiresAt`-null + `remainingUses`
  `-1` is the "unlimited" grant; `remainingUses` is validated to `-1` or `>= 0`. A grant
  never takes authority away.
- **`checkApproved` only throttles invocations, never payloads.** `Approved` or
  `Modified` lets the invocation proceed with its original arguments — rewriting is
  `ApprovalToolRegistry`'s job on the tool-call path, a different gate entirely.
- **`EnvironmentAttributes.timestamp()`, never `Instant.now()`.** Policies stay pure
  functions of their context, so decisions are reproducible in tests without a clock.
- **Defensive `parse`, always to the lower tier.** `ClearanceLevel.parse` maps anything
  malformed to `STANDARD` — the fail-locked, never fail-open, direction.
- **ABAC is a second, separate layer, not a scope replacement.** `authorize` *runs the
  scope check first*, always; ABAC evaluates only after scopes pass, and only when an
  engine is configured. Scopes and ABAC are checked in this order everywhere, and
  `SubjectAttributes` carries `grantedScopes` so a policy can combine both signals
  without a caller evaluating them separately.