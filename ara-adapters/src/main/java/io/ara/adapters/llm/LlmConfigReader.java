package io.ara.adapters.llm;

import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.core.llm.LlmClient;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds named {@link LlmClient}s from the flat {@code ara.llm.<name>.<key>} entries of a configuration
 * map — the implementation behind {@link AraLlmClientFactory#fromConfig(Map)}, which carries the
 * user-facing description of the keys.
 *
 * <h2>Why a flat map, and why here</h2>
 * <p>The input is a {@code Map<String, String>} of dotted keys, which is what
 * {@code io.ara.runtime.config.AraYamlLoader} produces. This class does not read YAML and does not know
 * that module exists: {@code ara-adapters} depends on {@code ara-core} only, and the YAML loader lives in
 * {@code ara-runtime}. The caller — an application, which has both — passes the map across. That keeps
 * this class testable with a literal {@code Map.of(...)} and leaves one place that knows YAML.
 *
 * <p>It belongs in {@code ara-adapters} rather than {@code ara-runtime} because turning {@code provider:
 * openai} into a client needs the provider builders, and the runtime must not depend on the adapters and
 * the LangChain4j tree under them.
 *
 * <h2>Strictness</h2>
 * <p>Every refusal names the full key ({@code ara.llm.main.modle}), because the reader of the message has a
 * YAML file open, not a stack trace. An unknown key is an error, not ignored: {@code modle: gpt-4o} would
 * otherwise build a client on the adapter's default model and fail, or worse succeed, far from the typo.
 * A key that belongs to another provider is the same mistake. A blank value counts as absent, which is what
 * {@code ${VAR}} becomes when {@code VAR} is unset — so a missing API key is reported as a missing key, with
 * a hint about the variable, instead of as a 401 at the first request.
 *
 * <h2>Providers, and the ones left out</h2>
 * <p>{@code openai}, {@code anthropic}, {@code mistral} and {@code ollama}: the providers whose builders are
 * plain settings. {@code chatJimmy} (a proxy, a rotating client-IP header) and {@code openCode} (launches a
 * local executable, and its free-tier tools grant access on whichever host the server sits on) are not
 * reachable from a file: a deployment file is the wrong place to make those decisions, and their builders
 * carry the warnings in code, where they are read. Adding a provider is one {@code case} in {@link #build}
 * and a key list; no registration mechanism, because nothing needs to be plugged in from outside and a
 * lookup by name would be reflection or a service loader for the sake of four entries.
 *
 * <h2>What it does not do</h2>
 * <p>It does not contact any provider: a builder records settings, so loading a file needs neither network
 * nor a valid key, and a wrong URL or key shows at the first request. It does not substitute environment
 * variables — that is the YAML loader's job ({@code ${VAR}} and {@code ${VAR:default}}) — and it invents no
 * default for a key (in particular, no placeholder API key for a local OpenAI-compatible server: write
 * {@code apiKey: ${LLM_API_KEY:not-required}} and the choice is in the file).
 */
final class LlmConfigReader {

    static final String PREFIX = "ara.llm.";

    private static final String PROVIDERS = "openai, anthropic, mistral or ollama";

    private LlmConfigReader() {}

    /** The clients of every {@code ara.llm.<name>} entry in {@code config}, by name; empty when there are none. */
    static Map<String, LlmClient> read(Map<String, String> config) {
        Objects.requireNonNull(config, "config must not be null");
        Map<String, Map<String, String>> byName = new TreeMap<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(PREFIX)) continue;
            String rest = key.substring(PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot <= 0 || dot == rest.length() - 1) {
                throw new IllegalArgumentException("'" + key + "': an LLM entry is ara.llm.<name>.<key>");
            }
            byName.computeIfAbsent(rest.substring(0, dot), n -> new TreeMap<>())
                    .put(rest.substring(dot + 1), entry.getValue());
        }
        Map<String, LlmClient> clients = new LinkedHashMap<>();
        byName.forEach((name, props) -> clients.put(name, build(new Entry(name, props))));
        return Collections.unmodifiableMap(clients);
    }

    private static LlmClient build(Entry e) {
        String provider = e.optional("provider")
                .orElseThrow(() -> new IllegalArgumentException(
                        "ara.llm." + e.name + ".provider is required (" + PROVIDERS + ")"));
        try {
            return switch (provider.toLowerCase(Locale.ROOT)) {
                case "openai" -> keyed(e, AraLlmClientFactory.openAi(), true).build();
                case "anthropic" -> keyed(e, AraLlmClientFactory.anthropic(), false).build();
                case "mistral" -> keyed(e, AraLlmClientFactory.mistral(), false).build();
                case "ollama" -> ollama(e).build();
                default -> throw new IllegalArgumentException(
                        "ara.llm." + e.name + ".provider: unknown '" + provider + "' (" + PROVIDERS + ")");
            };
        } catch (IllegalStateException refusedByTheAdapter) {
            // A builder's own refusal (its build() checks) said nothing about which entry it came from.
            throw new IllegalArgumentException("ara.llm." + e.name + ": " + refusedByTheAdapter.getMessage(),
                    refusedByTheAdapter);
        }
    }

    /**
     * The three adapters that share {@link AbstractLlmClientBuilder}'s eight settings. {@code modelRequired}
     * is true where the adapter has no default model to fall back on: OpenAI's builder starts with none,
     * while Anthropic's and Mistral's start with a catalogue model, so {@code model} may be left out and the
     * adapter's default applies.
     */
    private static <B extends AbstractLlmClientBuilder<B>> B keyed(Entry e, B builder, boolean modelRequired) {
        e.allow("model", "baseUrl", "apiKey", "temperature", "maxTokens", "timeoutSeconds",
                "logRequests", "logResponses");
        if (modelRequired) {
            builder.modelName(e.required("model"));
        } else {
            e.optional("model").ifPresent(builder::modelName);
        }
        e.optional("baseUrl").ifPresent(builder::baseUrl);
        builder.apiKey(e.required("apiKey"));
        e.optional("temperature").ifPresent(v -> builder.temperature(e.temperature(v)));
        e.optional("maxTokens").ifPresent(v -> builder.maxTokens(e.positiveInt("maxTokens", v)));
        e.optional("timeoutSeconds").ifPresent(v -> builder.timeout(Duration.ofSeconds(e.positiveInt("timeoutSeconds", v))));
        e.optional("logRequests").ifPresent(v -> builder.logRequests(e.bool("logRequests", v)));
        e.optional("logResponses").ifPresent(v -> builder.logResponses(e.bool("logResponses", v)));
        return builder;
    }

    /**
     * Ollama does not share {@link AbstractLlmClientBuilder} (no API key, no max-tokens; see that class), so
     * it has its own, shorter, key list. Model and base URL are optional because its builder defaults them
     * to a local {@code llama3.2} on {@code http://localhost:11434}.
     */
    private static OllamaLlmClient.Builder ollama(Entry e) {
        e.allow("model", "baseUrl", "temperature", "timeoutSeconds", "logRequests", "logResponses");
        OllamaLlmClient.Builder builder = AraLlmClientFactory.ollama();
        e.optional("model").ifPresent(builder::modelName);
        e.optional("baseUrl").ifPresent(builder::baseUrl);
        e.optional("temperature").ifPresent(v -> builder.temperature(e.temperature(v)));
        e.optional("timeoutSeconds").ifPresent(v -> builder.timeout(Duration.ofSeconds(e.positiveInt("timeoutSeconds", v))));
        e.optional("logRequests").ifPresent(v -> builder.logRequests(e.bool("logRequests", v)));
        e.optional("logResponses").ifPresent(v -> builder.logResponses(e.bool("logResponses", v)));
        return builder;
    }

    /** One {@code ara.llm.<name>} entry: its keys, with checks whose messages name the full key. */
    private static final class Entry {
        private final String name;
        private final Map<String, String> props;

        Entry(String name, Map<String, String> props) {
            this.name = name;
            this.props = props;
        }

        /** Rejects any key beyond {@code provider} and {@code allowed}. */
        void allow(String... allowed) {
            Set<String> known = new TreeSet<>(List.of(allowed));
            known.add("provider");
            for (String key : props.keySet()) {
                if (!known.contains(key)) {
                    throw new IllegalArgumentException("ara.llm." + name + "." + key
                            + ": unknown key for provider '" + props.get("provider") + "' (known: " + known + ")");
                }
            }
        }

        /** A blank value is absent: it is what an unset {@code ${VAR}} becomes. */
        Optional<String> optional(String key) {
            String value = props.get(key);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
        }

        String required(String key) {
            return optional(key).orElseThrow(() -> new IllegalArgumentException("ara.llm." + name + "." + key
                    + " is required for provider '" + props.get("provider") + "'"
                    + " (if it comes from an environment variable, is that variable set?)"));
        }

        double temperature(String value) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException notANumber) {
                throw bad("temperature", "must be a number", value);
            }
        }

        int positiveInt(String key, String value) {
            int parsed;
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException notAnInteger) {
                throw bad(key, "must be a whole number", value);
            }
            if (parsed <= 0) {
                throw bad(key, "must be greater than zero", value);
            }
            return parsed;
        }

        boolean bool(String key, String value) {
            if ("true".equalsIgnoreCase(value)) return true;
            if ("false".equalsIgnoreCase(value)) return false;
            throw bad(key, "must be true or false", value);
        }

        private IllegalArgumentException bad(String key, String problem, String value) {
            return new IllegalArgumentException("ara.llm." + name + "." + key + " " + problem + ", got '" + value + "'");
        }
    }
}
