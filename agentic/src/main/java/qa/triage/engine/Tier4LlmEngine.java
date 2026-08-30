package qa.triage.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import qa.triage.FailureBundle;
import qa.triage.LlmVerdict;
import qa.triage.TriageResult;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tier 4 of the cascade (agentic/AGENT-DESIGN.md §1) — optional, offline by default. Only
 * ever consulted after Tiers 1-3 all fell through (see CascadeTriageEngine). Never invents
 * remediation: the system prompt explicitly forbids claiming a fix, quarantine, or merge was
 * applied — this tier only ever proposes a category and an action for a human to act on.
 */
public final class Tier4LlmEngine {

    private static final Pattern CODE_FENCE = Pattern.compile("^```(?:json)?|```$", Pattern.MULTILINE);

    private static final String SYSTEM_PROMPT = """
            You are a CI test-failure triage assistant. You classify one failing test based on \
            the evidence given to you. You have no ability to modify code, run commands, quarantine \
            tests, file tickets, or merge/block anything — you only ever RECOMMEND. Never claim that \
            a fix, quarantine, retry, or merge has already been applied.

            Respond with ONLY a single JSON object, no markdown fences, no prose before or after, \
            matching exactly this shape:
            {
              "category": one of "flaky_test" | "product_bug" | "environment_issue" | "test_gap",
              "confidence": number between 0.0 and 1.0,
              "rootCause": one sentence explaining why,
              "evidence": array of short strings quoting the exact lines that justify the category,
              "recommendedAction": one of "RETRY" | "FILE_BUG" | "ESCALATE" | "QUARANTINE" | "IGNORE"
            }
            Do not include any other fields.""";

    private final LlmClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Offline by default: only builds a real HTTP client when LLM_API_KEY is set. */
    public Tier4LlmEngine() {
        this(System.getenv("LLM_API_KEY") != null ? new HttpLlmClient() : null);
    }

    Tier4LlmEngine(LlmClient client) {
        this.client = client;
    }

    public boolean isAvailable() {
        return client != null;
    }

    /** Empty if no LLM is configured, or if the model's response couldn't be parsed. */
    public Optional<LlmVerdict> classify(FailureBundle bundle, TriageResult deterministicBest) {
        if (client == null) {
            return Optional.empty();
        }
        String raw = client.completeJson(SYSTEM_PROMPT, userPrompt(bundle, deterministicBest));
        try {
            return Optional.of(mapper.readValue(stripCodeFences(raw), LlmVerdict.class));
        } catch (JsonProcessingException e) {
            System.err.println("Tier 4: could not parse LLM response as a TriageResult verdict, ignoring it: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static String stripCodeFences(String raw) {
        return CODE_FENCE.matcher(raw.trim()).replaceAll("").trim();
    }

    private static String userPrompt(FailureBundle bundle, TriageResult deterministicBest) {
        return """
                testName: %s
                attemptNumber: %d
                last5Runs: %s
                logTail:
                %s
                diff:
                %s

                Earlier deterministic tiers' best guess so far (you may agree or override it):
                category=%s confidence=%.2f rootCause=%s
                evidence=%s
                """.formatted(
                bundle.testName(), bundle.attemptNumber(), bundle.last5Runs(),
                bundle.logTail(), bundle.diff(),
                deterministicBest.category().wireValue(), deterministicBest.confidence(),
                deterministicBest.rootCause(), deterministicBest.evidence());
    }
}
