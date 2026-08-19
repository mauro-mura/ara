package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.contract.MediaLimits;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The media-validation phase must reject before the model is called at all: the point of a
 * quantitative cap is to stop a task that is too large from being paid for, so a counting
 * client that is never invoked is the assertion that matters.
 */
class ContractMediaValidationTest {

    private static final class CountingClient implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            calls.incrementAndGet();
            return new LlmCompletion("FINAL_ANSWER: done", 1, 1, "stop", null);
        }

        @Override public String providerId() { return "counting"; }
        @Override public Set<String> supportedMediaTypes() {
            return MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT);
        }
    }

    private static AgentConfig configFor(AgentId id) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of(id.value()))
                .plannerStrategy("react")
                .maxIterations(3)
                .build();
    }

    private static MediaRef pdf(String name, long bytes) {
        return new MediaRef("digest-" + name, "application/pdf", name, bytes, null);
    }

    @Test
    void an_over_limit_task_fails_without_reaching_the_model() {
        AgentId id = AgentId.of("capped-agent");
        CountingClient client = new CountingClient();
        AraRuntime runtime = AraRuntime.builder().llmClient(id.value(), client).build();
        try {
            AgentContract contract = AgentContract.builder()
                    .addMediaValidator(MediaLimits.of(1, 1_000))
                    .build();
            AraAgent agent = runtime.createAgent(configFor(id), contract);

            AgentResponse r = agent.execute(
                    AgentTask.of("read both", List.of(pdf("a.pdf", 900), pdf("b.pdf", 900))));

            assertFalse(r.isSuccess());
            assertTrue(r.failureReason().contains("media"), r.failureReason());
            assertEquals(0, client.calls.get(), "a capped task must not spend a token");
        } finally {
            runtime.stop();
        }
    }

    @Test
    void a_within_limit_task_runs_normally() {
        AgentId id = AgentId.of("ok-agent");
        CountingClient client = new CountingClient();
        AraRuntime runtime = AraRuntime.builder().llmClient(id.value(), client).build();
        try {
            AgentContract contract = AgentContract.builder()
                    .addMediaValidator(MediaLimits.of(2, 10_000))
                    .build();
            AraAgent agent = runtime.createAgent(configFor(id), contract);

            AgentResponse r = agent.execute(AgentTask.of("read it", List.of(pdf("a.pdf", 900))));

            assertTrue(r.isSuccess(), () -> "execute failed: " + r.failureReason());
            assertEquals(1, client.calls.get());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void a_contract_with_only_a_media_validator_is_not_empty() {
        AgentContract contract = AgentContract.builder()
                .addMediaValidator(MediaLimits.none())
                .build();
        assertFalse(contract.isEmpty(),
                "otherwise AgentFactory would skip the enforcing decorator and never apply it");
    }
}
