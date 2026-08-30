package qa.triage.engine;

import org.junit.jupiter.api.Test;
import qa.triage.FailureBundle;
import qa.triage.LlmVerdict;
import qa.triage.TriageCategory;
import qa.triage.TriageResult;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Tier4LlmEngineTest {

    private static final FailureBundle BUNDLE = new FailureBundle(
            "qa.SomeTest.someMethod", "some stack trace", "", "https://example.com/1", 1, List.of());
    private static final TriageResult DETERMINISTIC_BEST = new TriageResult(
            TriageCategory.TEST_GAP, TriageResult.Tier.RULES, 0.0, TriageResult.OwnerSquad.UNASSIGNED,
            "no rule matched", List.of(), "", true, TriageResult.RecommendedAction.ESCALATE);

    @Test
    void unavailableWithNoClient() {
        assertFalse(new Tier4LlmEngine(null).isAvailable());
    }

    @Test
    void availableWithAClient() {
        assertTrue(new Tier4LlmEngine(new FakeLlmClient("{}")).isAvailable());
    }

    @Test
    void noClientYieldsEmptyVerdict() {
        Optional<LlmVerdict> verdict = new Tier4LlmEngine(null).classify(BUNDLE, DETERMINISTIC_BEST);

        assertTrue(verdict.isEmpty());
    }

    @Test
    void parsesAWellFormedJsonVerdict() {
        String json = """
                {"category":"flaky_test","confidence":0.8,"rootCause":"retry succeeded",
                 "evidence":["attempt 2 passed"],"recommendedAction":"RETRY"}""";

        Optional<LlmVerdict> verdict = new Tier4LlmEngine(new FakeLlmClient(json)).classify(BUNDLE, DETERMINISTIC_BEST);

        assertTrue(verdict.isPresent());
        assertEquals(TriageCategory.FLAKY_TEST, verdict.get().category());
        assertEquals(0.8, verdict.get().confidence());
        assertEquals(TriageResult.RecommendedAction.RETRY, verdict.get().recommendedAction());
    }

    @Test
    void stripsMarkdownCodeFencesBeforeParsing() {
        String fenced = """
                ```json
                {"category":"product_bug","confidence":0.7,"rootCause":"npe",
                 "evidence":[],"recommendedAction":"FILE_BUG"}
                ```""";

        Optional<LlmVerdict> verdict = new Tier4LlmEngine(new FakeLlmClient(fenced)).classify(BUNDLE, DETERMINISTIC_BEST);

        assertTrue(verdict.isPresent());
        assertEquals(TriageCategory.PRODUCT_BUG, verdict.get().category());
    }

    @Test
    void malformedJsonYieldsEmptyVerdict() {
        Optional<LlmVerdict> verdict = new Tier4LlmEngine(new FakeLlmClient("not json at all"))
                .classify(BUNDLE, DETERMINISTIC_BEST);

        assertTrue(verdict.isEmpty());
    }
}
