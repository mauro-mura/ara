package io.ara.examples.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ContractViolation;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.agent.ContractAware;
import io.ara.runtime.contract.JsonSchemaValidator;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An agent's <b>contract</b>, input and output — what each side may send, what a refusal looks like, and
 * what happens to an answer that is nearly right. One story, offline, no key: the model is scripted.
 *
 * <p>The agent reads a purchase order and answers with its lines and total, which a procurement system
 * posts to a ledger. Four things the contract does for it, each one a few lines of builder:
 *
 * <ol>
 *   <li><b>A full JSON Schema on each side.</b> {@link JsonSchemaValidator#forOutput(String)} checks types,
 *       nested objects and arrays, {@code enum}, {@code pattern}, bounds and {@code additionalProperties},
 *       and reports <em>every</em> violation with its JSON Path in one rejection — not the first one, not
 *       only the top-level {@code required} list it used to check. The same instance is declared once and
 *       enforced ({@code inputSchema} + input processor, {@code outputSchema} + output processor).</li>
 *   <li><b>A refusal that is data.</b> A rejected task comes back with
 *       {@link AgentResponse#violation()}: the {@link ContractViolation.Phase phase} says whose fault it is
 *       ({@code INPUT} and {@code MEDIA} are the caller's, {@code OUTPUT} is the agent's) and the issues give
 *       each failing path — so a host chooses "fix your request" or "not your problem" without reading
 *       prose.</li>
 *   <li><b>A rule a schema cannot say.</b> "The total is the sum of the lines" is arithmetic, not shape. A
 *       custom {@link OutputProcessor} returns {@link ProcessingResult#reject(String, List)} with its own
 *       {@link ProcessingResult.Issue}s and takes part in everything below like any built-in. The two
 *       halves have different readers: the issues go to the host, the reason goes to the model on a repair —
 *       so the reason must name <em>where</em>, as the schema validator's does.</li>
 *   <li><b>Repair.</b> {@code outputRepairAttempts(n)} sends a rejected answer back to the model with the
 *       validator's own message, up to {@code n} times, before the rejection becomes the task's failure.
 *       Every attempt is a real call: tokens and iterations add up in the response, so a repaired task costs
 *       what it cost, and a task that stays wrong still shows what was spent on it.</li>
 * </ol>
 *
 * <p>And one introspection: a caller that needs to <em>build</em> a valid request reads the contract off the
 * agent ({@link ContractAware}) — what a gateway publishes on an agent's card.
 *
 * <p>The example checks its own claims at the end ({@link Checks}) and fails loudly if one does not hold;
 * this module has no test sources by design, so a demo that only printed would be unfalsifiable.
 */
public final class StructuredContractExample {

    private static final String INPUT_SCHEMA = """
            {"type":"object","required":["orderId","text"],"additionalProperties":false,
             "properties":{
               "orderId":{"type":"string","pattern":"^PO-[0-9]+$"},
               "text":{"type":"string","minLength":10}}}""";

    private static final String OUTPUT_SCHEMA = """
            {"type":"object","required":["lines","total"],"additionalProperties":false,
             "properties":{
               "lines":{"type":"array","minItems":1,"items":{
                 "type":"object","required":["sku","qty","unitPrice"],
                 "properties":{
                   "sku":{"type":"string","minLength":1},
                   "qty":{"type":"integer","minimum":1},
                   "unitPrice":{"type":"number","minimum":0}}}},
               "total":{"type":"number","minimum":0}}}""";

    private static final String GOOD_ORDER =
            "{\"orderId\":\"PO-7731\",\"text\":\"2 x A1 at 10.00, 1 x B2 at 5.50\"}";
    private static final String BAD_ORDER = "{\"orderId\":\"7731\",\"text\":\"short\",\"urgent\":true}";

    // What the scripted model answers, in the order it is asked.
    private static final String WRONG_TOTAL =                       // valid shape, wrong arithmetic: 30.0 != 25.5
            "{\"lines\":[{\"sku\":\"A1\",\"qty\":2,\"unitPrice\":10.0},{\"sku\":\"B2\",\"qty\":1,\"unitPrice\":5.5}],\"total\":30.0}";
    private static final String CORRECT =
            "{\"lines\":[{\"sku\":\"A1\",\"qty\":2,\"unitPrice\":10.0},{\"sku\":\"B2\",\"qty\":1,\"unitPrice\":5.5}],\"total\":25.5}";
    private static final String WRONG_SHAPE =                       // qty is a word and the total is missing
            "{\"lines\":[{\"sku\":\"A1\",\"qty\":\"two\",\"unitPrice\":10.0}]}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Checks checks = new Checks();
        System.out.println("=== ARA structured contract — input, output, refusal as data, repair ===\n");

        // ── 0. A schema that is not a schema is refused when the contract is built ───────────────
        System.out.println("── 0. A broken schema fails at construction, not on the first task ──");
        try {
            JsonSchemaValidator.forOutput("{\"type\":\"object\",\"properties\":");
            checks.that(false, "a truncated schema must be refused");
        } catch (IllegalArgumentException e) {
            System.out.println("  refused: " + e.getMessage());
            checks.that(true, "a truncated schema is refused at forOutput(...)");
        }
        System.out.println();

        // ── 1. The contract: both sides, one repair attempt, one custom rule ─────────────────────
        JsonSchemaValidator in = JsonSchemaValidator.forOutput(INPUT_SCHEMA);
        JsonSchemaValidator out = JsonSchemaValidator.forOutput(OUTPUT_SCHEMA);
        AgentContract contract = AgentContract.builder()
                .inputSchema(in).addInputProcessor(in)             // declared once, enforced by the same instance
                .outputSchema(out)                                  // also told to the model, in its system prompt
                .addOutputProcessor(out)                            // shape first ...
                .addOutputProcessor(new TotalIsTheSumOfTheLines())  // ... then the rule a schema cannot say
                .outputRepairAttempts(1)                            // a rejected answer goes back once
                .build();

        // The model, scripted — wrapped so the example can show what it is actually sent on a repair.
        RecordingLlm llm = new RecordingLlm(ScriptedLlmClient.script()
                .thenFinalAnswer(WRONG_TOTAL).thenFinalAnswer(CORRECT)        // order 2: answer, repair
                .thenFinalAnswer(WRONG_SHAPE).thenFinalAnswer(WRONG_SHAPE)    // order 3: answer, repair
                .build());
        AraRuntime runtime = AraRuntime.builder().llmClient(llm).build();
        runtime.start();
        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("po-extractor"))
                .agentType("po-extractor")
                .systemPrompt("Read the purchase order. Answer with one JSON object: its lines and the total.")
                .primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("react")
                .build(), contract);

        try {
            // ── 2. A refusal that is data ─────────────────────────────────────────────────────────
            System.out.println("── 1. A bad request is refused before the model, with the paths as data ──");
            AgentResponse refused = agent.execute(AgentTask.of(BAD_ORDER));
            describe(refused);
            ContractViolation inputViolation = refused.violationOpt().orElseThrow();
            checks.that(!refused.isSuccess(), "a bad request is refused");
            checks.that(inputViolation.phase() == ContractViolation.Phase.INPUT, "...in the INPUT phase");
            checks.that(inputViolation.phase().callerFault(), "...which is the caller's fault");
            checks.that(inputViolation.issues().size() == 3,
                    "all three problems are reported at once (pattern, minLength, additionalProperties), got "
                            + inputViolation.issues());
            checks.that(refused.inputTokens() + refused.outputTokens() == 0, "the model was never called");
            checks.that(llm.calls() == 0, "the scripted model saw no call");
            System.out.println("  host's reading: " + route(refused) + "\n");

            // ── 3. A rule the schema cannot say, and a repair ─────────────────────────────────────
            System.out.println("── 2. Right shape, wrong arithmetic — rejected by the custom rule, repaired ──");
            AgentResponse repaired = agent.execute(AgentTask.of(GOOD_ORDER));
            describe(repaired);
            checks.that(repaired.isSuccess(), "the repaired answer is accepted");
            checks.that(MAPPER.readTree(repaired.content()).get("total").decimalValue()
                    .compareTo(new BigDecimal("25.5")) == 0, "...and carries the correct total");
            checks.that(llm.calls() == 2, "it took two model calls: the answer and its repair");
            checks.that(repaired.iterationsUsed() == 2 * llm.iterationsPerCall(),
                    "iterations add up across attempts");
            String repairPrompt = llm.lastUserMessage();
            System.out.println("  what the model was sent on the second call:");
            repairPrompt.lines().skip(1)                       // line 1 is the original request, unchanged
                    .filter(l -> !l.isBlank() && !l.equals("---"))
                    .forEach(l -> System.out.println("      " + abbreviate(l)));
            checks.that(repairPrompt.startsWith(GOOD_ORDER), "the repair keeps the original request first");
            checks.that(repairPrompt.contains("Rejection:") && repairPrompt.contains("$.total"),
                    "...then the rejection with its path");
            checks.that(repairPrompt.contains(WRONG_TOTAL), "...and the answer being repaired");
            System.out.println();

            // ── 4. An answer that stays wrong ─────────────────────────────────────────────────────
            System.out.println("── 3. An answer that stays wrong is the agent's failure, with the cost kept ──");
            int callsBefore = llm.calls();
            AgentResponse failed = agent.execute(AgentTask.of(GOOD_ORDER));
            describe(failed);
            ContractViolation outputViolation = failed.violationOpt().orElseThrow();
            checks.that(!failed.isSuccess(), "an answer that stays wrong fails");
            checks.that(outputViolation.phase() == ContractViolation.Phase.OUTPUT, "...in the OUTPUT phase");
            checks.that(!outputViolation.phase().callerFault(), "...which is not the caller's fault");
            checks.that(llm.calls() - callsBefore == 2, "the one allowed repair was spent, then it stopped");
            checks.that(failed.inputTokens() + failed.outputTokens() > 0, "both attempts are on the bill");
            checks.that(outputViolation.issues().stream().anyMatch(i -> i.path().equals("$.lines[0].qty")),
                    "the word where a number belongs is located: $.lines[0].qty");
            System.out.println("  host's reading: " + route(failed) + "\n");

            // ── 5. The contract, read off the agent ───────────────────────────────────────────────
            System.out.println("── 4. A caller reads what to send from the agent itself ──");
            AgentContract published = ((ContractAware) agent).contract();
            JsonNode inputSchema = MAPPER.readTree(published.inputSchema().jsonSchema());
            JsonNode outputSchema = MAPPER.readTree(published.outputSchema().jsonSchema());
            System.out.println("  input  must have : " + inputSchema.get("required"));
            System.out.println("  output will have : " + outputSchema.get("required"));
            System.out.println("  repair attempts  : " + published.outputRepairAttempts());
            checks.that(inputSchema.get("required").get(0).asText().equals("orderId"),
                    "the input schema is readable off the agent");
            checks.that(published.outputRepairAttempts() == 1, "...with its repair count");
        } finally {
            runtime.stop();
        }

        System.out.println("\n" + checks.passed() + " checks passed.");
    }

    // ── a rule a schema cannot say ─────────────────────────────────────────────────────────────

    /**
     * {@code total == Σ qty × unitPrice}. Shape is the schema's job; arithmetic across fields is not. The
     * rejection carries a structured {@link ProcessingResult.Issue} at the path that is wrong, for the host
     * ({@link AgentResponse#violation()}), and a reason that names the same path, for the model: a repair
     * sends the model the reason and nothing else, so a reason that did not say where would leave it guessing.
     */
    static final class TotalIsTheSumOfTheLines implements OutputProcessor {
        @Override
        public ProcessingResult process(String answer) {
            JsonNode root;
            try {
                root = MAPPER.readTree(answer);
            } catch (Exception e) {
                return ProcessingResult.pass(answer);   // not JSON: the schema validator ahead of this already said so
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (JsonNode line : root.path("lines")) {
                sum = sum.add(line.path("unitPrice").decimalValue().multiply(BigDecimal.valueOf(line.path("qty").asLong())));
            }
            BigDecimal total = root.path("total").decimalValue();
            if (total.compareTo(sum) == 0) {
                return ProcessingResult.pass(answer);
            }
            String message = "total " + total.toPlainString() + " is not the sum of the lines (" + sum.toPlainString() + ")";
            return ProcessingResult.reject("Rule violated at $.total: " + message,
                    List.of(new ProcessingResult.Issue("$.total", message)));
        }
    }

    // ── presentation ───────────────────────────────────────────────────────────────────────────

    private static void describe(AgentResponse r) {
        if (r.isSuccess()) {
            System.out.println("  success: " + abbreviate(r.content()));
        } else {
            System.out.println("  FAILED : " + abbreviate(r.failureReason()));
        }
        r.violationOpt().ifPresent(v -> {
            System.out.println("  violation phase = " + v.phase());
            v.issues().forEach(i -> System.out.println("      " + i.path() + " : " + i.message()));
        });
        System.out.println("  usage  : iterations=" + r.iterationsUsed() + ", tokens in/out=" + r.inputTokens()
                + "/" + r.outputTokens());
    }

    /** What a host does with a refusal — the whole point of {@code Phase.callerFault()}. */
    private static String route(AgentResponse r) {
        return r.violationOpt()
                .map(v -> v.phase().callerFault()
                        ? "tell the caller to fix the request (" + v.issues().size() + " issue(s) to show)"
                        : "not the caller's doing: retry later or escalate, do not blame the request")
                .orElse("not a contract refusal");
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.replace('\n', ' ');
        return flat.length() <= 118 ? flat : flat.substring(0, 115) + "...";
    }

    // ── test double ────────────────────────────────────────────────────────────────────────────

    /** Delegates to a scripted client and remembers what the model was sent. */
    private static final class RecordingLlm implements LlmClient {
        private final LlmClient delegate;
        private final List<List<LlmMessage>> sent = new ArrayList<>();

        RecordingLlm(LlmClient delegate) { this.delegate = Objects.requireNonNull(delegate); }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
            sent.add(List.copyOf(messages));
            return delegate.complete(messages, context);
        }

        @Override public String providerId() { return delegate.providerId(); }

        int calls() { return sent.size(); }

        /** The user message of the most recent call — the task input the agent was handed. */
        String lastUserMessage() {
            List<LlmMessage> last = sent.get(sent.size() - 1);
            for (int i = last.size() - 1; i >= 0; i--) {
                if ("user".equals(last.get(i).role())) {
                    return last.get(i).content();
                }
            }
            throw new IllegalStateException("the last call carried no user message");
        }

        /** One scripted answer is one ReAct iteration. */
        int iterationsPerCall() { return 1; }
    }

    /** The example's own assertions: a demo whose claims nothing checks is only a story. */
    private static final class Checks {
        private int passed;

        void that(boolean condition, String claim) {
            if (!condition) {
                throw new IllegalStateException("example claim does not hold: " + claim);
            }
            passed++;
        }

        int passed() { return passed; }
    }

    private StructuredContractExample() { }
}
