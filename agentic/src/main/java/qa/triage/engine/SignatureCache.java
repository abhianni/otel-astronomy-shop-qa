package qa.triage.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import qa.triage.FailureBundle;
import qa.triage.TriageResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tier 2 of the cascade (agentic/AGENT-DESIGN.md §1): a signature → prior-verdict cache.
 * The signature is a hash of the normalized test name plus a normalized error signature
 * pulled from the log tail (first exception-shaped line, with volatile bits like ports/
 * ids/timestamps stripped) — so near-identical failures across runs collapse to the same
 * key even though ports, timestamps, and line numbers differ.
 *
 * <p>In-memory, with an optional JSON file backing store so entries survive across CLI
 * invocations. {@link #put} is how a human-confirmed verdict (or the golden dataset, via
 * eval tooling) seeds the cache for next time.
 */
public final class SignatureCache {

    private static final Pattern VOLATILE_TOKEN = Pattern.compile("\\d+|[0-9a-f]{8}-[0-9a-f-]{27}");
    private static final Pattern EXCEPTION_LINE = Pattern.compile(".*(Exception|Error|error|failed).*");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, TriageResult> entries = new ConcurrentHashMap<>();
    private final Path cacheFile;

    public SignatureCache() {
        this(Path.of("agentic/.cache/triage-cache.json"));
    }

    public SignatureCache(Path cacheFile) {
        this.cacheFile = cacheFile;
        load();
    }

    public static String signatureFor(FailureBundle bundle) {
        String normalized = normalizeTestName(bundle.testName()) + "|" + normalizeErrorSignature(bundle.logTail());
        return sha256Hex(normalized);
    }

    /** Cache hit: same verdict, high deterministic confidence, evidence noting the hit. */
    public Optional<TriageResult> lookup(FailureBundle bundle) {
        TriageResult cached = entries.get(signatureFor(bundle));
        if (cached == null) {
            return Optional.empty();
        }
        List<String> evidence = new ArrayList<>(cached.evidence());
        evidence.add("cache_hit: " + signatureFor(bundle));
        return Optional.of(new TriageResult(
                cached.category(),
                TriageResult.Tier.SIGNATURE_CACHE,
                0.95,
                cached.owner(),
                cached.rootCause(),
                evidence,
                cached.relatedTestCase(),
                false,
                cached.recommendedAction()
        ));
    }

    public void put(FailureBundle bundle, TriageResult result) {
        entries.put(signatureFor(bundle), result);
        persist();
    }

    private static String normalizeTestName(String testName) {
        return testName.trim().toLowerCase();
    }

    private static String normalizeErrorSignature(String logTail) {
        String line = logTail.lines()
                .filter(l -> EXCEPTION_LINE.matcher(l).matches())
                .findFirst()
                .orElseGet(() -> logTail.lines().findFirst().orElse(""));
        return VOLATILE_TOKEN.matcher(line.trim().toLowerCase()).replaceAll("#");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void load() {
        if (!Files.exists(cacheFile)) {
            return;
        }
        try {
            Map<String, TriageResult> loaded = mapper.readValue(cacheFile.toFile(), new TypeReference<>() {
            });
            entries.putAll(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load signature cache: " + cacheFile, e);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(cacheFile.toAbsolutePath().getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), entries);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist signature cache: " + cacheFile, e);
        }
    }
}
