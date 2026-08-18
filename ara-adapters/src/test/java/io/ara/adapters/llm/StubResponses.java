package io.ara.adapters.llm;

/**
 * Minimal provider responses each vendor's langchain4j parser accepts — just enough for a call
 * to complete, so tests can assert on what was sent or on how the reply is mapped.
 */
public final class StubResponses {

    private StubResponses() {}

    public static final String OPENAI = """
            {"id":"c","object":"chat.completion","created":1,"model":"gpt-4o",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},
                         "finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    public static final String ANTHROPIC = """
            {"id":"m","type":"message","role":"assistant","model":"claude-sonnet-4-6",
             "content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":1,"output_tokens":1}}""";

    public static final String OLLAMA = """
            {"model":"llama3.2","created_at":"2024-01-01T00:00:00Z",
             "message":{"role":"assistant","content":"hi"},"done":true,
             "done_reason":"stop","prompt_eval_count":1,"eval_count":1}""";

    /**
     * An Ollama reply that carries a tool call and no text — the shape that makes
     * {@code AiMessage.text()} null, which the completion mapping has to absorb.
     */
    public static final String OLLAMA_TOOL_CALL_ONLY = """
            {"model":"llama3.2","created_at":"2024-01-01T00:00:00Z",
             "message":{"role":"assistant","content":"",
                        "tool_calls":[{"function":{"name":"get_weather",
                                                   "arguments":{"city":"Rome"}}}]},
             "done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":1}""";
}
