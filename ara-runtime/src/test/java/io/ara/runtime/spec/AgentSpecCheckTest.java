package io.ara.runtime.spec;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentView;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmProfile;
import io.ara.core.spec.AgentSpec;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.bus.ScopeFilteringToolRegistry;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentSpecCheck} reports the misspelt names that {@code createAgent} would accept:
 * a tool the registry cannot resolve, and a model that would silently fall back to the
 * default client.
 */
class AgentSpecCheckTest {

    private static final AraTool SEARCH = new AraTool() {
        @Override public String toolId() { return "search"; }
        @Override public String description() { return "search"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.failure("search", "unused"); }
    };

    /** A registry that knows exactly one tool, and, like the real ones, skips unknown ids silently. */
    private static final ToolRegistry ONE_TOOL = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) {
            return ids.contains("search") ? List.of(SEARCH) : List.of();
        }
        @Override public Optional<AraTool> findById(String toolId) {
            return "search".equals(toolId) ? Optional.of(SEARCH) : Optional.empty();
        }
        @Override public ToolResult execute(String toolId, String argumentJson) {
            return ToolResult.failure(toolId, "unused");
        }
    };

    private static AraRuntime runtime() {
        return AraRuntime.builder()
                .llmClient("main", ScriptedLlmClient.script().thenFinalAnswer("ok").build())
                .toolRegistry(ONE_TOOL)
                .build();
    }

    private static AgentSpec spec(String model, String... tools) {
        return AgentSpec.root(AgentConfig.defaults().agentType("a")
                .primaryLlm(LlmProfile.of(model)).enabledTools(List.of(tools)).build());
    }

    @Test
    void everyNameRegistered_noProblems() {
        assertEquals(List.of(), AgentSpecCheck.problems(spec("main", "search"), runtime()));
    }

    @Test
    void aMisspeltTool_isReported() {
        List<String> problems = AgentSpecCheck.problems(spec("main", "serach"), runtime());

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("'serach'"), problems.get(0));
    }

    @Test
    void aMisspeltModel_isReported_withTheRegisteredNames() {
        List<String> problems = AgentSpecCheck.problems(spec("tpyo", "search"), runtime());

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("'tpyo'") && problems.get(0).contains("[main]"), problems.get(0));
    }

    @Test
    void aBlankModel_meansTheDefaultClient_andIsNotAProblem() {
        assertEquals(List.of(), AgentSpecCheck.problems(spec("", "search"), runtime()));
    }

    @Test
    void aFallbackModel_isChecked_withItsIndex() {
        AgentSpec spec = AgentSpec.root(AgentConfig.defaults().agentType("a")
                .primaryLlm(LlmProfile.of("main")).fallbackLlms(List.of(LlmProfile.of("main"), LlmProfile.of("gone")))
                .build());

        List<String> problems = AgentSpecCheck.problems(spec, runtime());

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("llm.fallbacks[1].model"), problems.get(0));
    }

    @Test
    void allProblemsAreCollected_notJustTheFirst() {
        assertEquals(3, AgentSpecCheck.problems(spec("tpyo", "serach", "calc"), runtime()).size());
    }

    /** A tool that exists but needs a scope the caller does not hold. */
    private static final AraTool RESTRICTED = new AraTool() {
        @Override public String toolId() { return "payroll"; }
        @Override public String description() { return "payroll"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.failure("payroll", "unused"); }
        @Override public List<String> requiredScopes() { return List.of("hr:payroll"); }
    };

    private static final ToolRegistry PAYROLL_REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) {
            return ids.contains("payroll") ? List.of(RESTRICTED) : List.of();
        }
        @Override public Optional<AraTool> findById(String toolId) {
            return "payroll".equals(toolId) ? Optional.of(RESTRICTED) : Optional.empty();
        }
        @Override public ToolResult execute(String toolId, String argumentJson) {
            return ToolResult.failure(toolId, "unused");
        }
    };

    /** A caller holding no scopes at all. */
    private static final AgentView NO_SCOPES = new AgentView() {
        @Override public Optional<AraAgent> findById(AgentId id) { return Optional.empty(); }
        @Override public Collection<AraAgent> all() { return List.of(); }
        @Override public String catalog(String contextInput) { return ""; }
        @Override public ScopeSet effectiveScopes() { return ScopeSet.EMPTY; }
    };

    @Test
    void aToolHiddenByAScope_isStillKnown_notReportedAsMissing() {
        ToolRegistry filtered = new ScopeFilteringToolRegistry(PAYROLL_REGISTRY, NO_SCOPES);
        AraRuntime scoped = AraRuntime.builder()
                .llmClient("main", ScriptedLlmClient.script().thenFinalAnswer("ok").build())
                .toolRegistry(filtered)
                .build();

        // The scoped registry really does hide the tool from resolveEnabled...
        assertEquals(List.of(), filtered.resolveEnabled(List.of("payroll")));
        // ...but it exists, so the check must not call it a typo.
        assertEquals(List.of(), AgentSpecCheck.problems(spec("main", "payroll"), scoped));
    }

    @Test
    void nullArguments_areRejected() {
        assertThrows(NullPointerException.class, () -> AgentSpecCheck.problems(null, runtime()));
        assertThrows(NullPointerException.class, () -> AgentSpecCheck.problems(spec("main"), null));
    }
}
