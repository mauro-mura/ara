package io.ara.examples.support;

import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.agent.AgentInterceptor;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.tool.ToolResult;

import java.time.Duration;

/**
 * Interceptor that prints step boundaries to stdout. Shared by
 * {@code AraSimpleExample} and its live variant so the live sibling does not import
 * the stub example's nested class.
 */
public final class LoggingInterceptor implements AgentInterceptor {

    @Override
    public void before(AgentExecutionContext ctx, String stepName) {
        System.out.printf("  [Interceptor] → before(%s) state=%s%n",
                stepName, ctx.currentState());
    }

    @Override
    public void after(AgentExecutionContext ctx, String stepName, String result) {
        System.out.printf("  [Interceptor] ← after(%s) result=%.60s%n",
                stepName, result);
    }

    @Override
    public void onError(AgentExecutionContext ctx, String stepName, Throwable t) {
        System.out.printf("  [Interceptor] ✗ onError(%s) %s%n",
                stepName, t.getMessage());
    }

    @Override
    public void beforeThink(AgentExecutionContext ctx) {
        System.out.println("  [Interceptor]     → beforeThink");
    }

    @Override
    public void afterThink(AgentExecutionContext ctx, LlmCompletion completion) {
        System.out.printf("  [Interceptor]     ← afterThink finishReason=%s tokens=%d%n",
                completion.finishReason(), completion.totalTokens());
    }

    @Override
    public void beforeToolCall(AgentExecutionContext ctx, String toolId, String argumentJson) {
        System.out.printf("  [Interceptor]     → beforeToolCall(%s) args=%.60s%n", toolId, argumentJson);
    }

    @Override
    public void afterToolCall(AgentExecutionContext ctx, String toolId, String argumentJson, ToolResult result) {
        System.out.printf("  [Interceptor]     ← afterToolCall(%s) success=%s%n", toolId, result.success());
    }

    @Override
    public void onBudgetExceeded(AgentExecutionContext ctx, String stepName, String reason) {
        System.out.printf("  [Interceptor] ✗ onBudgetExceeded(%s) %s%n", stepName, reason);
    }

    @Override
    public void onTimeout(AgentExecutionContext ctx, String stepName, Duration timeout) {
        System.out.printf("  [Interceptor] ✗ onTimeout(%s) after %ds%n", stepName, timeout.toSeconds());
    }

    @Override
    public void onCancelled(AgentExecutionContext ctx, String stepName) {
        System.out.printf("  [Interceptor] ✗ onCancelled(%s)%n", stepName);
    }
}
