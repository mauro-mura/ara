package io.ara.adapters.llm;

import dev.langchain4j.model.chat.request.ChatRequest;
import io.ara.core.llm.LlmCallContext;

/**
 * Applies the per-call sampling parameters carried by an {@link LlmCallContext} to a
 * langchain4j {@link ChatRequest.Builder}.
 *
 * <p>Shared by every adapter rather than reimplemented per provider — the same reasoning as
 * {@link ToolConversionUtils}: request-level parameters are a langchain4j concept, identical
 * whichever chat model consumes them, and the three adapters had already drifted apart once
 * (only the OpenAI one applied them at all, so a {@code temperatureOverride} was silently
 * dropped on Claude and on Ollama).
 *
 * <p>Request-level values override the client-level defaults baked into the chat model at
 * construction time. That direction is what langchain4j actually does for all three providers
 * — verified on the wire, not assumed: with {@code temperature(0.7)} on the model builder and
 * {@code temperature(0.1)} on the request, {@code 0.1} is what reaches the provider.
 *
 * @see ToolConversionUtils
 */
public final class CallParameterUtils {

    private CallParameterUtils() {}

    /**
     * Copies {@code context}'s sampling parameters onto {@code reqBuilder}, leaving the ones
     * the caller never asked to override untouched.
     *
     * <p>{@link LlmCallContext#temperature()} and {@link LlmCallContext#topP()} are {@code
     * null} unless a per-call override or an {@code AgentConfig} value was explicitly set (see
     * the class javadoc on {@code LlmCallContext}) — when {@code null} the corresponding
     * request field is deliberately left unset, so whatever default was configured directly on
     * the client survives. Substituting some other "default" here would defeat the explicit
     * configuration of whoever built the client.
     *
     * <p>{@link LlmCallContext#maxOutputTokens()} is different: it is a non-nullable {@code
     * int} because {@code ReactStrategy}'s cost-budget projection needs a concrete value
     * regardless of what gets sent to the provider, so it is always applied — see
     * {@code AgentConfig#maxTokensPerStep()}, which is where it comes from.
     *
     * @param reqBuilder the request under construction
     * @param context    the call context, or {@code null} to leave {@code reqBuilder} alone
     */
    public static void applyTo(ChatRequest.Builder reqBuilder, LlmCallContext context) {
        if (context == null) return;
        if (context.temperature() != null) reqBuilder.temperature(context.temperature());
        if (context.topP() != null)        reqBuilder.topP(context.topP());
        reqBuilder.maxOutputTokens(context.maxOutputTokens());
    }
}
