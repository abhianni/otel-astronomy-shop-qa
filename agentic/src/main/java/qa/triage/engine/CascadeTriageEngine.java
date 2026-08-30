package qa.triage.engine;

import qa.triage.FailureBundle;
import qa.triage.LlmVerdict;
import qa.triage.TriageResult;

import java.util.Optional;

/**
 * The real cascade entry point (agentic/AGENT-DESIGN.md §3/§4): Tiers 1 and 3 either produce
 * a confident-enough result ({@code confidence >= SHORT_CIRCUIT_THRESHOLD}) and short-circuit,
 * or fall through. Whatever wasn't confident enough on its own goes to Tier 4 (if an LLM is
 * configured) and then, either way, through {@link Guardrails} for the final banding decision.
 */
public final class CascadeTriageEngine implements CiTriageEngine {

    private static final double SHORT_CIRCUIT_THRESHOLD = 0.85;

    private final Tier1RuleEngine tier1;
    private final SignatureCache signatureCache;
    private final GoldenRetriever goldenRetriever;
    private final Tier4LlmEngine tier4;

    public CascadeTriageEngine() {
        this(new Tier1RuleEngine(), new SignatureCache(), new GoldenRetriever(), new Tier4LlmEngine());
    }

    CascadeTriageEngine(Tier1RuleEngine tier1, SignatureCache signatureCache, GoldenRetriever goldenRetriever,
                        Tier4LlmEngine tier4) {
        this.tier1 = tier1;
        this.signatureCache = signatureCache;
        this.goldenRetriever = goldenRetriever;
        this.tier4 = tier4;
    }

    /** Exposed so eval/golden tooling can seed confirmed verdicts (see SignatureCache#put). */
    public SignatureCache signatureCache() {
        return signatureCache;
    }

    @Override
    public TriageResult triage(FailureBundle bundle) {
        TriageResult tier1Result = tier1.classify(bundle);
        if (tier1Result.confidence() >= SHORT_CIRCUIT_THRESHOLD) {
            return tier1Result;
        }

        Optional<TriageResult> cacheHit = signatureCache.lookup(bundle);
        if (cacheHit.isPresent()) {
            return cacheHit.get();
        }

        TriageResult tier3Result = goldenRetriever.classify(bundle);
        if (tier3Result.confidence() >= SHORT_CIRCUIT_THRESHOLD) {
            return tier3Result;
        }

        TriageResult deterministicBest = tier3Result.confidence() >= tier1Result.confidence() ? tier3Result : tier1Result;
        Optional<LlmVerdict> llmVerdict = tier4.classify(bundle, deterministicBest);
        return Guardrails.decide(deterministicBest, llmVerdict);
    }
}
