package qa.triage;

import java.util.List;

/**
 * Tier 4's raw output — deliberately a narrower shape than {@link TriageResult}: the model
 * only ever proposes a category/confidence/rootCause/evidence/action. Tier/owner/
 * relatedTestCase/escalateToHuman are harness-owned (see agentic/AGENT-DESIGN.md §2/§4) and
 * are filled in by the cascade's guardrail step, never by the model.
 *
 * <p>Confidence is clamped rather than rejected — this is parsed from an untrusted external
 * response, and a slightly out-of-range number shouldn't take down the whole cascade.
 */
public record LlmVerdict(
        TriageCategory category,
        double confidence,
        String rootCause,
        List<String> evidence,
        TriageResult.RecommendedAction recommendedAction
) {
    public LlmVerdict {
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        rootCause = rootCause == null ? "" : rootCause;
    }
}
