package qa.triage.engine;

import org.junit.jupiter.api.Test;
import qa.triage.FailureBundle;
import qa.triage.TriageCategory;
import qa.triage.TriageResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Tier1RuleEngineTest {

    private final Tier1RuleEngine engine = new Tier1RuleEngine();

    @Test
    void connectionRefusedIsEnvironmentIssue() {
        FailureBundle bundle = bundle("java.lang.IllegalStateException: Could not resolve host port for currency:7001");

        TriageResult result = engine.classify(bundle);

        assertEquals(TriageCategory.ENVIRONMENT_ISSUE, result.category());
        assertTrue(result.confidence() >= 0.85);
        assertFalse(result.escalateToHuman());
        assertEquals(TriageResult.RecommendedAction.RETRY, result.recommendedAction());
    }

    @Test
    void oomKilledIsEnvironmentIssue() {
        TriageResult result = engine.classify(bundle("container exited: OOMKilled"));

        assertEquals(TriageCategory.ENVIRONMENT_ISSUE, result.category());
        assertTrue(result.confidence() >= 0.85);
        assertEquals(TriageResult.RecommendedAction.ESCALATE, result.recommendedAction());
    }

    @Test
    void containerAttributedOomIsProductBugButBelowShortCircuitThreshold() {
        TriageResult result = engine.classify(bundle("checkout_1  | java.lang.OutOfMemoryError: Java heap space"));

        assertEquals(TriageCategory.PRODUCT_BUG, result.category());
        assertTrue(result.confidence() < 0.85);
        assertTrue(result.escalateToHuman());
    }

    @Test
    void bareOomIsEnvironmentIssueBelowShortCircuitThreshold() {
        TriageResult result = engine.classify(bundle("java.lang.OutOfMemoryError: Java heap space"));

        assertEquals(TriageCategory.ENVIRONMENT_ISSUE, result.category());
        assertTrue(result.confidence() < 0.85);
        assertTrue(result.escalateToHuman());
    }

    @Test
    void mixedLast5RunsIsFlaky() {
        FailureBundle bundle = new FailureBundle(
                "qa.CartTest.addItem", "some failure", "", "https://example.com/1", 2,
                List.of("FAIL", "PASS", "FAIL", "PASS", "PASS"));

        TriageResult result = engine.classify(bundle);

        assertEquals(TriageCategory.FLAKY_TEST, result.category());
        assertTrue(result.confidence() >= 0.85);
        assertEquals(TriageResult.RecommendedAction.RETRY, result.recommendedAction());
    }

    @Test
    void passedOnRetryPhraseIsFlaky() {
        TriageResult result = engine.classify(bundle("test failed on attempt 1, passed on retry"));

        assertEquals(TriageCategory.FLAKY_TEST, result.category());
    }

    @Test
    void todoMarkerIsTestGap() {
        TriageResult result = engine.classify(bundle("assertion skipped // TODO: assert response body"));

        assertEquals(TriageCategory.TEST_GAP, result.category());
        assertEquals(TriageResult.RecommendedAction.FILE_BUG, result.recommendedAction());
    }

    @Test
    void consistentAssertionFailureIsProductBug() {
        FailureBundle bundle = new FailureBundle(
                "qa.PricingTest.total", "org.opentest4j.AssertionFailedError: expected: <10> but was: <12>",
                "", "https://example.com/1", 1, List.of("FAIL", "FAIL", "FAIL", "FAIL", "FAIL"));

        TriageResult result = engine.classify(bundle);

        assertEquals(TriageCategory.PRODUCT_BUG, result.category());
        assertTrue(result.confidence() >= 0.85);
        assertFalse(result.escalateToHuman());
    }

    @Test
    void noRuleMatchesIsLowConfidenceTestGap() {
        TriageResult result = engine.classify(bundle("some completely unrelated stack trace"));

        assertEquals(TriageCategory.TEST_GAP, result.category());
        assertEquals(0.0, result.confidence());
        assertTrue(result.escalateToHuman());
    }

    private static FailureBundle bundle(String logTail) {
        return new FailureBundle("qa.SomeTest.someMethod", logTail, "", "https://example.com/1", 1, List.of());
    }
}
