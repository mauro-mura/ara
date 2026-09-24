package io.ara.examples.basics;

import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;

/**
 * The smallest useful ARA program: build a runtime, ask one question.
 *
 * <p>Two lines of logic. {@link AraRuntime#askText(String)} runs the prompt on a shared
 * default agent — no agent to create, no task to build — and the runtime auto-starts on
 * first use, with {@code close()} shutting it down. There is no explicit {@code start()},
 * no {@code AgentConfig} and no {@code AgentResponse}: everything the first ten minutes
 * with ARA needs, and nothing else.
 *
 * <p>Offline by design: {@link ScriptedLlmClient} replays one canned answer, so it runs with
 * no key and no network. To point it at a real model, replace the {@code llmClient(...)} line
 * with an adapter from the README quick start — nothing else changes.
 *
 * <p>The moment the agent needs a role, a system prompt, tools or a specific model, create
 * one explicitly: {@code runtime.createAgent(AgentConfig.of("assistant", "..."))}, then
 * {@code AraAgents.askText(agent, prompt)}. See {@link AraSimpleExample} for the fuller shape.
 *
 * @see AraSimpleExample — the same runtime with a tool, an interceptor and the full response
 */
public class MinimalAgentExample {

    public static void main(String[] args) {
        try (var runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenFinalAnswer("Virtual threads are lightweight JVM threads.")
                        .build())
                .build()) {

            System.out.println(runtime.askText("Explain virtual threads"));
        }
    }
}
