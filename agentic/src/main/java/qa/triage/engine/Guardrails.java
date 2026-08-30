package qa.triage.engine;

import qa.triage.LlmVerdict;
import qa.triage.TriageCategory;
import qa.triage.TriageResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The cascade's final decision layer (agentic/AGENT-DESIGN.md §4). No tier decides
 * {@code escalateToHuman} or overrides a low-confidence action itself — only this class
 * does, uniformly, whether or not Tier 4 ran:
 *
 * <ul>
 *   <li>finalScore &gt;= 0.85 -&gt; auto-label, {@code escalateToHuman = false}
 *   <li>0.60 &lt;= finalScore &lt; 0.85 -&gt; suggest, {@code escalateToHuman = true}, proposed action kept
 *   <li>finalScore &lt; 0.60 -&gt; escalate, {@code escalateToHuman = true}, action forced to
 *       {@code ESCALATE} regardless of what was proposed — too little confidence to trust it
 * </ul>
 */
final class Guardrails {

    static final double AUTO_LABEL_THRESHOLD = 0.85;
    static final double SUGGEST_THRESHOLD = 0.60;

    private Guardrails() {
    }

    /**
     * @param deterministicBest the higher-confidence of Tier 1's and Tier 3's guesses
     * @param llmVerdict        Tier 4's verdict, if an LLM was configured and answered
     */
    static TriageResult decide(TriageResult deterministicBest, Optional<LlmVerdict> llmVerdict) {
        if (llmVerdict.isEmpty()) {
            return band(deterministicBest.category(), deterministicBest.confidence(), deterministicBest.decidedAtTier(),
                    deterministicBest.owner(), deterministicBest.rootCause(), deterministicBest.evidence(),
                    deterministicBest.relatedTestCase(), deterministicBest.recommendedAction());
        }

        LlmVerdict verdict = llmVerdict.get();
        double finalScore = 0.6 * deterministicBest.confidence() + 0.4 * verdict.confidence();

        List<String> evidence = new ArrayList<>(deterministicBest.evidence());
        verdict.evidence().forEach(line -> evidence.add("llm: " + line));

        return band(verdict.category(), finalScore, TriageResult.Tier.LLM, deterministicBest.owner(),
                verdict.rootCause(), evidence, deterministicBest.relatedTestCase(), verdict.recommendedAction());
    }

    private static TriageResult band(TriageCategory category, double finalScore, TriageResult.Tier tier,
                                      TriageResult.OwnerSquad owner, String rootCause, List<String> evidence,
                                      String relatedTestCase, TriageResult.RecommendedAction proposedAction) {
        if (finalScore >= AUTO_LABEL_THRESHOLD) {
            return new TriageResult(category, tier, finalScore, owner, rootCause, evidence, relatedTestCase,
                    false, proposedAction);
        }
        if (finalScore >= SUGGEST_THRESHOLD) {
            return new TriageResult(category, tier, finalScore, owner, rootCause, evidence, relatedTestCase,
                    true, proposedAction);
        }
        return new TriageResult(category, tier, finalScore, owner, rootCause, evidence, relatedTestCase,
                true, TriageResult.RecommendedAction.ESCALATE);
    }
}
