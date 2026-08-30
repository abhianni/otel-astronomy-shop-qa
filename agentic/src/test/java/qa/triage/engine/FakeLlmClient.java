package qa.triage.engine;

/** Returns a fixed, canned JSON response so tests never need a live LLM call. */
final class FakeLlmClient implements LlmClient {

    private final String fixedResponse;

    FakeLlmClient(String fixedResponse) {
        this.fixedResponse = fixedResponse;
    }

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        return fixedResponse;
    }
}
