package qa.triage;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One labeled row from agentic/golden/labeled-failures.jsonl, used by Tier 3
 * (golden retrieval) as its reference set. See agentic/AGENT-DESIGN.md §3.
 */
public record GoldenExample(
        String id,
        FailureBundle failure,
        @JsonProperty("expected_category") TriageCategory expectedCategory,
        String notes
) {
}
