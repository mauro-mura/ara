package io.ara.core.agent;

import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentResponseArtifactsTest {

    private static final AgentId AGENT = AgentId.of("a");

    private static MediaRef artifact(String name) {
        return MediaStore.inMemory().put(name, "text/plain", name.getBytes());
    }

    private static AgentResponse thirteenArg() {
        return new AgentResponse("t", AGENT, "ok", AgentState.DONE, 1, 0, 0, Money.ZERO_EUR,
                Duration.ZERO, null, Instant.now(), List.of(), null);
    }

    private static AgentResponse fourteenArg() {
        return new AgentResponse("t", AGENT, "ok", AgentState.DONE, 1, 0, 0, Money.ZERO_EUR,
                Duration.ZERO, null, Instant.now(), List.of(), null, null);
    }

    @Test
    void theOlderConstructors_haveNoArtifacts() {
        assertEquals(List.of(), thirteenArg().artifacts());
        assertEquals(List.of(), fourteenArg().artifacts());
    }

    @Test
    void factories_haveNoArtifacts() {
        assertEquals(List.of(), AgentResponse.failure("t", AGENT, "x", Duration.ZERO).artifacts());
        assertEquals(List.of(), AgentResponse.success("t", AGENT, "ok", 1, 1, 1, Money.ZERO_EUR, Duration.ZERO,
                List.of()).artifacts());
    }

    @Test
    void aNullListIsNoArtifacts() {
        AgentResponse response = new AgentResponse("t", AGENT, "ok", AgentState.DONE, 1, 0, 0, Money.ZERO_EUR,
                Duration.ZERO, null, Instant.now(), List.of(), null, null, null);
        assertEquals(List.of(), response.artifacts());
    }

    @Test
    void withArtifacts_setsThem_andKeepsEverythingElse() {
        MediaRef block = artifact("block-1.py");

        AgentResponse response = fourteenArg().withArtifacts(List.of(block));

        assertEquals(List.of(block), response.artifacts());
        assertEquals("ok", response.content());
        assertEquals(AgentState.DONE, response.finalState());
    }

    @Test
    void theOtherCopiers_keepTheArtifacts() {
        MediaRef block = artifact("block-1.py");
        AgentResponse with = fourteenArg().withArtifacts(List.of(block));

        assertEquals(List.of(block), with.withContent("other").artifacts());
        assertEquals(List.of(block), with.withCost(Money.ZERO_EUR).artifacts());
        assertEquals(List.of(block), with.withLlmProvider("p").artifacts());
        assertEquals(List.of(block), with.withViolation(null).artifacts());
    }

    @Test
    void theListIsACopy() {
        MediaRef block = artifact("block-1.py");
        List<MediaRef> source = new java.util.ArrayList<>(List.of(block));

        AgentResponse response = fourteenArg().withArtifacts(source);
        source.clear();

        assertEquals(List.of(block), response.artifacts());
        assertThrows(UnsupportedOperationException.class, () -> response.artifacts().clear());
    }

    @Test
    void withArtifacts_refusesNull() {
        assertThrows(NullPointerException.class, () -> fourteenArg().withArtifacts(null));
    }
}
