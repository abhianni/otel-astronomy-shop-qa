package qa.triage.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import qa.triage.FailureBundle;
import qa.triage.GoldenExample;
import qa.triage.TriageCategory;
import qa.triage.TriageResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tier 3 of the cascade (agentic/AGENT-DESIGN.md §1): "golden RAG-lite". Retrieves the
 * top-k most similar labeled failures from agentic/golden/labeled-failures.jsonl by plain
 * token-overlap (Jaccard) on normalized test name + log tail — no vector DB, no embeddings.
 *
 * <p>The retrieved examples double as the "context" a future Tier 4 (LLM) would read; until
 * that tier exists, they're surfaced as {@code evidence} on the returned result. If the
 * top-k examples agree on category and the best match is similar enough, this tier bumps
 * its own confidence high enough to short-circuit; otherwise it's a low-confidence result
 * the cascade falls through past.
 */
public final class GoldenRetriever {

    // ponytail: thresholds are a starting guess, tune once real triage feedback comes in.
    private static final double SIMILARITY_THRESHOLD = 0.3;
    private static final double AGREEMENT_CONFIDENCE = 0.9;
    private static final double SOLO_MATCH_CONFIDENCE = 0.6;
    private static final int DEFAULT_TOP_K = 3;

    private final List<GoldenExample> examples;

    public GoldenRetriever() {
        this(Path.of("golden/labeled-failures.jsonl"));
    }

    public GoldenRetriever(Path goldenFile) {
        this.examples = load(goldenFile);
    }

    public List<ScoredExample> retrieveTopK(FailureBundle bundle, int k) {
        Set<String> queryTokens = tokens(bundle.testName() + " " + bundle.logTail());
        return examples.stream()
                .map(example -> new ScoredExample(example,
                        jaccard(queryTokens, tokens(example.failure().testName() + " " + example.failure().logTail()))))
                .sorted(Comparator.comparingDouble(ScoredExample::similarity).reversed())
                .limit(k)
                .toList();
    }

    public TriageResult classify(FailureBundle bundle) {
        List<ScoredExample> topK = retrieveTopK(bundle, DEFAULT_TOP_K);
        if (topK.isEmpty() || topK.get(0).similarity() < SIMILARITY_THRESHOLD) {
            return result(TriageCategory.TEST_GAP, 0.0, contextEvidence(topK),
                    "No sufficiently similar golden example found", TriageResult.RecommendedAction.ESCALATE);
        }

        TriageCategory topCategory = topK.get(0).example().expectedCategory();
        boolean allAgree = topK.stream().allMatch(scored -> scored.example().expectedCategory() == topCategory);
        double confidence = allAgree ? AGREEMENT_CONFIDENCE : SOLO_MATCH_CONFIDENCE;

        return result(topCategory, confidence, contextEvidence(topK),
                "Nearest golden example: " + topK.get(0).example().id(), defaultActionFor(topCategory));
    }

    private static List<String> contextEvidence(List<ScoredExample> topK) {
        return topK.stream()
                .map(scored -> String.format("golden:%s (%s, sim=%.2f): %s",
                        scored.example().id(), scored.example().expectedCategory().wireValue(),
                        scored.similarity(), scored.example().notes()))
                .toList();
    }

    private static TriageResult.RecommendedAction defaultActionFor(TriageCategory category) {
        return switch (category) {
            case FLAKY_TEST -> TriageResult.RecommendedAction.RETRY;
            case PRODUCT_BUG -> TriageResult.RecommendedAction.FILE_BUG;
            case ENVIRONMENT_ISSUE -> TriageResult.RecommendedAction.ESCALATE;
            case TEST_GAP -> TriageResult.RecommendedAction.FILE_BUG;
        };
    }

    private static TriageResult result(TriageCategory category, double confidence, List<String> evidence,
                                        String rootCause, TriageResult.RecommendedAction action) {
        return new TriageResult(
                category,
                TriageResult.Tier.GOLDEN_RAG,
                confidence,
                TriageResult.OwnerSquad.UNASSIGNED,
                rootCause,
                evidence,
                "",
                confidence < 0.85,
                action
        );
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
            if (token.length() > 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static List<GoldenExample> load(Path goldenFile) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return Files.readAllLines(goldenFile).stream()
                    .filter(line -> !line.isBlank())
                    .map(line -> readGoldenExample(mapper, line))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load golden dataset: " + goldenFile, e);
        }
    }

    private static GoldenExample readGoldenExample(ObjectMapper mapper, String line) {
        try {
            return mapper.readValue(line, GoldenExample.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed golden dataset line: " + line, e);
        }
    }

    public record ScoredExample(GoldenExample example, double similarity) {
    }
}
