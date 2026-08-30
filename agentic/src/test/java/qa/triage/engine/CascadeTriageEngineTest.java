package qa.triage.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qa.triage.FailureBundle;
import qa.triage.TriageCategory;
import qa.triage.TriageResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CascadeTriageEngineTest {

    private final CascadeTriageEngine engine = new CascadeTriageEngine();

    @Test
    void confidentTier1MatchShortCircuits() {
        FailureBundle bundle = new FailureBundle(
                "qa.SomeTest.someMethod", "java.net.UnknownHostException: currency", "",
                "https://example.com/1", 1, List.of());

        TriageResult result = engine.triage(bundle);

        assertEquals(TriageCategory.ENVIRONMENT_ISSUE, result.category());
        assertEquals(TriageResult.Tier.RULES, result.decidedAtTier());
    }

    @Test
    void lowConfidenceFallsThroughGuardrailsWithNoLlmConfigured(@TempDir Path tempDir) {
        CascadeTriageEngine cascade = new CascadeTriageEngine(
                new Tier1RuleEngine(), new SignatureCache(tempDir.resolve("cache.json")), new GoldenRetriever(),
                new Tier4LlmEngine(null));
        FailureBundle bundle = new FailureBundle(
                "qa.SomeTest.someMethod", "some completely unrelated stack trace", "",
                "https://example.com/1", 1, List.of());

        TriageResult result = cascade.triage(bundle);

        assertEquals(0.0, result.confidence());
        assertTrue(result.escalateToHuman());
        assertEquals(TriageResult.RecommendedAction.ESCALATE, result.recommendedAction());
    }

    @Test
    void lowConfidenceDeterministicResultIsBlendedWithLlmVerdict(@TempDir Path tempDir) {
        String llmJson = """
                {"category":"product_bug","confidence":0.9,"rootCause":"npe in checkout",
                 "evidence":["NullPointerException at Checkout.java:42"],"recommendedAction":"FILE_BUG"}""";
        CascadeTriageEngine cascade = new CascadeTriageEngine(
                new Tier1RuleEngine(), new SignatureCache(tempDir.resolve("cache.json")), new GoldenRetriever(),
                new Tier4LlmEngine(new FakeLlmClient(llmJson)));
        FailureBundle bundle = new FailureBundle(
                "qa.SomeTest.someMethod", "some completely unrelated stack trace", "",
                "https://example.com/1", 1, List.of());

        TriageResult result = cascade.triage(bundle);

        // deterministic best (0.0) * 0.6 + llm (0.9) * 0.4 = 0.36
        assertEquals(0.36, result.confidence(), 1e-9);
        assertEquals(TriageCategory.PRODUCT_BUG, result.category());
        assertEquals(TriageResult.Tier.LLM, result.decidedAtTier());
        assertTrue(result.escalateToHuman());
        assertEquals(TriageResult.RecommendedAction.ESCALATE, result.recommendedAction());
    }

    @Test
    void signatureCacheHitShortCircuitsBeforeGoldenAndLlm(@TempDir Path tempDir) {
        CascadeTriageEngine cascade = new CascadeTriageEngine(
                new Tier1RuleEngine(), new SignatureCache(tempDir.resolve("cache.json")), new GoldenRetriever(),
                new Tier4LlmEngine(null));
        FailureBundle bundle = new FailureBundle(
                "qa.SomeTest.someMethod", "zzz qqq totally unrelated gibberish xyzzy plugh", "",
                "https://example.com/1", 1, List.of());
        TriageResult confirmed = new TriageResult(
                TriageCategory.PRODUCT_BUG, TriageResult.Tier.LLM, 0.7, TriageResult.OwnerSquad.COMMERCE,
                "confirmed by a human", List.of(), "", true, TriageResult.RecommendedAction.FILE_BUG);
        cascade.signatureCache().put(bundle, confirmed);

        TriageResult result = cascade.triage(bundle);

        assertEquals(TriageResult.Tier.SIGNATURE_CACHE, result.decidedAtTier());
        assertEquals(TriageCategory.PRODUCT_BUG, result.category());
    }

    @Test
    void goldenRetrievalShortCircuitsWhenTopKAgreeOnCategory(@TempDir Path tempDir) throws IOException {
        Path goldenFile = tempDir.resolve("golden.jsonl");
        Files.writeString(goldenFile, String.join("\n",
                goldenLine("G-1", "widget frobnicator timeout retry backoff"),
                goldenLine("G-2", "widget frobnicator timeout retry backoff delay"),
                goldenLine("G-3", "widget frobnicator timeout retry backoff jitter")) + "\n");

        CascadeTriageEngine cascade = new CascadeTriageEngine(
                new Tier1RuleEngine(), new SignatureCache(tempDir.resolve("cache.json")),
                new GoldenRetriever(goldenFile), new Tier4LlmEngine(null));
        FailureBundle bundle = new FailureBundle(
                "qa.WidgetTest.frobnicate", "widget frobnicator timeout retry backoff observed",
                "", "https://example.com/1", 1, List.of());

        TriageResult result = cascade.triage(bundle);

        assertEquals(TriageResult.Tier.GOLDEN_RAG, result.decidedAtTier());
        assertEquals(TriageCategory.FLAKY_TEST, result.category());
    }

    private static String goldenLine(String id, String logTail) {
        return "{\"id\":\"" + id + "\",\"failure\":{\"testName\":\"qa.WidgetTest.frobnicate\","
                + "\"logTail\":\"" + logTail + "\",\"diff\":\"\",\"buildUrl\":\"https://example.com/1\","
                + "\"attemptNumber\":1,\"last5Runs\":[]},\"expected_category\":\"flaky_test\",\"notes\":\"test data\"}";
    }
}
