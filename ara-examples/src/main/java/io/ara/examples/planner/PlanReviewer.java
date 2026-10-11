package io.ara.examples.planner;

import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.examples.support.Ansi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The person who approves the plan in {@link PlannerExample}: it watches the runtime's
 * {@link ApprovalGate}, and when the strategy asks for the plan to be approved it shows what the
 * person can do and answers the gate with what they type.
 *
 * <p>This is the other half of {@code StrategyConfig.PlanExecute#approvePlan}. The strategy puts the
 * plan to the gate and parks until someone decides; a real application would have an HTTP endpoint or a
 * chat message call {@link ApprovalGate#submit} instead. Here it is a terminal, on its own thread, so the
 * agent and the person do what they would do in a service: the agent waits, the person answers when
 * they can.
 *
 * <p>Three answers, one for each {@link ApprovalDecision}: {@code a} approves the plan as it is,
 * {@code n} rejects it (and the task stops), {@code e} lets the person write the steps again. Any other
 * answer, Enter included, is asked again: approving is a choice, not what happens when a key is hit by chance.
 * With nothing to read (the input is closed: the example runs from a script, a pipe or a CI job) it
 * approves the plan and says so, so the example never hangs waiting for a person who is not there.
 * {@code -Dara.approve=auto} does the same on purpose, without waiting to find out.
 */
final class PlanReviewer implements AutoCloseable {

    private static final String PAD = "      ";

    private final ApprovalGate gate;
    private final boolean ask;
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    private final Thread watcher;

    /** @param ask {@code false} approves every plan without asking */
    PlanReviewer(ApprovalGate gate, boolean ask) {
        this.gate = gate;
        this.ask = ask;
        this.watcher = Thread.ofVirtual().name("plan-reviewer").start(this::watch);
    }

    /** Whether the run asks the person: yes, unless {@code -Dara.approve=auto}. */
    static boolean asksThePerson() {
        return !"auto".equalsIgnoreCase(System.getProperty("ara.approve", ""));
    }

    private void watch() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                for (ApprovalRequest request : gate.getPendingRequests()) {
                    if ("approve_plan".equals(request.action())) {
                        gate.submit(request.requestId(), decide(request));
                    }
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IllegalArgumentException alreadyDecided) {
            // The request expired between the listing and the answer: nothing left to decide.
        }
    }

    private ApprovalDecision decide(ApprovalRequest request) {
        if (!ask) {
            say(Ansi.GREEN, "✔ -Dara.approve=auto: the plan is approved without asking.");
            return new ApprovalDecision.Approved();
        }
        System.out.println();
        say(Ansi.BOLD, "The plan is waiting for you. What do you want to do?");
        System.out.println(PAD + "a      approve it as it is");
        System.out.println(PAD + "e      change it: you write the steps");
        System.out.println(PAD + "n      reject it: the task stops");
        while (true) {
            String answer = prompt("> ");
            if (answer == null) {
                System.out.println();
                say(Ansi.GREY, "(nothing to read: the plan is approved automatically)");
                return new ApprovalDecision.Approved();
            }
            switch (answer.strip().toLowerCase()) {
                case "a", "approve" -> { return new ApprovalDecision.Approved(); }
                case "e", "edit" -> { return edit(); }
                case "n", "no" -> { return reject(); }
                default -> say(Ansi.YELLOW, "Type a, e or n.");
            }
        }
    }

    private ApprovalDecision reject() {
        String reason = prompt("Why? (Enter: \"not approved\") ");
        return new ApprovalDecision.Rejected(reason == null || reason.isBlank() ? "not approved" : reason.strip());
    }

    private ApprovalDecision edit() {
        System.out.println(PAD + "One step per line. An empty line ends the plan.");
        List<String> steps = new ArrayList<>();
        for (String line = prompt((steps.size() + 1) + ". "); line != null && !line.isBlank();
             line = prompt((steps.size() + 1) + ". ")) {
            steps.add(line.strip());
        }
        return steps.isEmpty() ? new ApprovalDecision.Approved() : new ApprovalDecision.Modified(steps);
    }

    /** One line typed by the person, or {@code null} when the input is closed. */
    private String prompt(String text) {
        System.out.print(PAD + text);
        System.out.flush();
        try {
            return in.readLine();
        } catch (IOException closed) {
            return null;
        }
    }

    private static void say(String style, String text) {
        System.out.println(PAD + Ansi.paint(style, text));
    }

    @Override
    public void close() {
        watcher.interrupt();
    }
}
