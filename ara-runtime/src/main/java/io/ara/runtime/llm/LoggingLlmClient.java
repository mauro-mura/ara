package io.ara.runtime.llm;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@link LlmClient} decorator that logs request messages and the response at INFO level.
 * Applied automatically by {@link io.ara.runtime.factory.DefaultLlmRouter} when
 * {@code LlmConfig.logIo()} is {@code true}.
 *
 * <p>Attachments are logged as name, type and size. Their bytes never reach this class — a
 * {@code MediaRef} carries none — which is why the log stays readable with a 2 MB PDF
 * attached, with no flag to turn media logging off.
 */
public final class LoggingLlmClient extends DelegatingLlmClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingLlmClient.class);

    private final int maxChars;

    public LoggingLlmClient(LlmClient delegate, int maxChars) {
        super(delegate);
        this.maxChars = maxChars;
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        logRequest(messages, context != null ? context.logLlmIoMaxChars() : maxChars);
        LlmCompletion completion = delegate.complete(messages, context);
        logResponse(completion, context != null ? context.logLlmIoMaxChars() : maxChars);
        return completion;
    }

    @Override
    @SuppressWarnings("deprecation")
    public LlmCompletion complete(List<LlmMessage> messages, AgentConfig config) {
        logRequest(messages, maxChars);
        LlmCompletion completion = delegate.complete(messages, config);
        logResponse(completion, maxChars);
        return completion;
    }

    private void logRequest(List<LlmMessage> messages, int chars) {
        if (!log.isInfoEnabled()) return;
        StringBuilder sb = new StringBuilder("LLM REQUEST [").append(messages.size()).append(" messages]");
        for (LlmMessage m : messages) {
            sb.append("\n  [").append(m.role()).append("] ").append(truncate(m.content(), chars));
            for (var ref : m.media()) {
                sb.append("\n    + media ").append(ref.name())
                  .append(" (").append(ref.mimeType()).append(", ")
                  .append(ref.sizeBytes()).append(" bytes)");
            }
        }
        log.info("{}", sb);
    }

    private void logResponse(LlmCompletion c, int chars) {
        if (!log.isInfoEnabled()) return;
        log.info("LLM RESPONSE text={} finishReason={} promptTokens={} outputTokens={}",
                truncate(c.text(), chars), c.finishReason(), c.promptTokens(), c.outputTokens());
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "<null>";
        if (maxChars <= 0 || s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "…[+" + (s.length() - maxChars) + "]";
    }
}
