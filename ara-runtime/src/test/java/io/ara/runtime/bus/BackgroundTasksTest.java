package io.ara.runtime.bus;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AsyncDelegation;
import io.ara.core.agent.RunContext;
import io.ara.core.bus.Delegation;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-run registry of background tasks, driven with futures the test completes by hand, so
 * every state is reached on purpose instead of by timing.
 */
class BackgroundTasksTest {

    /** A started delegation the test controls, and how many times it was asked to stop. */
    private static final class Controlled {
        final CompletableFuture<AgentResponse> response = new CompletableFuture<>();
        final AtomicInteger cancels = new AtomicInteger();
        final Delegation delegation;

        Controlled(String taskId) {
            this.delegation = new Delegation(taskId, response, cancels::incrementAndGet);
        }

        void succeedWith(String content) {
            response.complete(AgentResponse.success(delegation.taskId(), AgentId.of("worker"), content,
                    1, 0, 0.0, Duration.ZERO, List.of()));
        }
    }

    private static BackgroundTasks tasks(int maxConcurrent) {
        return new BackgroundTasks(new AsyncDelegation(maxConcurrent, 50));
    }

    private static Controlled started(BackgroundTasks tasks, String taskId, String label) {
        assertNull(tasks.reserve(), "a slot should have been free");
        Controlled controlled = new Controlled(taskId);
        tasks.register(label, "worker", controlled.delegation);
        return controlled;
    }

    @Test
    void aFinishedTask_isDeliveredOnce_markedAsAutomated_withItsLabelAndResult() {
        BackgroundTasks tasks = tasks(4);
        started(tasks, "t-1", "sources").succeedWith("the sources");

        List<String> delivered = tasks.deliverFinished();

        assertEquals(1, delivered.size());
        assertTrue(delivered.get(0).startsWith(BackgroundTasks.AUTOMATED), delivered.get(0));
        assertTrue(delivered.get(0).contains("t-1 \"sources\" (worker): completed"), delivered.get(0));
        assertTrue(delivered.get(0).contains("[worker] the sources"), delivered.get(0));
        assertTrue(tasks.deliverFinished().isEmpty(), "a task is handed over once");
        assertFalse(tasks.hasTasks());
    }

    @Test
    void aRunningTask_isNotDelivered_untilItFinishes() {
        BackgroundTasks tasks = tasks(4);
        Controlled running = started(tasks, "t-1", "slow");

        assertTrue(tasks.deliverFinished().isEmpty());
        assertTrue(tasks.hasTasks());

        running.succeedWith("done");
        assertEquals(1, tasks.deliverFinished().size());
    }

    @Test
    void aResultLongerThanTheLimit_isCut_withTheCountOfWhatIsMissing() {
        BackgroundTasks tasks = tasks(4);
        started(tasks, "t-1", "big").succeedWith("x".repeat(80));

        String delivered = tasks.deliverFinished().get(0);

        assertTrue(delivered.contains("x".repeat(50) + "…[+30 characters not shown]"), delivered);
        assertFalse(delivered.contains("x".repeat(51)), delivered);
    }

    @Test
    void aFailedDelegate_isDeliveredWithItsReason() {
        BackgroundTasks tasks = tasks(4);
        Controlled failing = started(tasks, "t-1", "risky");
        failing.response.complete(AgentResponse.failure("t-1", AgentId.of("worker"), "the model refused",
                Duration.ZERO));

        String delivered = tasks.deliverFinished().get(0);

        assertTrue(delivered.contains("failed — the model refused"), delivered);
    }

    @Test
    void aDelegateThatThrew_isFailed_notLost() {
        BackgroundTasks tasks = tasks(4);
        started(tasks, "t-1", "broken").response.completeExceptionally(new IllegalStateException("boom"));

        assertTrue(tasks.deliverFinished().get(0).contains("failed — boom"));
    }

    @Test
    void theLimit_countsRunningTasks_refusesTheNext_andFreesWhenOneFinishes() {
        BackgroundTasks tasks = tasks(1);
        Controlled first = started(tasks, "t-1", "first");

        String refusal = tasks.reserve();

        assertNotNull(refusal);
        assertTrue(refusal.contains("limit 1") && refusal.contains("t-1"), refusal);
        first.succeedWith("done");
        assertNull(tasks.reserve(), "a finished task no longer takes a slot, even before it is delivered");
    }

    @Test
    void aReservationGivenBack_freesItsSlot() {
        BackgroundTasks tasks = tasks(1);
        assertNull(tasks.reserve());
        assertNotNull(tasks.reserve(), "the slot is taken while the start is in progress");

        tasks.unreserve();

        assertNull(tasks.reserve());
    }

    @Test
    void cancel_stopsARunningTask_andHandsItOverAsCancelled() {
        BackgroundTasks tasks = tasks(4);
        Controlled running = started(tasks, "t-1", "slow");

        String answer = tasks.cancel("t-1");

        assertTrue(answer.contains("cancelled"), answer);
        assertEquals(1, running.cancels.get());
        assertFalse(tasks.hasTasks());
        assertTrue(tasks.cancel("t-1").contains("unknown task t-1"), "a handed-over task is unknown afterwards");
    }

