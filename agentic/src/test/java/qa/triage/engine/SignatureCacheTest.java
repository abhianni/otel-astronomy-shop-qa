package qa.triage.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qa.triage.FailureBundle;
import qa.triage.TriageCategory;
import qa.triage.TriageResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureCacheTest {

    @Test
    void firstCallMissesSecondCallHitsForSameSignature(@TempDir Path tempDir) {
        SignatureCache cache = new SignatureCache(tempDir.resolve("triage-cache.json"));
        FailureBundle bundle = bundle("boom: connection reset by peer on port 9142");

        assertTrue(cache.lookup(bundle).isEmpty());

        TriageResult confirmed = new TriageResult(
                TriageCategory.PRODUCT_BUG, TriageResult.Tier.LLM, 0.7, TriageResult.OwnerSquad.COMMERCE,
                "confirmed by a human", List.of("original evidence"), "", true,
                TriageResult.RecommendedAction.FILE_BUG);
        cache.put(bundle, confirmed);

        Optional<TriageResult> hit = cache.lookup(bundle);
        assertTrue(hit.isPresent());
        assertEquals(TriageCategory.PRODUCT_BUG, hit.get().category());
        assertEquals(TriageResult.Tier.SIGNATURE_CACHE, hit.get().decidedAtTier());
        assertTrue(hit.get().confidence() >= 0.85);
        assertTrue(hit.get().evidence().contains("cache_hit: " + SignatureCache.signatureFor(bundle)));
    }

    @Test
    void sameSignatureIgnoresVolatilePortsAndTimestamps(@TempDir Path tempDir) {
        SignatureCache cache = new SignatureCache(tempDir.resolve("triage-cache.json"));
        FailureBundle first = bundle("java.net.ConnectException: Connection refused: localhost:9142");
        FailureBundle second = bundle("java.net.ConnectException: Connection refused: localhost:51234");

        assertEquals(SignatureCache.signatureFor(first), SignatureCache.signatureFor(second));
    }

    @Test
    void cacheSurvivesReloadFromDisk(@TempDir Path tempDir) {
        Path file = tempDir.resolve("triage-cache.json");
        FailureBundle bundle = bundle("java.lang.NullPointerException: card is null");
        TriageResult result = new TriageResult(
                TriageCategory.PRODUCT_BUG, TriageResult.Tier.LLM, 0.7, TriageResult.OwnerSquad.COMMERCE,
                "confirmed", List.of(), "", true, TriageResult.RecommendedAction.FILE_BUG);

        new SignatureCache(file).put(bundle, result);
        SignatureCache reloaded = new SignatureCache(file);

        assertTrue(reloaded.lookup(bundle).isPresent());
    }

    private static FailureBundle bundle(String logTail) {
        return new FailureBundle("qa.SomeTest.someMethod", logTail, "", "https://example.com/1", 1, List.of());
    }
}
