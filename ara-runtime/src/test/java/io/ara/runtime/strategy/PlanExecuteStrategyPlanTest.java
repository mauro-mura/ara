package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaRef;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.strategy.PlanPhaseLlmClient.Phase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@code plan_execute} obtains its plan: the JSON format, what happens when the reply cannot
 * be read, and the native-schema request. Reading itself is covered by {@code PlanReaderTest};
 * these tests cover what the strategy does with the result.
 */
class PlanExecuteStrategyPlanTest {

    private static final String TWO_STEP_JSON = """
            {"steps":[{"id":"s1","goal":"Create Person","dependsOn":[]},
                      {"id":"s2","goal":"Create Address","dependsOn":["s1"]}]}""";

    private static final ToolRegistry NO_TOOLS = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(); }
        @Override public Optional<AraTool> findById(String id) { return Optional.empty(); }
        @Override public ToolResult execute(String toolId, String argumentJson) {
            return ToolResult.failure(toolId, "no tools in this test");
        }
    };

    private static MemoryManager seededMemory() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "build the model");
        return memory;
    }

    private static AgentConfig config(String replanPolicy, boolean nativeJsonSchema) {
        return AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").nativeJsonSchema(nativeJsonSchema).build())
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(20)
                .strategyConfig(new StrategyConfig.PlanExecute(replanPolicy, 8, 3))
                .build();
    }

    private static ExecutionResult run(PlanPhaseLlmClient llm, AgentConfig config) {
        return new PlanExecuteStrategy().execute(
                AgentTask.of("build the model"), llm, seededMemory(), NO_TOOLS, config);
    }

    // ── maxParallelSteps: batching by declared dependsOn ─────────────────────

    @Test
    void maxParallelSteps_withARealDependency_stillRunsTheStepsOneAfterTheOther() {
        // TWO_STEP_JSON's step 2 depends on step 1 ("dependsOn":["s1"]), so even with
        // maxParallelSteps=4 the batch starting at step 1 cannot reach step 2: its one
        // dependency is not yet satisfied before the batch starts. This is the correct
        // outcome of batching by declared dependencies, not a sign parallelism is unimplemented.
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEP_JSON);
        AgentConfig parallel = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").build())
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(20)
                .strategyConfig(new StrategyConfig.PlanExecute("never", 8, 3, 4))
                .build();

        ExecutionResult result = run(llm, parallel);

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals(2, llm.callsIn(Phase.STEP).size());
    }

    @Test
    void maxParallelSteps_withNoDependencies_runsTheStepsConcurrently() {
        String independentSteps = """
                {"steps":[{"id":"s1","goal":"Create Person","dependsOn":[]},
                          {"id":"s2","goal":"Create Address","dependsOn":[]}]}""";
        // Each worker needs its own reply queue slot: PlanPhaseLlmClient answers STEP calls off
        // one shared deque, so two concurrent step calls must each find a usable answer.
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies(independentSteps)
                .stepReplies("STEP_DONE", "STEP_DONE");
        AgentConfig parallel = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").build())
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(20)
                .strategyConfig(new StrategyConfig.PlanExecute("never", 8, 3, 4))
                .build();

        ExecutionResult result = run(llm, parallel);

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals(2, llm.callsIn(Phase.STEP).size());
    }

    @Test
    void maxParallelSteps_withNoDependencies_tracesBothStepsInPlanOrder() {
        // Both steps run concurrently, so the two STEP calls can arrive at the test double in
        // either order — asserting on llm.callsIn(Phase.STEP) by position would be asserting on
        // a race. What PlanTally.mergeFrom actually guarantees is that the recorded trace reads
        // in plan order regardless of completion order, which is what this checks instead: the
        // THOUGHT step for "Create Person" must precede the one for "Create Address" in
        // ExecutionResult.steps(), and both STEP calls must have happened (in whichever order).
        String independentSteps = """
                {"steps":[{"id":"s1","goal":"Create Person","dependsOn":[]},
                          {"id":"s2","goal":"Create Address","dependsOn":[]}]}""";
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies(independentSteps)
                .stepReplyFor("Execute step 1/2: Create Person", "Person done.")
                .stepReplyFor("Execute step 2/2: Create Address", "Address done.");
        AgentConfig parallel = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").build())
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(20)
                .strategyConfig(new StrategyConfig.PlanExecute("never", 8, 3, 4))
                .build();

        ExecutionResult result = run(llm, parallel);

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<String> stepPrompts = llm.callsIn(Phase.STEP).stream()
                .map(PlanExecuteStrategyPlanTest::lastUserMessage).toList();
        assertTrue(stepPrompts.stream().anyMatch(p -> p.contains("Execute step 1/2: Create Person")));
        assertTrue(stepPrompts.stream().anyMatch(p -> p.contains("Execute step 2/2: Create Address")));

        List<String> thoughtsInTraceOrder = result.steps().stream()
                .filter(s -> s.content() != null && (s.content().equals("Person done.")
                        || s.content().equals("Address done.")))
                .map(io.ara.core.agent.ExecutionStep::content).toList();
        assertEquals(List.of("Person done.", "Address done."), thoughtsInTraceOrder,
                "the merged trace must read in plan order, not completion order: " + result.steps());
    }

    @Test
    void maxParallelSteps_capsTheBatchSize() {
        // Three independent steps with maxParallelSteps=2: the first batch takes only the first
        // two, the third runs in a batch of its own — never more than the configured cap at once.
        String threeIndependentSteps = """
                {"steps":[{"id":"s1","goal":"Step A","dependsOn":[]},
                          {"id":"s2","goal":"Step B","dependsOn":[]},
                          {"id":"s3","goal":"Step C","dependsOn":[]}]}""";
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies(threeIndependentSteps)
                .stepReplies("STEP_DONE", "STEP_DONE", "STEP_DONE");
        AgentConfig cappedAtTwo = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").build())
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(20)
                .strategyConfig(new StrategyConfig.PlanExecute("never", 8, 3, 2))
                .build();

        ExecutionResult result = run(llm, cappedAtTwo);

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<PlanPhaseLlmClient.Call> steps = llm.callsIn(Phase.STEP);
        assertEquals(3, steps.size());
        assertTrue(lastUserMessage(steps.get(2)).contains("Execute step 3/3: Step C"));
    }

    @Test
    void aChainOfDependencies_runsOneAtATimeEvenWithParallelismAllowed() {
        // s2 depends on s1, s3 depends on s2: every batch along this chain has exactly one
        // satisfiable step, so a cap of 4 changes nothing — the plan is sequential by its own
        // declared structure, not by any limit this strategy imposes.
        String chain = """
                {"steps":[{"id":"s1","goal":"First","dependsOn":[]},
                          {"id":"s2","goal":"Second","dependsOn":["s1"]},
                          {"id":"s3","goal":"Third","dependsOn":["s2"]}]}""";
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies(chain)
                .stepReplies("STEP_DONE", "STEP_DONE", "STEP_DONE");
        AgentConfig parallel = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").build())
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(20)
                .strategyConfig(new StrategyConfig.PlanExecute("never", 8, 3, 4))
                .build();

        ExecutionResult result = run(llm, parallel);

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals(3, llm.callsIn(Phase.STEP).size());
    }

    @Test
    void noMaxParallelSteps_meansOneAtATimeEvenWithNoDependencies() {
        // The default (maxParallelSteps unset -> parallelSteps() == 1) must not change just
        // because the plan happens to declare independent steps: parallelism is opt-in.
        String independentSteps = """
                {"steps":[{"id":"s1","goal":"Create Person","dependsOn":[]},
                          {"id":"s2","goal":"Create Address","dependsOn":[]}]}""";
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies(independentSteps)
                .stepReplies("STEP_DONE", "STEP_DONE");

        ExecutionResult result = run(llm, config("never", false));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals(2, llm.callsIn(Phase.STEP).size());
    }

    // ── a JSON plan is executed ─────────────────────────────────────────────

    @Test
    void aJsonPlan_runsEachGoalAsAStep_inTheWrittenOrder() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEP_JSON);

        ExecutionResult result = run(llm, config("never", false));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<PlanPhaseLlmClient.Call> steps = llm.callsIn(Phase.STEP);
        assertEquals(2, steps.size());
        assertTrue(lastUserMessage(steps.get(0)).contains("Execute step 1/2: Create Person"));
        assertTrue(lastUserMessage(steps.get(1)).contains("Execute step 2/2: Create Address"));
    }

    @Test
    void aStepPrompt_isOneUserMessage_withTheTaskThePlanAndTheStepInOrder() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEP_JSON);

        run(llm, config("never", false));

        // The second step has a finished step behind it, so every part of the prompt is present.
        List<LlmMessage> messages = llm.callsIn(Phase.STEP).get(1).messages();
        long userMessages = messages.stream().filter(m -> m.role().equals("user")).count();
        assertEquals(1, userMessages, "task, plan, results and step are one message, not one each");
        String prompt = lastUserMessage(llm.callsIn(Phase.STEP).get(1));
        int task = prompt.indexOf("Task: build the model");
        int plan = prompt.indexOf("Execution plan:");
        int results = prompt.indexOf("Completed step results:");
        int step = prompt.indexOf("Execute step 2/2: Create Address");
        assertTrue(0 == task && task < plan && plan < results && results < step, prompt);
    }

    @Test
    void aStepPrompt_keepsTheTasksAttachments() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEP_JSON);
        MediaRef picture = new MediaRef("m1", "image/png", "plan.png", 3, null);

        new PlanExecuteStrategy().execute(AgentTask.of("build the model", List.of(picture)), llm,
                seededMemory(), NO_TOOLS, config("never", false));

        for (PlanPhaseLlmClient.Call step : llm.callsIn(Phase.STEP)) {
            List<LlmMessage> messages = step.messages();
            assertEquals(List.of(picture), messages.get(messages.size() - 1).media());
        }
    }

    @Test
    void aNumberedListPlan_isStillExecuted() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. Create Person\n2. Create Address");

        ExecutionResult result = run(llm, config("never", false));

        assertTrue(result.isSuccess());
        assertEquals(2, llm.callsIn(Phase.STEP).size());
    }

    // ── where the format instruction goes ────────────────────────────────────

    @Test
    void theJsonFormatInstruction_isInTheUserMessage_notTheSystemPrompt() {
        // Measured against gpt-oss-20b behind LM Studio: a system prompt demanding "a JSON object
        // and nothing else" made the server fail every planning call; the same words in the user
        // message worked every time. A planning call that dies before producing a plan cannot be
        // recovered, so the system prompt only names the phase.
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEP_JSON);

        run(llm, config("never", false));

        PlanPhaseLlmClient.Call plan = llm.callsIn(Phase.PLAN).get(0);
        assertFalse(plan.messages().get(0).content().contains("JSON"),
                "the system prompt of the planning call must not ask for JSON");
        assertTrue(lastUserMessage(plan).contains("one JSON object"));
    }

    @Test
    void theReplanFormatInstruction_isInTheUserMessage_too() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies("{\"steps\":[{\"id\":\"s1\",\"goal\":\"Doomed\",\"dependsOn\":[]}]}")
                .stepReplies("", "STEP_DONE");

        run(llm, config("on_failure", false));

        PlanPhaseLlmClient.Call replan = llm.callsIn(Phase.REPLAN).get(0);
        assertFalse(replan.messages().get(0).content().contains("JSON"));
        assertTrue(lastUserMessage(replan).contains("one JSON object"));
    }

    @Test
    void thePlanner_isShownTheToolsByNameAndDescription_butNotTheirArgumentSchemas() {
        AraTool tool = new AraTool() {
            @Override public String toolId() { return "write_file"; }
            @Override public String description() { return "Writes a file."; }
            @Override public String argumentSchema() { return "{\"SCHEMA_MARKER\":true}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "ok"); }
        };
        ToolRegistry registry = new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(tool); }
            @Override public Optional<AraTool> findById(String id) { return Optional.of(tool); }
            @Override public ToolResult execute(String toolId, String argumentJson) { return tool.execute(argumentJson); }
        };
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEP_JSON);

        new PlanExecuteStrategy().execute(AgentTask.of("build the model"), llm, seededMemory(), registry,
                config("never", false));

        String planner = llm.callsIn(Phase.PLAN).get(0).messages().get(0).content();
        assertTrue(planner.contains("write_file: Writes a file."), "the planner must know what tools exist");
        assertFalse(planner.contains("SCHEMA_MARKER"),
                "schemas are for calling a tool; a JSON-looking schema next to a request for a JSON plan "
                        + "made a local model server fail every planning call");
        String step = llm.callsIn(Phase.STEP).get(0).messages().get(0).content();
        assertTrue(step.contains("SCHEMA_MARKER"), "a step still sees the full catalogue it needs to call tools");
    }

    // ── an unreadable plan is an error, never an invented plan ──────────────

    @Test
    void anUnreadablePlan_withReplanNever_failsWithoutExecutingAnything() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("I would just answer directly.");

        ExecutionResult result = run(llm, config("never", false));

        assertFalse(result.isSuccess());
        assertTrue(result.failureReasonOpt().orElse("").contains("could not be read"),
                () -> "unexpected failure reason: " + result.failureReasonOpt());
        assertEquals(1, llm.callsIn(Phase.PLAN).size(), "no second chance under replanPolicy=never");
        assertTrue(llm.callsIn(Phase.STEP).isEmpty(), "no step may run on an invented plan");
        assertTrue(llm.callsIn(Phase.SYNTHESIS).isEmpty());
    }

    @Test
    void anEmptyPlanReply_isUnreadableToo() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("");

        ExecutionResult result = run(llm, config("never", false));

        assertFalse(result.isSuccess());
        assertTrue(llm.callsIn(Phase.STEP).isEmpty());
    }

    @Test
    void anUnreadablePlan_withReplanOnFailure_isAskedForOnceMore_withTheReason() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies("I would just answer directly.", TWO_STEP_JSON);

        ExecutionResult result = run(llm, config("on_failure", false));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<PlanPhaseLlmClient.Call> plans = llm.callsIn(Phase.PLAN);
        assertEquals(2, plans.size());
        assertFalse(lastUserMessage(plans.get(0)).contains("could not be read as a plan"));
        assertTrue(lastUserMessage(plans.get(1)).contains("could not be read as a plan"),
                "the retry must tell the model what was wrong with its first reply");
        assertEquals(2, llm.callsIn(Phase.STEP).size());
    }

    @Test
    void anUnreadablePlan_askedForTwice_failsAfterTheRetry() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("prose", "more prose");

        ExecutionResult result = run(llm, config("on_failure", false));

        assertFalse(result.isSuccess());
        assertEquals(2, llm.callsIn(Phase.PLAN).size(), "exactly one retry, not a loop");
        assertTrue(llm.callsIn(Phase.STEP).isEmpty());
    }

    @Test
    void theUnreadableAttempt_staysInTheTrace() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("prose", TWO_STEP_JSON);

        ExecutionResult result = run(llm, config("on_failure", false));

        assertTrue(result.steps().stream().anyMatch(step -> "prose".equals(step.content())),
                "both attempts are visible to whoever reads the trace");
    }

    // ── a failed step's replan is read like the first plan ───────────────────

    @Test
    void aRegeneratedPlanInJson_isReadAndExecuted() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies("{\"steps\":[{\"id\":\"s1\",\"goal\":\"Doomed step\",\"dependsOn\":[]}]}")
                // the doomed step yields nothing, the retried one succeeds
                .stepReplies("", "STEP_DONE")
                .replanReplies("{\"steps\":[{\"id\":\"s1\",\"goal\":\"Better step\",\"dependsOn\":[]}]}");

        ExecutionResult result = run(llm, config("on_failure", false));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<PlanPhaseLlmClient.Call> steps = llm.callsIn(Phase.STEP);
        assertTrue(lastUserMessage(steps.get(steps.size() - 1)).contains("Better step"));
    }

    // ── the native schema ────────────────────────────────────────────────────

    @Test
    void whenTheAgentAsksForNativeSchemaAndTheClientCan_thePlanCallCarriesThePlanSchema() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .nativeStructuredOutput(true).planReplies(TWO_STEP_JSON);

        run(llm, config("never", true));

        assertEquals(PlanReader.SCHEMA, llm.callsIn(Phase.PLAN).get(0).context().outputJsonSchema());
        assertEquals(PlanReader.SCHEMA_NAME, llm.callsIn(Phase.PLAN).get(0).context().outputSchemaName());
        llm.callsIn(Phase.STEP).forEach(call -> assertNull(call.context().outputJsonSchema(),
                "only the plan call is constrained to the plan schema"));
        llm.callsIn(Phase.SYNTHESIS).forEach(call -> assertNull(call.context().outputJsonSchema()));
    }

    @Test
    void whenTheClientCannot_thePlanCallCarriesNoSchema() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .nativeStructuredOutput(false).planReplies(TWO_STEP_JSON);

        run(llm, config("never", true));

        assertNull(llm.callsIn(Phase.PLAN).get(0).context().outputJsonSchema());
    }

    @Test
    void whenTheAgentDidNotAskForNativeSchema_thePlanCallCarriesNoSchema_evenIfTheClientCan() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .nativeStructuredOutput(true).planReplies(TWO_STEP_JSON);

        run(llm, config("never", false));

        assertNull(llm.callsIn(Phase.PLAN).get(0).context().outputJsonSchema(),
                "an agent that never opted in must keep sending the request it has always sent");
    }

    @Test
    void theReplanCall_carriesThePlanSchemaToo() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .nativeStructuredOutput(true)
                .planReplies("{\"steps\":[{\"id\":\"s1\",\"goal\":\"Doomed\",\"dependsOn\":[]}]}")
                .stepReplies("", "STEP_DONE");

        run(llm, config("on_failure", true));

        assertEquals(PlanReader.SCHEMA, llm.callsIn(Phase.REPLAN).get(0).context().outputJsonSchema());
    }

    private static String lastUserMessage(PlanPhaseLlmClient.Call call) {
        List<LlmMessage> messages = call.messages();
        return messages.get(messages.size() - 1).content();
    }
}
