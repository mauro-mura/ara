package io.ara.runtime.llm;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.media.MediaResolver;
import io.ara.core.media.MediaStore;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Attaches the runtime's {@link MediaStore} to every outgoing {@link LlmCallContext}, so the
 * adapter downstream can turn the message's media references back into bytes.
 *
 * <h2>Why a decorator, and not the two shorter routes</h2>
 * <p>The store is a runtime-wide collaborator — <em>where</em> bytes live is an
 * infrastructure decision, like {@code SessionStore} — while the call context is built inside
 * each {@code ExecutionStrategy}, which never sees runtime wiring. Two more direct routes
 * were considered and rejected:
 * <ul>
 *   <li><b>A parameter on {@code ExecutionStrategy.execute(...)}.</b> That interface is public
 *       and implemented by eight built-in strategies plus anything a user has written; adding
 *       an argument breaks all of them to serve one concern that most strategies never touch.</li>
 *   <li><b>A field on {@code AgentConfig}.</b> It would make the store per-agent, so two
 *       agents in one delegation chain could disagree about where the same document lives —
 *       and a {@code MediaRef} passed from one to the other would resolve for one and not the
 *       other.</li>
 * </ul>
 * <p>A decorator installed where the runtime already wraps every registered client keeps both
 * of those intact: strategies stay unchanged, the store stays runtime-wide, and the context
 * the adapter reads carries the resolver. It extends {@link DelegatingLlmClient}, so it
 * cannot mask the capabilities of what it wraps.
 *
 * <p>An explicit per-call resolver already on the context wins: this only fills in the
 * default, so a caller that built a context with its own resolver keeps it.
 */
public final class MediaResolvingLlmClient extends DelegatingLlmClient {

    private final MediaResolver resolver;

    public MediaResolvingLlmClient(LlmClient delegate, MediaStore mediaStore) {
        super(delegate);
        this.resolver = MediaResolver.backedBy(
                Objects.requireNonNull(mediaStore, "mediaStore must not be null"));
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        return delegate.complete(messages, withResolver(context));
    }

    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        return delegate.stream(messages, withResolver(context));
    }

    /**
     * Returns {@code context} carrying this runtime's resolver, unless it already has one of
     * its own. {@code null} is passed through untouched — some deprecated call paths still
     * hand no context at all, and those carry no media either.
     */
    private LlmCallContext withResolver(LlmCallContext context) {
        if (context == null || context.hasMediaResolver()) {
            return context;
        }
        return context.withMediaResolver(resolver);
    }
}
