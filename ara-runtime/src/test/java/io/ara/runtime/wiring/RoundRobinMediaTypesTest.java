package io.ara.runtime.wiring;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lives in the {@code wiring} package because {@link RoundRobinLlmClient} is package-private.
 *
 * <p>The intersection matters more here than for failover: the rotation picks a client per
 * call, so reporting the union would make the same PDF succeed or fail depending on where the
 * counter stands — one failure in N, the hardest kind to reproduce.
 */
class RoundRobinMediaTypesTest {

    private record Capable(String providerId, Set<String> supportedMediaTypes) implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }
    }

    @Test
    void reports_only_what_every_client_in_the_rotation_supports() {
        LlmClient pool = new RoundRobinLlmClient(List.of(
                new Capable("a", Set.of("image/png", "application/pdf")),
                new Capable("b", Set.of("image/png"))));

        assertEquals(Set.of("image/png"), pool.supportedMediaTypes());
    }

    @Test
    void one_text_only_client_makes_the_whole_rotation_text_only() {
        LlmClient pool = new RoundRobinLlmClient(List.of(
                new Capable("a", Set.of("image/png")),
                new Capable("b", Set.of())));

        assertEquals(Set.of(), pool.supportedMediaTypes());
    }
}
