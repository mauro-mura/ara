# AGENTS.md

ARA — Agent Runtime Architecture: a Java 21 framework for autonomous agents and multi-agent systems. Four Maven modules built from the repo root: `ara-core` (interfaces) → `ara-runtime` (implementation) → `ara-adapters` (LangChain4j providers, MCP, OTel) → `ara-examples` (demos). Packages under `io.ara.*`.

## Build, test, lint

- No maven wrapper, no makefile. Requires Java 21+ and Maven 3.9+. Use plain `mvn`.
- `mvn` is NOT on PATH on this machine. Fall back to the Maven bundled with IntelliJ (3.9.16): `MVN="/Users/mlago/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/maven-plugin/lib/maven3/bin/mvn"; "$MVN" <goal>`. If a build is run through something else that adds Maven to PATH, plain `mvn` and `$MVN` are interchangeable.
- CI gate, run before pushing: `mvn -B verify`. It includes:
  - Maven Enforcer (`dependencyConvergence`, `requireUpperBoundDeps`, `banDuplicatePomDependencyVersions`) — the only static analysis. Jackson is split on purpose: `jackson-annotations` is `${jackson-annotations.version}` (2.22), the rest `${jackson.version}` (2.22.1); don't "fix" that mismatch.
  - JaCoCo coverage floor, checked in the `verify` phase per module (BUNDLE scope, instruction/branch): ara-core `0.50/0.31`, ara-runtime `0.66/0.57`, ara-adapters `0.61/0.45`. New production code normally needs accompanying tests or `verify` fails. Floors are a ratchet — never lower one to make the build pass.
  - `ara-examples` has no tests by design, skips JaCoCo (`jacoco.skip`), and is not published.
- Focused runs: `mvn -pl ara-runtime -am test` or `mvn -pl ara-core -am -Dtest=<ClassName> test`.
- All unit tests run offline against `ScriptedLlmClient` / `AssociativeLlmClient`; Mockito is managed but unused. Do not write tests that hit real providers. Live demos are `main()`s in `ara-examples` (takes a `live` arg or `-Dara.example.live=true`) and need their own API keys.

## Architecture (not obvious from filenames)

- `ara-core` is interface-first (e.g. `AraAgent`, `LlmClient`, `ToolRegistry`, `AgentContract`) and depends only on jackson-databind. The whole build is deliberately free of reflection, annotation magic, Kotlin and Spring — keep new code that way. New `ara-core` types should be plain interfaces/records with no behavior.
- `AraRuntime` (`ara-runtime/AraRuntime.java`) is the runtime entry point: it wires strategies, tool registry, memory, scheduler, telemetry, HITL gate. Everything is registered by name via `AraRuntime.Builder`.
- `ara-gateway` (the Javalin HTTP layer) is NOT part of this repo or build — nothing here may depend on it.
- Execution strategies are plugins (`Builder.extraStrategies(...)`). A `plannerStrategy` name typo now fails *loud* at first task execution with `IllegalStateException` listing the registered names (no more silent `"react"` fallback). `StrategyConfig` is sealed in `ara-core` (no new variants outside it); `ReactExecutionSupport` is package-private.
- The commented-out "Agent graph" section in `README.md` describes a module that does not exist yet — don't build against it.

## Conventions

- `docs/CODING-GUIDELINES.md` is the enforced style guide — read it before editing and follow it in every development (both new code and refactors). It mandates simplicity-first design, no premature abstractions, short single-responsibility functions, and a comment taxonomy. This repo deliberately keeps verbose "why"/design comments (including discarded alternatives) and expects them preserved and matched — this overrides the default "avoid comments" instinct.
- Comments must stand alone: never point to an external doc/ADR in place of writing the rationale.
- Version bumps are separate `Version X.Y.Z` commits; current version is `1.0.3` (root `pom.xml`).

## Docs

- `README.md` is the feature reference (contracts, strategies, HITL, RAG, scheduler, examples table).
- `docs/ADVANCED.md`: custom `ExecutionStrategy` registration + LLM-tracing gotchas.
- Package READMEs: `ara-runtime/src/main/java/io/ara/runtime/{strategy,pipeline,contract,llm}/README.md`. Main README links the strategy/pipeline ones; check them when touching those areas.

## Release (human-gated — don't initiate unless asked)

- Annotated tag `v<X.Y.Z>`; tag version must match the pom version and be non-`-SNAPSHOT` (workflow fails otherwise). `mvn -B verify`, then `mvn -B -Prelease -DskipTests deploy`. `autoPublish=false` parks the upload as a draft on the Central portal where a human presses Publish. Publishing server id is `central`.