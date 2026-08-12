package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.ara.runtime.pipeline.PipelineTestAgents.echoAgent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentPipeline.FsmBuilder}.
 *
 * <p>Each test targets a distinct FSM pattern:
 * <ul>
 *   <li>linear chain (no explicit transitions)</li>
 *   <li>terminal state stops the pipeline</li>
 *   <li>unconditional transition</li>
 *   <li>conditional transition using {@link PipelineExecution}</li>
 *   <li>review/revise loop with loop detection via execution history</li>
 *   <li>escalation path triggered by history count</li>
 *   <li>custom initial state</li>
 *   <li>maxSteps guard</li>
 *   <li>duplicate state name</li>
 *   <li>unknown initial state</li>
 * </ul>
 */
class AgentPipelineFsmBuilderTest {

    // ── Linear / sequential ───────────────────────────────────────────────────

    @Test
    void linear_chain_without_explicit_transitions_executes_in_declaration_order() {
        AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                .state("a", echoAgent("a", "out-a"))
                .state("b", echoAgent("b", "out-b"))
                .state("c", echoAgent("c", "out-c"))
                .build();

        PipelineResult r = pipeline.run("start");
        assertTrue(r.success());
        assertEquals("out-c", r.finalOutput());
        assertEquals(List.of("a", "b", "c"), r.stepsExecuted());
    }

    // ── Terminal state ────────────────────────────────────────────────────────

    @Test
    void terminal_state_stops_pipeline_before_subsequent_states() {
        AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                .state("a",    echoAgent("a",    "out-a"))
                .state("done", echoAgent("done", "finished"))
                .state("c",    echoAgent("c",    "should-not-run"))
                .terminal("done")
                .transition("a", "done")
                .build();

        PipelineResult r = pipeline.run("start");
        assertTrue(r.success());
        assertEquals("finished", r.finalOutput());
        assertFalse(r.stepsExecuted().contains("c"), "state after terminal must not execute");
    }

    @Test
    void multiple_terminal_states_each_stop_the_pipeline() {
        for (String verdict : List.of("APPROVED", "REJECTED")) {
            AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                    .state("review",   echoAgent("review",   "VERDICT: " + verdict))
                    .state("approved", echoAgent("approved", "ok"))
                    .state("rejected", echoAgent("rejected", "ko"))
                    .terminal("approved", "rejected")
                    .transition("review", exec ->
                            exec.lastOutput().contains("APPROVED") ? "approved" : "rejected")
                    .build();

            PipelineResult r = pipeline.run("proposal");
            assertTrue(r.success(), "pipeline must succeed for verdict=" + verdict);
            assertEquals(verdict.equals("APPROVED") ? "ok" : "ko", r.finalOutput());
        }
    }

    // ── Unconditional transition ──────────────────────────────────────────────

    @Test
    void unconditional_transition_overrides_sequential_order() {
        AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                .state("a", echoAgent("a", "out-a"))
                .state("b", echoAgent("b", "out-b"))  // declared second but never reached
                .state("c", echoAgent("c", "out-c"))
                .terminal("c")
                .transition("a", "c")            // skip b
                .build();

        PipelineResult r = pipeline.run("start");
        assertTrue(r.success());
        assertFalse(r.stepsExecuted().contains("b"), "b must be skipped");
        assertEquals(List.of("a", "c"), r.stepsExecuted());
    }

    // ── Conditional transition with PipelineExecution ─────────────────────────

    @Test
    void conditional_transition_routes_based_on_last_output() {
        AgentPipeline pipelineA = AgentPipeline.fsmBuilder()
                .state("check", echoAgent("check", "STATUS: OK"))
                .state("pass",  echoAgent("pass",  "passed"))
                .state("fail",  echoAgent("fail",  "failed"))
                .terminal("pass", "fail")
                .transition("check", exec ->
                        exec.lastOutput().contains("OK") ? "pass" : "fail")
                .build();

        AgentPipeline pipelineB = AgentPipeline.fsmBuilder()
                .state("check", echoAgent("check", "STATUS: ERROR"))
                .state("pass",  echoAgent("pass",  "passed"))
                .state("fail",  echoAgent("fail",  "failed"))
                .terminal("pass", "fail")
                .transition("check", exec ->
                        exec.lastOutput().contains("OK") ? "pass" : "fail")
                .build();

        assertEquals("passed", pipelineA.run("go").finalOutput());
        assertEquals("failed",  pipelineB.run("go").finalOutput());
    }

    @Test
    void router_can_access_initial_input_via_execution() {
        String[] capturedInitial = {null};

        AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                .state("a", echoAgent("a", "out-a"))
                .state("b", echoAgent("b", "out-b"))
                .terminal("b")
                .transition("a", exec -> {
                    capturedInitial[0] = exec.initialInput();
                    return "b";
                })
                .build();

        pipeline.run("original-input");
        assertEquals("original-input", capturedInitial[0]);
    }

    // ── Review/revise loop ────────────────────────────────────────────────────

