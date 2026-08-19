package io.ara.runtime.llm;

import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.runtime.interceptor.AgentInterceptorChain;
import io.ara.runtime.interceptor.InterceptingLlmClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every single-wrap decorator reports the capabilities of what it wraps,
 * rather than the leaf defaults of {@link LlmClient}.
 *
 * <p>Written parametrically, over the list of decorators rather than one test each, so that
 * a decorator added later has to be registered here to be covered — and so a capability
 * method added later is checked across all of them at once. That is the failure this guards:
 * a decorator that silently answers "no native tools" over an OpenAI client makes the
 * strategy above it inject a redundant text tool catalog, with no exception and no log line.
 */
class DelegatingLlmClientTest {

    /** A leaf client that claims a capability, so a decorator masking it is visible. */
    private static final class CapableClient implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }

        @Override public String providerId()            { return "capable-provider"; }
        @Override public String lastUsedProviderId()    { return "capable-last-used"; }
        @Override public boolean supportsNativeTools()  { return true; }
    }

    private static Stream<Object[]> decorators() {
        return Stream.of(
                new Object[]{"LoggingLlmClient",      (Function<LlmClient, LlmClient>) d -> new LoggingLlmClient(d, 100)},
                new Object[]{"InstrumentedLlmClient", (Function<LlmClient, LlmClient>) d -> new InstrumentedLlmClient(d, AraTelemetry.noop())},
                new Object[]{"InterceptingLlmClient", (Function<LlmClient, LlmClient>) d -> new InterceptingLlmClient(
                        d, AgentInterceptorChain.empty(), () -> (AgentExecutionContext) null)}
        );
    }

    @ParameterizedTest(name = "{0} forwards supportsNativeTools")
    @MethodSource("decorators")
    void forwards_supportsNativeTools(String name, Function<LlmClient, LlmClient> wrap) {
        assertTrue(wrap.apply(new CapableClient()).supportsNativeTools(),
                name + " must report the delegate's native-tool capability, not the leaf default");
    }

    @ParameterizedTest(name = "{0} forwards providerId and lastUsedProviderId")
    @MethodSource("decorators")
    void forwards_provider_identity(String name, Function<LlmClient, LlmClient> wrap) {
        LlmClient decorated = wrap.apply(new CapableClient());
        assertEquals("capable-provider", decorated.providerId(), name);
        assertEquals("capable-last-used", decorated.lastUsedProviderId(), name);
    }
}
