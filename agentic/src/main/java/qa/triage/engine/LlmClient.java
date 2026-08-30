package qa.triage.engine;

/**
 * The only thing Tier 4 needs from an LLM: send a system + user prompt, get raw text back.
 * {@link HttpLlmClient} is the real (OpenAI-compatible chat-completions) implementation;
 * tests use a fake that returns fixed JSON, so no test in this repo requires a live LLM call.
 */
public interface LlmClient {

    /** Returns the model's raw response text (expected, but not guaranteed, to be strict JSON). */
    String completeJson(String systemPrompt, String userPrompt);
}
