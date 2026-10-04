package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.budget.SpendMeter;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeteringStrategy}: every LLM call a strategy makes lands on the {@link SpendMeter} the
 * task carries — whichever strategy it is — and nothing else about the run changes.
 */
class MeteringStrategyTest {

    private static AgentConfig config() {
        return AgentConfig.defaults()
                .agentId(AgentId.of("metered-agent")).agentType("t")
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(Money.of("1.0", "EUR"))
                        .costOutputPer1kTokens(Money.of("3.0", "EUR"))
                        .build())
                .plannerStrategy("react")
                .maxIterations(5)
                .build();
    }

    /** A client that answers every call with the same usage: 10 prompt / 15 output tokens. */
    private static final class FixedUsageClient implements LlmClient {
        int streams;

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return new LlmCompletion("ok", 10, 15, "stop", null);
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            streams++;
            return subscriber -> { };
        }

        @Override
        public String providerId() { return "fixed"; }
    }

    /** A strategy that is not ReAct and charges nothing itself: it just calls the model {@code n} times. */
    private static final class CallsNTimes implements ExecutionStrategy {
        final int n;
        final AtomicReference<LlmClient> seen = new AtomicReference<>();

        CallsNTimes(int n) { this.n = n; }

        @Override
        public ExecutionResult execute(AgentTask task, LlmClient llm, MemoryManager memory,
                                       ToolRegistry tools, AgentConfig config) {
            seen.set(llm);
            for (int i = 0; i < n; i++) {
                llm.complete(List.of(LlmMessage.user("q")), LlmCallContext.from(config));
            }
            return ExecutionResult.success("done", n, 10 * n, 15 * n, List.of());
        }

        @Override
        public String strategyName() { return "calls-n-times"; }
    }

    private static AgentTask taskWith(SpendMeter meter) {
        return AgentTask.of("hi").withRunContext(meter.attachTo(io.ara.core.agent.RunContext.empty()));
    }

    @Test
    void anyStrategyIsMeasured_notJustTheOnesThatChargeARunBudget() {
        SpendMeter meter = SpendMeter.of("EUR");
        ExecutionStrategy metered = MeteringStrategy.around(new CallsNTimes(3));

        metered.execute(taskWith(meter), new FixedUsageClient(), null, null, config());

        SpendMeter.Reading reading = meter.reading();
        assertEquals(3, reading.spend().calls());
        assertEquals(30, reading.promptTokens());
        assertEquals(45, reading.outputTokens());
        // (30/1000)*1.0 + (45/1000)*3.0 = 0.03 + 0.135 = 0.165
        assertEquals(0, reading.spend().money().compareTo(Money.of("0.165", "EUR")));
    }

    @Test
    void aRealAgentRun_isMeasuredToTheSameFiguresItsResponseReports() {
        SpendMeter meter = SpendMeter.of("EUR");
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("noop", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .toolRegistry(ToolRegistry.empty())
                .build();
        AraAgent agent = runtime.createAgent(config());

        AgentResponse response = agent.execute(taskWith(meter));

        assertTrue(response.isSuccess(), response.failureReason());
        SpendMeter.Reading reading = meter.reading();
        assertEquals(response.inputTokens(), reading.promptTokens());
        assertEquals(response.outputTokens(), reading.outputTokens());
        assertEquals(0, response.estimatedCost().compareTo(reading.spend().money()),
                "the meter prices the calls exactly as the response prices the run");
        assertEquals(2, reading.spend().calls());
    }

    @Test
    void aRunThatFailsAfterSomeCalls_keepsWhatItSpent() {
        SpendMeter meter = SpendMeter.of("EUR");
        ExecutionStrategy failing = MeteringStrategy.around(new ExecutionStrategy() {
            @Override
            public ExecutionResult execute(AgentTask task, LlmClient llm, MemoryManager memory,
                                           ToolRegistry tools, AgentConfig config) {
                llm.complete(List.of(LlmMessage.user("q")), LlmCallContext.from(config));
                llm.complete(List.of(LlmMessage.user("q")), LlmCallContext.from(config));
                throw new IllegalStateException("wedged on the third call");
            }

            @Override
            public String strategyName() { return "failing"; }
        });

        assertThrows(IllegalStateException.class,
                () -> failing.execute(taskWith(meter), new FixedUsageClient(), null, null, config()));

        assertEquals(2, meter.reading().spend().calls(),
                "the calls that returned are on the meter even though the run never reported them");
    }

    @Test
    void aStreamedCallIsCountedAsUnmetered() {
        SpendMeter meter = SpendMeter.of("EUR");
        FixedUsageClient client = new FixedUsageClient();
        ExecutionStrategy streaming = MeteringStrategy.around(new ExecutionStrategy() {
            @Override
            public ExecutionResult execute(AgentTask task, LlmClient llm, MemoryManager memory,
                                           ToolRegistry tools, AgentConfig config) {
                llm.stream(List.of(LlmMessage.user("q")), LlmCallContext.from(config));
                return ExecutionResult.success("done", 1, 0, 0, List.of());
            }

            @Override
            public String strategyName() { return "streaming"; }
        });

        streaming.execute(taskWith(meter), client, null, null, config());

        assertEquals(1, client.streams, "the call still goes through");
        assertEquals(1, meter.reading().unmeteredStreams());
        assertEquals(0, meter.reading().spend().calls());
    }

    @Test
    void aTaskWithoutAMeter_getsTheClientUntouched() {
        CallsNTimes inner = new CallsNTimes(1);
        FixedUsageClient client = new FixedUsageClient();

        MeteringStrategy.around(inner).execute(AgentTask.of("hi"), client, null, null, config());

        assertSame(client, inner.seen.get(), "no meter, no decorator, no overhead beyond the lookup");
    }

    @Test
    void aTaskWithAMeter_getsADecoratedClient() {
        CallsNTimes inner = new CallsNTimes(1);
        FixedUsageClient client = new FixedUsageClient();

        MeteringStrategy.around(inner).execute(taskWith(SpendMeter.of("EUR")), client, null, null, config());

        assertNotSame(client, inner.seen.get());
    }

    @Test
    void metering_isInvisibleToAnythingThatReadsTheStrategyName() {
        ExecutionStrategy inner = new CallsNTimes(1);

        assertEquals("calls-n-times", MeteringStrategy.around(inner).strategyName(),
                "a different name would change the behavioural hash of every metered spec");
    }

    @Test
    void wrappingTwiceIsWrappingOnce() {
        ExecutionStrategy once = MeteringStrategy.around(new CallsNTimes(1));

        assertSame(once, MeteringStrategy.around(once));
    }
}
