package qa.triage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TriageEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void categoryWireValuesMatchTheContractExactly() throws Exception {
        assertEquals("\"flaky_test\"", mapper.writeValueAsString(TriageCategory.FLAKY_TEST));
        assertEquals("\"product_bug\"", mapper.writeValueAsString(TriageCategory.PRODUCT_BUG));
        assertEquals("\"environment_issue\"", mapper.writeValueAsString(TriageCategory.ENVIRONMENT_ISSUE));
        assertEquals("\"test_gap\"", mapper.writeValueAsString(TriageCategory.TEST_GAP));

        assertEquals(TriageCategory.PRODUCT_BUG, mapper.readValue("\"product_bug\"", TriageCategory.class));
    }

    @Test
    void confidenceOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TriageResult(
                TriageCategory.TEST_GAP, TriageResult.Tier.NONE, 1.5, TriageResult.OwnerSquad.UNASSIGNED,
                "x", List.of(), "", true, TriageResult.RecommendedAction.ESCALATE));
    }

    @Test
    void failureBundleRoundTripsThroughJson() throws Exception {
        FailureBundle original = new FailureBundle(
                "qa.SomeTest.someMethod", "log tail", "", "https://example.com/1", 1, List.of("PASS", "FAIL"));

        String json = mapper.writeValueAsString(original);
        FailureBundle parsed = mapper.readValue(json, FailureBundle.class);

        assertEquals(original, parsed);
    }
}
