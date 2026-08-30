package qa.triage.engine;

import org.junit.jupiter.api.Test;
import qa.triage.FailureBundle;
import qa.triage.TriageCategory;
import qa.triage.TriageResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenRetrieverTest {

    private final GoldenRetriever retriever = new GoldenRetriever();

    @Test
    void retrievesTheRelevantGoldenForANearDuplicateLog() {
        FailureBundle nearDuplicate = new FailureBundle(
                "qa.FrontendHttpSmokeTest.homepageReturns200",
                "java.net.ConnectException: Connection refused (Connection refused): http://localhost:8080/ -- "
                        + "make sure the demo stack is running",
                "", "https://example.com/actions/runs/999", 1, List.of());

        List<GoldenRetriever.ScoredExample> topK = retriever.retrieveTopK(nearDuplicate, 3);

        assertEquals("GF-03", topK.get(0).example().id());
        assertTrue(topK.get(0).similarity() >= 0.3);
    }

    @Test
    void classifyBumpsConfidenceOnHighSimilarityAgreement() {
        FailureBundle nearDuplicate = new FailureBundle(
                "qa.FrontendHttpSmokeTest.homepageReturns200",
                "java.net.ConnectException: Connection refused (Connection refused): http://localhost:8080/ -- "
                        + "make sure the demo stack is running",
                "", "https://example.com/actions/runs/999", 1, List.of());

        TriageResult result = retriever.classify(nearDuplicate);

        assertEquals(TriageCategory.ENVIRONMENT_ISSUE, result.category());
        assertTrue(result.evidence().stream().anyMatch(line -> line.contains("GF-03")));
    }

    @Test
    void unrelatedLogGetsLowConfidence() {
        FailureBundle unrelated = new FailureBundle(
                "qa.SomeTest.someMethod", "zzz qqq totally unrelated gibberish xyzzy plugh",
                "", "https://example.com/1", 1, List.of());

        TriageResult result = retriever.classify(unrelated);

        assertTrue(result.confidence() < 0.85);
        assertTrue(result.escalateToHuman());
    }
}
