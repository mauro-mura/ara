package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.ExecutionConfig;
import io.ara.core.agent.SessionBusyPolicy;
import io.ara.core.agent.StrategyConfig;

import static io.ara.runtime.spec.DocumentFields.put;
import static io.ara.runtime.spec.DocumentFields.set;
import static io.ara.runtime.spec.DocumentFields.stringArray;

/**
 * The {@code execution} section of an agent document: how a task runs — strategy, tools,
 * limits, authorization scopes. Package-private; reached only through
 * {@link AgentSpecDocument}. Stateless; thread-safe.
 *
 * <p><b>Strategy has two shapes because {@code AgentConfig} has two.</b> A bare string
 * ({@code "rag+react"}) sets only {@code plannerStrategy} and leaves {@code strategyConfig}
 * null; an object ({@code {"type": "plan_execute", ...}}) sets a typed {@link StrategyConfig},
 * from which the planner name is derived. The two are different configs (they compare
 * unequal), so the encoder writes the string form exactly when {@code strategyConfig} is
 * null and a round trip returns what it was given.
 *
 * <p><b>Authority fields are in the document.</b> {@code grantedScopes}, {@code
 * requiredScopes}, {@code humanApprovalRequired} and {@code requiresApproval} are part of
 * {@code ExecutionConfig}; leaving them out would make an export lossy. Decoding is pure and
 * does not decide who may import: that stays with whoever calls {@code createAgent}, the same
 * as for a config built in Java. Treat a document with the trust of a configuration file an
 * operator wrote, not of user input.
 */
final class ExecutionDocument {

    private ExecutionDocument() {}

    // ── decode ─────────────────────────────────────────────────────────────────────────

    static void decode(DocumentFields execution, AgentConfig.Builder builder) {
        decodeStrategy(execution, builder);
        set(builder::enabledTools, execution.stringList("tools"));
        set(builder::mcpServerIds, execution.stringList("mcpServers"));
        set(builder::maxIterations, execution.integer("maxIterations"));
        set(builder::executionTimeout, execution.duration("timeout"));
        set(builder::maxTokensPerStep, execution.integer("maxTokensPerStep"));
        set(builder::humanApprovalRequired, execution.bool("humanApprovalRequired"));
        set(builder::knowledgeBaseId, execution.string("knowledgeBaseId"));
        set(builder::retrieverId, execution.string("retrieverId"));
        set(builder::sessionBusyPolicy, execution.enumValue("sessionBusyPolicy", SessionBusyPolicy.class));
        set(builder::delegateStateAccess, execution.enumValue("delegateStateAccess", DelegateStateAccess.class));
        set(builder::sessionTtl, execution.duration("sessionTtl"));
        set(builder::grantedScopes, execution.stringList("grantedScopes"));
        set(builder::visibleToScopes, execution.stringList("visibleToScopes"));
        set(builder::requiredScopes, execution.stringList("requiredScopes"));
        set(builder::requiresApproval, execution.bool("requiresApproval"));
        execution.finish();
    }

    private static void decodeStrategy(DocumentFields execution, AgentConfig.Builder builder) {
        JsonNode raw = execution.raw("strategy");
        if (raw == null) {
            return;
        }
        if (raw.isTextual()) {
            builder.plannerStrategy(raw.asText());
        } else if (raw.isObject()) {
            builder.strategyConfig(StrategyDocument.decode(new DocumentFields(raw, execution.at("strategy"))));
        } else {
            throw new AgentSpecDocumentException(execution.at("strategy"),
                    "expected a strategy name or an object {\"type\": ...}, got " + raw.getNodeType().name().toLowerCase());
        }
    }

    // ── encode ─────────────────────────────────────────────────────────────────────────

    static ObjectNode encode(ExecutionConfig execution) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("strategy", execution.strategyConfig() == null
                ? JsonNodeFactory.instance.textNode(execution.plannerStrategy())
                : StrategyDocument.encode(execution.strategyConfig()));
        out.set("tools", stringArray(execution.enabledTools()));
        out.set("mcpServers", stringArray(execution.mcpServerIds()));
        out.put("maxIterations", execution.maxIterations());
        out.put("timeout", execution.executionTimeout().toString());
        out.put("maxTokensPerStep", execution.maxTokensPerStep());
        out.put("humanApprovalRequired", execution.humanApprovalRequired());
        put(out, "knowledgeBaseId", execution.knowledgeBaseId());
        put(out, "retrieverId", execution.retrieverId());
        out.put("sessionBusyPolicy", execution.sessionBusyPolicy().name());
        out.put("delegateStateAccess", execution.delegateStateAccess().name());
        out.put("sessionTtl", execution.sessionTtl().toString());
        out.set("grantedScopes", stringArray(execution.grantedScopes()));
        out.set("visibleToScopes", stringArray(execution.visibleToScopes()));
        out.set("requiredScopes", stringArray(execution.requiredScopes()));
        out.put("requiresApproval", execution.requiresApproval());
        return out;
    }
}
