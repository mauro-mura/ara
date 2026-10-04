package io.ara.runtime.strategy;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.memory.ToolCallMetadata;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.telemetry.TelemetryToolRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Support for tool dispatch and streaming with deadline bounds.
 * Extracted from ReactExecutionSupport to improve cohesion.
 */
final class ToolDispatchSupport {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolDispatchSupport.class);

    private ToolDispatchSupport() {
    }
    static boolean dispatchSingle(ToolCallParser.ToolCallRequest tcr, String legacyToolCallId, ReActSupport.DispatchContext ctx) {
        log.debug("Dispatching tool [{}] for task [{}]", tcr.toolId(), ctx.task().taskId());
        String callId = tcr.toolCallId() != null ? tcr.toolCallId() : legacyToolCallId;
        ToolCallParser.ToolCallRequest resolved = callId != null && !callId.equals(tcr.toolCallId())
                ? new ToolCallParser.ToolCallRequest(tcr.toolId(), tcr.argumentJson(), callId)
                : tcr;
        return dispatchBounded(List.of(resolved), ctx);
    }

    static boolean dispatchParallel(List<ToolCallParser.ToolCallRequest> calls, ReActSupport.DispatchContext ctx) {
        return dispatchBounded(calls, ctx);
    }

    static boolean dispatchBounded(List<ToolCallParser.ToolCallRequest> calls, ReActSupport.DispatchContext ctx) {
        int n = calls.size();
        Map<Integer, ToolResult> results = new ConcurrentHashMap<>(n);
        CountDownLatch latch = new CountDownLatch(n);
        List<Thread> workers = new ArrayList<>(n);
        ToolRegistry tools = ctx.tools();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final ToolCallParser.ToolCallRequest tcr = calls.get(idx);
            if (ctx.logIo()) {
                log.info("TOOL CALL [{}] args={}", tcr.toolId(), truncate(tcr.argumentJson(), ctx.logIoMaxChars()));
            }
            ctx.task().notifyToolCall(tcr.toolId(), tcr.argumentJson());
            ctx.steps().add(ExecutionStep.toolCall(tcr.toolId(), tcr.argumentJson(), ctx.iteration()));
            AgentTask dispatchTask = (tcr.toolCallId() != null && !tcr.toolCallId().isBlank())
                    ? ctx.task().withAttachment(TelemetryToolRegistry.TOOL_CALL_ID_ATTACHMENT_KEY, tcr.toolCallId())
                    : ctx.task();
            workers.add(Thread.ofVirtual().start(tools.wrapForPropagation(() -> {
                try {
                    results.put(idx, tools.execute(tcr.toolId(), tcr.argumentJson(), dispatchTask));
                } catch (Exception e) {
                    results.put(idx, ToolResult.failure(tcr.toolId(),
                            "Unexpected error: " + e.getMessage()));
                } finally {
                    latch.countDown();
                }
            })));
        }

        try {
            long remainingMs = Math.max(0, Duration.between(Instant.now(), ctx.deadline()).toMillis());
            if (!latch.await(remainingMs, TimeUnit.MILLISECONDS)) {
                log.warn("Tool dispatch did not finish before deadline for task [{}] ({} call(s))",
                        ctx.task().taskId(), n);
                workers.forEach(Thread::interrupt);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.forEach(Thread::interrupt);
            log.warn("Tool dispatch interrupted for task [{}]", ctx.task().taskId());
        }

        boolean anyFailed = false;
        for (int i = 0; i < n; i++) {
            ToolCallParser.ToolCallRequest tcr = calls.get(i);
            ToolResult result = results.getOrDefault(i,
                    ToolResult.failure(tcr.toolId(), "result missing — thread may have been lost"));
            anyFailed |= recordObservation(tcr, tcr.toolCallId(), result, ctx);
        }
        return anyFailed;
    }

    static <T> T runBounded(ToolRegistry tools, Supplier<T> work, Instant deadline, Duration executionTimeout)
            throws InterruptedException {

        long remainingMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        if (remainingMs <= 0) {
            throw new ExecutionTimeoutException(executionTimeout);
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = Thread.ofVirtual().start(tools.wrapForPropagation(() -> {
            try {
                result.set(work.get());
            } catch (RuntimeException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }));

        try {
            if (!done.await(remainingMs, TimeUnit.MILLISECONDS)) {
                worker.interrupt();
                throw new ExecutionTimeoutException(executionTimeout);
            }
        } catch (InterruptedException e) {
            worker.interrupt();
            throw e;
        }

        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    static boolean recordObservation(
            ToolCallParser.ToolCallRequest tcr, String callId, ToolResult result, ReActSupport.DispatchContext ctx) {

        String observation = result.success()
                ? result.output()
                : "Tool [%s] failed — %s".formatted(tcr.toolId(), result.error());

        if (log.isDebugEnabled()) {
            log.debug("Tool [{}] result — success={} output={}",
                    tcr.toolId(), result.success(),
                    observation.length() > 300 ? observation.substring(0, 300) + "…" : observation);
        }
        if (ctx.logIo()) {
            log.info("TOOL RESULT [{}] success={} output={}",
                    tcr.toolId(), result.success(), truncate(observation, ctx.logIoMaxChars()));
        }

        if (callId != null && !callId.isBlank()) {
            ctx.memory().appendToWorkingMemory("tool", observation, new ToolCallMetadata(callId, tcr.toolId()));
        } else {
            ctx.memory().appendToWorkingMemory("user", "Observation: " + observation);
        }
        ctx.steps().add(ExecutionStep.observation(observation, ctx.iteration()));
        return !result.success();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (max <= 0) return s;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
