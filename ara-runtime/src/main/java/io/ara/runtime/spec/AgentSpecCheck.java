package io.ara.runtime.spec;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmProfile;
import io.ara.core.spec.AgentSpec;
import io.ara.runtime.AraRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lists the names in a spec that the target runtime does not know — the mistakes that
 * {@code createAgent} would let through without a word.
 *
 * <p><b>Why this exists.</b> An agent defined in a file refers to tools and models by name,
 * and a misspelt name does not fail where one would expect:
 * <ul>
 *   <li>{@code ToolRegistry.resolveEnabled} silently skips an id it cannot resolve, so
 *       {@code "serach_documents"} produces an agent that simply has no search tool;</li>
 *   <li>an unregistered {@code transportId} falls back to the runtime's default client, so
 *       {@code "tpyo"} produces an agent that quietly runs on <em>another model</em>, with
 *       a different cost and quality.</li>
 * </ul>
 * For a file written by hand these are the likeliest typos, so the document loader offers
 * this check instead of leaving them to be found in production.
 *
 * <p><b>Why it is separate from {@code createAgent}.</b> Making {@code createAgent} strict
 * would change behaviour for every existing caller that builds configs in Java and relies on
 * the lenient lookup. That is a different decision; this check is opt-in.
 *
 * <p><b>What it does not cover.</b> A wrong strategy name already fails loudly at the first
 * task ({@code ExecutionPlanner}), and the runtime exposes no registry of MCP server ids to
 * compare against. A tool hidden from an agent by an authorization scope still counts as
 * known: it exists, it is merely not visible to that caller.
 *
 * <p>Stateless; thread-safe.
 */
public final class AgentSpecCheck {

    private AgentSpecCheck() {}

    /**
     * The problems found, one readable sentence each; empty when every referenced tool and
     * LLM client is registered on {@code runtime}.
     */
    public static List<String> problems(AgentSpec spec, AraRuntime runtime) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");
        AgentConfig config = spec.config();
        List<String> problems = new ArrayList<>();
        for (String tool : config.execution().enabledTools()) {
            if (runtime.toolRegistry().findById(tool).isEmpty()) {
                problems.add("tool '" + tool + "' (execution.tools) is not registered, so the agent would "
                        + "silently run without it");
            }
        }
        checkModel(config.llm().primary(), "llm.primary", runtime, problems);
        for (int index = 0; index < config.llm().fallbacks().size(); index++) {
            checkModel(config.llm().fallbacks().get(index), "llm.fallbacks[" + index + "]", runtime, problems);
        }
        return problems;
    }

    private static void checkModel(LlmProfile profile, String path, AraRuntime runtime, List<String> problems) {
        String model = profile.transportId();
        // A blank id means "use the runtime's default client" on purpose; only a name that was
        // written down and does not exist is a mistake.
        if (!model.isBlank() && !runtime.llmClients().containsKey(model)) {
            problems.add("model '" + model + "' (" + path + ".model) is not a registered LLM client, so the "
                    + "runtime would silently use its default client instead; registered: "
                    + runtime.llmClients().keySet().stream().sorted().toList());
        }
    }
}
