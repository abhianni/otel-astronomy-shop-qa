package qa.triage.engine;

import org.junit.jupiter.api.Test;
import qa.triage.LlmVerdict;
import qa.triage.TriageCategory;
import qa.triage.TriageResult;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardrailsTest {

    private static TriageResult deterministic(double confidence, TriageResult.RecommendedAction action) {
        return new TriageResult(TriageCategory.FLAKY_TEST, TriageResult.Tier.GOLDEN_RAG, confidence,
                TriageResult.OwnerSquad.UNASSIGNED, "some root cause", List.of(), "", confidence < 0.85, action);
    }

    @Test
    void noLlmVerdictAutoLabelsAtOrAboveAutoLabelThreshold() {
        TriageResult best = deterministic(0.9, TriageResult.RecommendedAction.RETRY);

        TriageResult result = Guardrails.decide(best, Optional.empty());

        assertFalse(result.escalateToHuman());
        assertEquals(TriageResult.RecommendedAction.RETRY, result.recommendedAction());
    }

    @Test
    void noLlmVerdictSuggestsBetweenThresholdsKeepingProposedAction() {
        TriageResult best = deterministic(0.7, TriageResult.RecommendedAction.FILE_BUG);

        TriageResult result = Guardrails.decide(best, Optional.empty());

        assertTrue(result.escalateToHuman());
        assertEquals(TriageResult.RecommendedAction.FILE_BUG, result.recommendedAction());
    }

    @Test
    void noLlmVerdictForcesEscalateBelowSuggestThreshold() {
        TriageResult best = deterministic(0.2, TriageResult.RecommendedAction.RETRY);

        TriageResult result = Guardrails.decide(best, Optional.empty());

        assertTrue(result.escalateToHuman());
        assertEquals(TriageResult.RecommendedAction.ESCALATE, result.recommendedAction());
    }

    @Test
    void llmVerdictBlendsConfidenceAndCanAutoLabel() {
        TriageResult best = deterministic(0.9, TriageResult.RecommendedAction.RETRY);
        LlmVerdict verdict = new LlmVerdict(TriageCategory.PRODUCT_BUG, 0.9, "npe in checkout",
                List.of("NullPointerException"), TriageResult.RecommendedAction.FILE_BUG);

        TriageResult result = Guardrails.decide(best, Optional.of(verdict));

        assertEquals(0.9, result.confidence(), 1e-9);
        assertEquals(TriageCategory.PRODUCT_BUG, result.category());
        assertEquals(TriageResult.Tier.LLM, result.decidedAtTier());
        assertFalse(result.escalateToHuman());
        assertEquals(TriageResult.RecommendedAction.FILE_BUG, result.recommendedAction());
    }

    @Test
    void llmVerdictBelowSuggestThresholdForcesEscalate() {
        TriageResult best = deterministic(0.0, TriageResult.RecommendedAction.ESCALATE);
        LlmVerdict verdict = new LlmVerdict(TriageCategory.TEST_GAP, 0.1, "unclear",
                List.of(), TriageResult.RecommendedAction.IGNORE);

        TriageResult result = Guardrails.decide(best, Optional.of(verdict));

        assertEquals(0.04, result.confidence(), 1e-9);
        assertTrue(result.escalateToHuman());
        assertEquals(TriageResult.RecommendedAction.ESCALATE, result.recommendedAction());
    }
}
