package qa.triage;

import java.util.List;

/**
 * Strict output contract for the CI Triage Agent (agentic/AGENT-DESIGN.md §2). The
 * canonical constructor is the enforcement point: it rejects out-of-range confidence
 * and makes evidence immutable, so an invalid TriageResult can't even be constructed.
 */
public record TriageResult(
        TriageCategory category,
        Tier decidedAtTier,
        double confidence,
        OwnerSquad owner,
        String rootCause,
        List<String> evidence,
        String relatedTestCase,
        boolean escalateToHuman,
        RecommendedAction recommendedAction
) {
    public TriageResult {
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
        evidence = List.copyOf(evidence);
    }

    /** Which cascade tier produced this result. NONE = no tier has been implemented yet. */
    public enum Tier { RULES, SIGNATURE_CACHE, GOLDEN_RAG, LLM, NONE }

    /** Mirrors the pod ownership model in test-strategy.md §3 / automation-strategy.md §2. */
    public enum OwnerSquad { COMMERCE, CATALOG, PLATFORM, UNASSIGNED }

    /** What to do about the failure, independent of which tier decided the category. */
    public enum RecommendedAction { RETRY, FILE_BUG, ESCALATE, QUARANTINE, IGNORE }
}
