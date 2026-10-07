package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.agent.AgentConfig;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmConfig;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmSelectionPolicy;
import io.ara.core.llm.ReasoningEffort;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static io.ara.runtime.spec.DocumentFields.guard;
import static io.ara.runtime.spec.DocumentFields.put;
import static io.ara.runtime.spec.DocumentFields.set;

/**
 * The {@code llm} section of an agent document: which model(s) an agent calls and what they
 * cost. Package-private; reached only through {@link AgentSpecDocument}. Stateless;
 * thread-safe.
 *
 * <p><b>A model is a name, never an endpoint.</b> {@code model} is the id of a client
 * registered on the runtime ({@code LlmProfile.transportId}). {@code baseUrl}, {@code
 * modelName} and {@code apiKey} have no field here, so a secret cannot end up in a file by
 * construction. A profile that carries such an inline transport is therefore <em>not
 * representable</em>: encoding fails loudly instead of dropping the endpoint, because an
 * export that loses information and succeeds is worse than one that refuses.
 *
 * <p><b>Money is a string.</b> A JSON number goes through {@code double} and loses the
 * six-digit scale {@link Money} keeps, so the amount is always a string.
 *
 * <p>Defaults come from {@link AgentConfig.Builder} and {@link LlmProfile.Builder}: a field
 * the document leaves out is simply never set, so the defaults are not repeated here.
 */
final class LlmDocument {

    private static final String UNLIMITED = "unlimited";

    private LlmDocument() {}

    // ── decode ─────────────────────────────────────────────────────────────────────────

    static void decode(DocumentFields llm, AgentConfig.Builder builder) {
        DocumentFields primary = llm.optionalObject("primary");
        if (primary != null) {
            builder.primaryLlm(decodeProfile(primary));
        }
        List<LlmProfile> fallbacks = new ArrayList<>();
        for (DocumentFields fallback : llm.objectList("fallbacks")) {
            fallbacks.add(decodeProfile(fallback));
        }
        builder.fallbackLlms(fallbacks);
        set(builder::llmSelectionPolicy, llm.enumValue("selectionPolicy", LlmSelectionPolicy.class));
        set(builder::logLlmIo, llm.bool("logIo"));
        set(builder::logLlmIoMaxChars, llm.integer("logIoMaxChars"));
        llm.finish();
    }

    private static LlmProfile decodeProfile(DocumentFields fields) {
        LlmProfile.Builder profile = LlmProfile.builder();
        set(profile::transportId, fields.string("model"));
        set(profile::pinnedTransportVersion, fields.longValue("transportVersion"));
        set(profile::temperature, fields.decimal("temperature"));
        set(profile::topP, fields.decimal("topP"));
        set(profile::maxTokens, fields.integer("maxTokens"));
        set(profile::streamingEnabled, fields.bool("streaming"));
        set(profile::nativeJsonSchema, fields.bool("nativeJsonSchema"));

        String currency = fields.string("costCurrency");
        set(profile::costCurrency, currency);
        // The builder's zero prices are in EUR; a document that names another currency and
        // says nothing about prices would otherwise trip the "currency must match" check.
        set(profile::costInputPer1kTokens, price(fields, "costInputPer1k", currency));
        set(profile::costOutputPer1kTokens, price(fields, "costOutputPer1k", currency));
        set(profile::costBudget, decodeBudget(fields));
        decodeReasoning(fields.optionalObject("reasoning"), profile);
        fields.finish();
        return guard(fields.path(), profile::build);
    }

    /** The optional {@code reasoning} object of a profile; absent means every option stays unset. */
    private static void decodeReasoning(DocumentFields reasoning, LlmProfile.Builder profile) {
        if (reasoning == null) {
            return;
        }
        set(profile::reasoningEffort, reasoning.enumValue("effort", ReasoningEffort.class));
        set(profile::thinkingBudgetTokens, reasoning.integer("thinkingBudgetTokens"));
        set(profile::returnReasoning, reasoning.bool("returnReasoning"));
        reasoning.finish();
    }

    private static Money price(DocumentFields fields, String field, String currency) {
        DocumentFields money = fields.optionalObject(field);
        if (money != null) {
            return decodeMoney(money);
        }
        return currency == null ? null : Money.zero(currency);
    }

