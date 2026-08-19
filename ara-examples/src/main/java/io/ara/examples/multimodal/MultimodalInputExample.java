package io.ara.examples.multimodal;

import io.ara.adapters.llm.mistral.MistralLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;
import io.ara.runtime.AraRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sends a document to a model, and an image to a different one, with the <em>same</em> code
 * above the adapter.
 *
 * <p>That is the whole demonstration. {@link #analyse} is written once and knows nothing about
 * providers, MIME types or byte encodings: it puts bytes in the {@link MediaStore}, attaches
 * the returned {@link MediaRef} to an {@link AgentTask}, and runs the agent. Which provider
 * ends up receiving a base64 PDF document block versus an inline image part is settled inside
 * the adapter, and never leaks upwards.
 *
 * <p>Two behaviours are worth watching while running this:
 * <ul>
 *   <li><b>A task can be just a document.</b> The PDF run passes {@code ""} as the input.
 *       "Read this" with no other words is the most common shape of a task that attaches a
 *       file, and it is legal precisely because media is present.</li>
 *   <li><b>An unsupported type fails loudly.</b> Send the PDF to Ollama (swap the clients) and
 *       the task fails naming the type and the provider, instead of answering fluently about a
 *       document the model never received. Ollama's chat API has no document part, so there is
 *       nothing honest for a PDF to become there.</li>
 * </ul>
 *
 * <p>Both runs need something listening: a {@code MISTRAL_API_KEY} for the first, a local
 * Ollama with a vision model (e.g. {@code llava}) for the second. Pass the file paths as
 * arguments, or drop a {@code contract.pdf} and a {@code scan.png} next to where you run it.
 */
public class MultimodalInputExample {

    public static void main(String[] args) throws Exception {
        Path pdf   = Path.of(args.length > 0 ? args[0] : "contract.pdf");
        Path image = Path.of(args.length > 1 ? args[1] : "scan.png");

        // Where the bytes live. Runtime-wide, exactly like SessionStore: a per-agent store
        // would let two agents in one delegation chain disagree about where a document is.
        MediaStore mediaStore = MediaStore.inMemory();

        if (Files.exists(pdf)) {
            LlmClient mistral = MistralLlmClient.builder()
                    .apiKey(requireEnv("MISTRAL_API_KEY"))
                    .model(MistralLlmClient.Models.MISTRAL_MEDIUM_LATEST)
                    .build();

            // No words at all: the document *is* the request.
            analyse("pdf-analyst", mistral, mediaStore, pdf, "application/pdf", "");
        } else {
            System.out.println("Skipping the PDF run: " + pdf.toAbsolutePath() + " not found");
        }

        if (Files.exists(image)) {
            LlmClient ollama = OllamaLlmClient.builder()
                    .modelName("llava")          // any local vision-capable model
                    .build();

            analyse("image-analyst", ollama, mediaStore, image, "image/png",
                    "What is written on this document?");
        } else {
            System.out.println("Skipping the image run: " + image.toAbsolutePath() + " not found");
        }
    }

    /**
     * The provider-agnostic half: store the bytes, attach the reference, run the agent.
     *
     * <p>Nothing here branches on the media type or on which client was passed in. Note that
     * {@code file} is read once, into the store, and the {@code MediaRef} carries only its
     * digest, name and size from then on — so the bytes never enter the task, the session, the
     * request log or the working-memory token estimate.
     */
    private static void analyse(String agentType, LlmClient llm, MediaStore mediaStore,
                                Path file, String mimeType, String question) throws Exception {

        MediaRef attachment = mediaStore.put(
                file.getFileName().toString(), mimeType, Files.readAllBytes(file));

        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", llm)
                .mediaStore(mediaStore)
                .build()) {

            runtime.start();

            AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                    .agentType(agentType)
                    .systemPrompt("You analyse the documents and images you are given. "
                            + "Answer only from what you can actually see in them.")
                    .primaryLlm(LlmProfile.of("model"))
                    .plannerStrategy("react")
                    .maxIterations(3)
                    .build());

            System.out.printf("%n=== %s → %s ===%n", attachment.name(), llm.providerId());
            System.out.printf("Attachment : %s (%s, %d bytes, id=%s…)%n",
                    attachment.name(), attachment.mimeType(), attachment.sizeBytes(),
                    attachment.mediaId().substring(0, 12));
            System.out.printf("Question   : %s%n",
                    question.isBlank() ? "(none — the document is the request)" : question);

            AgentResponse response = agent.execute(AgentTask.of(question, List.of(attachment)));

            System.out.printf("Success    : %s%n", response.isSuccess());
            if (response.isSuccess()) {
                System.out.printf("Answer     : %s%n", response.content());
            } else {
                // What an unsupported media type looks like: named, and never a fluent answer
                // about a document the model was not sent.
                System.out.printf("Failed     : %s%n", response.failureReason());
            }
            System.out.printf("Tokens     : %d%n", response.totalTokens());
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + name);
        }
        return value;
    }
}