    @Test
    void cancel_ofAFinishedTask_returnsItsResult_insteadOfStoppingIt() {
        BackgroundTasks tasks = tasks(4);
        Controlled done = started(tasks, "t-1", "quick");
        done.succeedWith("already here");

        String answer = tasks.cancel("t-1");

        assertTrue(answer.contains("completed") && answer.contains("already here"), answer);
        assertEquals(0, done.cancels.get());
    }

    @Test
    void aCancelledTask_staysCancelled_evenIfTheDelegateFinishesAnyway() {
        BackgroundTasks tasks = tasks(4);
        Controlled running = started(tasks, "t-1", "slow");
        tasks.cancel("t-1");

        running.succeedWith("too late");

        assertFalse(tasks.hasTasks());
    }

    @Test
    void cancelAll_stopsWhatRuns_forgetsEverything_andNamesWhatItStopped() {
        BackgroundTasks tasks = tasks(4);
        Controlled running = started(tasks, "t-1", "slow");
        started(tasks, "t-2", "quick").succeedWith("uncollected");

        List<String> stopped = tasks.cancelAll();

        assertEquals(List.of("t-1 \"slow\" (worker)"), stopped);
        assertEquals(1, running.cancels.get());
        assertFalse(tasks.hasTasks());
    }

    @Test
    void await_withATimeoutOfZero_listsTheTasksStillWorking() {
        BackgroundTasks tasks = tasks(4);
        started(tasks, "t-1", "slow");
        tasks.beginTurn();

        String answer = tasks.await(List.of(), Duration.ZERO);

        assertTrue(answer.contains("t-1 \"slow\" (worker): still working"), answer);
        assertTrue(tasks.hasTasks(), "listing hands nothing over");
    }

    @Test
    void await_returnsTheResultOfAFinishedTask_andHandsItOver() {
        BackgroundTasks tasks = tasks(4);
        started(tasks, "t-1", "quick").succeedWith("here you go");
        tasks.beginTurn();

        String answer = tasks.await(List.of("t-1"), Duration.ofSeconds(1));

        assertTrue(answer.contains("completed") && answer.contains("here you go"), answer);
        assertFalse(tasks.hasTasks());
    }

    @Test
    void await_waitsForARunningTask_untilItFinishes() throws Exception {
        BackgroundTasks tasks = tasks(4);
        Controlled running = started(tasks, "t-1", "slow");

        Thread finisher = Thread.ofVirtual().start(() -> {
            sleep(150);
            running.succeedWith("late but here");
        });
        String answer = tasks.await(List.of("t-1"), Duration.ofSeconds(5));
        finisher.join();

        assertTrue(answer.contains("late but here"), answer);
    }

    @Test
    void await_isBoundedByItsTimeout_andReportsWhatIsStillWorking() {
        BackgroundTasks tasks = tasks(4);
        started(tasks, "t-1", "never");

        Instant before = Instant.now();
        String answer = tasks.await(List.of("t-1"), Duration.ofMillis(100));

        assertTrue(answer.contains("still working"), answer);
        assertTrue(Duration.between(before, Instant.now()).toMillis() < 2_000);
    }

    @Test
    void await_withoutIds_meansTheTasksOpenAtTheStartOfTheTurn_notOnesStartedSince() {
        BackgroundTasks tasks = tasks(4);
        started(tasks, "t-1", "before");
        tasks.beginTurn();
        started(tasks, "t-2", "during");

        String answer = tasks.await(List.of(), Duration.ZERO);

        assertTrue(answer.contains("t-1"), answer);
        assertFalse(answer.contains("t-2"), "a task started in this turn is not one the model could have known to wait for");
    }

    @Test
    void await_ofAnUnknownId_saysSo() {
        assertTrue(tasks(4).await(List.of("nope"), Duration.ZERO).contains("unknown task nope"));
    }

    @Test
    void awaitAllAndDeliver_waitsForRunningTasks_andDeliversThem() {
        BackgroundTasks tasks = tasks(4);
        Controlled running = started(tasks, "t-1", "slow");
        Thread.ofVirtual().start(() -> {
            sleep(100);
            running.succeedWith("worth the wait");
        });

        List<String> delivered = tasks.awaitAllAndDeliver(Instant.now().plusSeconds(5));

        assertEquals(1, delivered.size());
        assertTrue(delivered.get(0).contains("worth the wait"));
    }

    @Test
    void awaitAllAndDeliver_givesUpAtTheDeadline_withoutTheUnfinished() {
        BackgroundTasks tasks = tasks(4);
        started(tasks, "t-1", "never");

        List<String> delivered = tasks.awaitAllAndDeliver(Instant.now().plusMillis(100));

        assertTrue(delivered.isEmpty());
        assertTrue(tasks.hasTasks());
    }

    @Test
    void theRegistry_travelsInTheTasksContext_andIsDroppedForADelegate() {
        BackgroundTasks tasks = tasks(4);
        AgentTask attached = tasks.attachTo(AgentTask.of("go"));

        assertTrue(BackgroundTasks.from(attached.runContext()).isPresent());
        assertTrue(BackgroundTasks.from(BackgroundTasks.without(attached.runContext())).isEmpty());
        assertTrue(BackgroundTasks.from(RunContext.empty()).isEmpty());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