    @Test
    void review_revise_loop_resolves_after_one_revision() {
        AtomicInteger reviewCount = new AtomicInteger();

        AraAgent reviewAgent = new AraAgent() {
            final AgentId id = AgentId.of("review");
            @Override public AgentId agentId()       { return id; }
            @Override public AgentConfig config()     { return null; }
            @Override public AgentState currentState(){ return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                String verdict = reviewCount.incrementAndGet() == 1 ? "VERDICT: REVISE" : "VERDICT: APPROVED";
                return ok(task, "review", verdict);
            }
            @Override public void terminate() {}
        };

        AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                .state("draft",  echoAgent("draft",  "design text"))
                .state("review", reviewAgent)
                .state("revise", echoAgent("revise", "revised design"))
                .state("done",   echoAgent("done",   "approved!"))
                .terminal("done")
                .transition("draft", "review")
                .transition("review", exec ->
                        exec.lastOutput().contains("APPROVED") ? "done" : "revise")
                .transition("revise", "review")
                .build();

        PipelineResult r = pipeline.run("feature request");
        assertTrue(r.success());
        assertEquals("approved!", r.finalOutput());
        assertEquals(List.of("draft", "review", "revise", "review", "done"), r.stepsExecuted());
    }

    @Test
    void loop_detection_via_history_count_triggers_escalation() {
        // review always says REVISE — router must escalate after 2 revisions
        AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                .state("draft",    echoAgent("draft",    "design"))
                .state("review",   echoAgent("review",   "VERDICT: REVISE"))
                .state("revise",   echoAgent("revise",   "revised"))
                .state("escalate", echoAgent("escalate", "ESCALATED"))
                .state("done",     echoAgent("done",     "closed"))
                .terminal("done")
                .transition("draft", "review")
                .transition("review", exec -> {
                    if (exec.lastOutput().contains("APPROVED")) return "done";
                    return exec.attemptsOf("revise") >= 2 ? "escalate" : "revise";
                })
                .transition("revise",   "review")
                .transition("escalate", "done")
                .maxSteps(15)
                .build();

        PipelineResult r = pipeline.run("feature");
        assertTrue(r.success());
        assertEquals("closed", r.finalOutput());
        assertEquals(2, r.stepsExecuted().stream().filter("revise"::equals).count());
        assertTrue(r.stepsExecuted().contains("escalate"));
    }

    @Test
    void execution_history_contains_all_step_results_in_order() {
        List<String> capturedStepNames = new java.util.ArrayList<>();

        AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                .state("a", echoAgent("a", "out-a"))
                .state("b", echoAgent("b", "out-b"))
                .state("c", echoAgent("c", "out-c"))
                .terminal("c")
                .transition("a", "b")
                .transition("b", exec -> {
                    exec.history().forEach(r -> capturedStepNames.add(r.stepName()));
                    return "c";
                })
                .build();

        pipeline.run("start");
        assertEquals(List.of("a", "b"), capturedStepNames);
    }

    @Test
    void result_of_returns_latest_output_for_looped_state() {
        AtomicInteger pass = new AtomicInteger();
        AraAgent reviewAgent = new AraAgent() {
            final AgentId id = AgentId.of("review");
            @Override public AgentId agentId()        { return id; }
            @Override public AgentConfig config()      { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return ok(task, "review", pass.incrementAndGet() < 2 ? "REVISE" : "APPROVED");
            }
            @Override public void terminate() {}
        };

        String[] lastReviewOutput = {null};

        AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                .state("review", reviewAgent)
                .state("revise", echoAgent("revise", "patched"))
                .state("done",   echoAgent("done",   "ok"))
                .terminal("done")
                .transition("review", exec -> {
                    lastReviewOutput[0] = exec.resultOf("review").map(r -> r.output()).orElse(null);
                    return exec.lastOutput().contains("APPROVED") ? "done" : "revise";
                })
                .transition("revise", "review")
                .build();

        pipeline.run("go");
        // resultOf returns the latest review output at decision time
        assertEquals("APPROVED", lastReviewOutput[0]);
    }

    // ── Custom initial state ──────────────────────────────────────────────────

    @Test
    void custom_initial_state_starts_execution_from_specified_state() {
        AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                .state("skipped", echoAgent("skipped", "should-not-run"))
                .state("entry",   echoAgent("entry",   "entry-out"))
                .state("end",     echoAgent("end",     "done"))
                .terminal("end")
                .initial("entry")
                .transition("entry", "end")
                .build();

        PipelineResult r = pipeline.run("start");
        assertTrue(r.success());
        assertFalse(r.stepsExecuted().contains("skipped"));
        assertEquals(List.of("entry", "end"), r.stepsExecuted());
    }

    // ── maxSteps guard ────────────────────────────────────────────────────────

    @Test
    void infinite_loop_is_caught_by_max_steps() {
        AgentPipeline pipeline = AgentPipeline.fsmBuilder()
                .state("a", echoAgent("a", "out"))
                .transition("a", "a")   // self-loop
                .maxSteps(4)
                .build();

        PipelineResult r = pipeline.run("go");
        assertFalse(r.success());
        assertTrue(r.failureReason().contains("maximum step count"));
        assertEquals(4, r.stepsExecuted().size());
    }

    // ── Validation errors ─────────────────────────────────────────────────────

    @Test
    void duplicate_state_name_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentPipeline.fsmBuilder()
                        .state("a", echoAgent("a", "out"))
                        .state("a", echoAgent("a2", "out"))
                        .build());
    }

    @Test
    void unknown_initial_state_throws() {
        assertThrows(IllegalStateException.class, () ->
                AgentPipeline.fsmBuilder()
                        .state("a", echoAgent("a", "out"))
                        .initial("nonexistent")
                        .build());
    }

    @Test
    void empty_fsm_throws() {
        assertThrows(IllegalStateException.class, () ->
                AgentPipeline.fsmBuilder().build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AgentResponse ok(AgentTask task, String id, String output) {
        return AgentResponse.success(
                task.taskId(), AgentId.of(id), output, 1, 0, 0, Duration.ofMillis(1), List.of());
    }
}