    private static Budget decodeBudget(DocumentFields fields) {
        JsonNode raw = fields.raw("budget");
        if (raw == null) {
            return null;
        }
        if (raw.isTextual() && UNLIMITED.equals(raw.asText())) {
            return Budget.unlimited();
        }
        if (raw.isObject()) {
            DocumentFields budget = new DocumentFields(raw, fields.at("budget"));
            Money cap = decodeMoney(budget.requireObject("cap"));
            budget.finish();
            return Budget.limited(cap);
        }
        throw new AgentSpecDocumentException(fields.at("budget"),
                "expected \"" + UNLIMITED + "\" or an object {\"cap\": {amount, currency}}");
    }

    private static Money decodeMoney(DocumentFields money) {
        String amount = money.requireString("amount");
        String currency = money.requireString("currency");
        money.finish();
        return guard(money.path(), () -> Money.of(new BigDecimal(amount), currency));
    }

    // ── encode ─────────────────────────────────────────────────────────────────────────

    static ObjectNode encode(LlmConfig llm) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("primary", encodeProfile(llm.primary(), "llm.primary"));
        ArrayNode fallbacks = out.putArray("fallbacks");
        for (int index = 0; index < llm.fallbacks().size(); index++) {
            fallbacks.add(encodeProfile(llm.fallbacks().get(index), "llm.fallbacks[" + index + "]"));
        }
        out.put("selectionPolicy", llm.policy().name());
        out.put("logIo", llm.logIo());
        out.put("logIoMaxChars", llm.logIoMaxChars());
        return out;
    }

    private static ObjectNode encodeProfile(LlmProfile profile, String path) {
        if (profile.inlineTransport() != null) {
            throw new AgentSpecDocumentException(path, "carries an inline transport (baseUrl/modelName/apiKey), "
                    + "which a document cannot represent: register the client on the runtime under a name "
                    + "and refer to it by model id");
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        put(out, "model", profile.transportId());
        if (profile.pinnedTransportVersion() != null) {
            out.put("transportVersion", profile.pinnedTransportVersion());
        }
        if (profile.temperature() != null) {
            out.put("temperature", profile.temperature());
        }
        if (profile.topP() != null) {
            out.put("topP", profile.topP());
        }
        if (profile.maxTokens() != null) {
            out.put("maxTokens", profile.maxTokens());
        }
        out.put("streaming", profile.streamingEnabled());
        out.put("nativeJsonSchema", profile.nativeJsonSchema());
        out.put("costCurrency", profile.costCurrency());
        out.set("costInputPer1k", encodeMoney(profile.costInputPer1kTokens()));
        out.set("costOutputPer1k", encodeMoney(profile.costOutputPer1kTokens()));
        out.set("budget", encodeBudget(profile.costBudget()));
        encodeReasoning(profile, out);
        return out;
    }

    /**
     * Written only when the profile sets one of the options, so the export of an agent that never
     * mentioned reasoning is what it was before the options existed (the version number does not change).
     */
    private static void encodeReasoning(LlmProfile profile, ObjectNode out) {
        if (profile.reasoningEffort() == null && profile.thinkingBudgetTokens() == null
                && profile.returnReasoning() == null) {
            return;
        }
        ObjectNode reasoning = out.putObject("reasoning");
        if (profile.reasoningEffort() != null) reasoning.put("effort", profile.reasoningEffort().name());
        if (profile.thinkingBudgetTokens() != null) reasoning.put("thinkingBudgetTokens", profile.thinkingBudgetTokens());
        if (profile.returnReasoning() != null) reasoning.put("returnReasoning", profile.returnReasoning());
    }

    private static JsonNode encodeBudget(Budget budget) {
        return switch (budget) {
            case Budget.Unlimited ignored -> JsonNodeFactory.instance.textNode(UNLIMITED);
            case Budget.Limited limited -> {
                ObjectNode out = JsonNodeFactory.instance.objectNode();
                out.set("cap", encodeMoney(limited.cap()));
                yield out;
            }
        };
    }

    private static ObjectNode encodeMoney(Money money) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("amount", money.amount().toPlainString());
        out.put("currency", money.currency());
        return out;
    }
}
