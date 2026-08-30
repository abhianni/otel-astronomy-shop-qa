package qa.triage.engine;

import qa.triage.FailureBundle;
import qa.triage.TriageCategory;
import qa.triage.TriageResult;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tier 1 of the cascade (agentic/AGENT-DESIGN.md §3/§7): deterministic string/regex
 * rules over the raw log tail, diff, and run history. No network, no LLM — just the
 * FailureBundle already in hand. Rules run in priority order and the first match wins.
 *
 * <p>Every match carries a {@code confidence} in [0,1] that {@link CascadeTriageEngine}
 * uses as the "short-circuit, skip cache/LLM" threshold — a rule can fire and still be
 * a low-confidence guess (see the OOM rule below) when the signal alone isn't enough to
 * safely auto-resolve.
 */
public final class Tier1RuleEngine {

    private static final Pattern CONNECTION = Pattern.compile(
            "(?i)connection refused|ECONNREFUSED|UnknownHostException|could not resolve host|"
                    + "name or service not known");

    private static final Pattern OOM_KILLED = Pattern.compile("(?i)OOMKilled");
    private static final Pattern OUT_OF_MEMORY = Pattern.compile("(?i)OutOfMemoryError");
    private static final Pattern CONTAINER_LOG_PREFIX = Pattern.compile("^[\\w.-]+(_\\d+)?\\s*\\|");

    private static final Pattern TEST_GAP = Pattern.compile(
            "(?i)\\btodo\\b|not (yet )?implemented|missing assertion|no assertions? (found|present)");

    private static final Pattern PASSED_ON_RETRY = Pattern.compile("(?i)passed on retry");

    private static final Pattern ASSERTION_FAILURE = Pattern.compile(
            "(?i)assertionerror|assertionfailederror|expected:\\s|expected \\[");

    /** Tier 1 always returns a result; below-threshold matches are for the cascade to discard. */
    public TriageResult classify(FailureBundle bundle) {
        return checkConnectionIssue(bundle)
                .or(() -> checkOutOfMemory(bundle))
                .or(() -> checkTestGap(bundle))
                .or(() -> checkFlaky(bundle))
                .or(() -> checkAssertionFailure(bundle))
                .orElseGet(() -> result(TriageCategory.TEST_GAP, 0.0, List.of(),
                        "Tier 1 found no matching rule", TriageResult.RecommendedAction.ESCALATE));
    }

    private Optional<TriageResult> checkConnectionIssue(FailureBundle bundle) {
        List<String> evidence = matchingLines(bundle.logTail(), CONNECTION);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result(TriageCategory.ENVIRONMENT_ISSUE, 0.95, evidence,
                "Log tail shows a connection/DNS failure reaching a dependency",
                TriageResult.RecommendedAction.RETRY));
    }

    /**
     * OOM is inherently ambiguous without deeper context (heap dump, container inspect), so
     * this rule deliberately does not always short-circuit:
     * - "OOMKilled" is a container-level kernel signal (cgroup memory limit hit) — clearly an
     *   infra sizing issue, high confidence.
     * - "OutOfMemoryError" prefixed by a docker-compose-style container log line ("service_1 |")
     *   plausibly means the app-under-test's own JVM leaked — flagged as a possible product bug,
     *   but just under the short-circuit threshold since Tier 1 can't confirm a leak from a
     *   single line.
     * - A bare "OutOfMemoryError" with no container attribution is most likely our own JUnit/CI
     *   runner JVM hitting its heap limit — an environment sizing issue, but again not confident
     *   enough to auto-resolve.
     */
    private Optional<TriageResult> checkOutOfMemory(FailureBundle bundle) {
        List<String> oomKilled = matchingLines(bundle.logTail(), OOM_KILLED);
        if (!oomKilled.isEmpty()) {
            return Optional.of(result(TriageCategory.ENVIRONMENT_ISSUE, 0.92, oomKilled,
                    "Container was killed by the OOM killer — memory limit, not app code",
                    TriageResult.RecommendedAction.ESCALATE));
        }

        List<String> oomLines = matchingLines(bundle.logTail(), OUT_OF_MEMORY);
        if (oomLines.isEmpty()) {
            return Optional.empty();
        }
        boolean containerAttributed = oomLines.stream().anyMatch(line -> CONTAINER_LOG_PREFIX.matcher(line).find());
        if (containerAttributed) {
            return Optional.of(result(TriageCategory.PRODUCT_BUG, 0.82, oomLines,
                    "OutOfMemoryError attributed to the app-under-test's own container log",
                    TriageResult.RecommendedAction.FILE_BUG));
        }
        return Optional.of(result(TriageCategory.ENVIRONMENT_ISSUE, 0.78, oomLines,
                "OutOfMemoryError with no container attribution — likely the test/CI runner JVM",
                TriageResult.RecommendedAction.ESCALATE));
    }

    private Optional<TriageResult> checkTestGap(FailureBundle bundle) {
        List<String> evidence = matchingLines(bundle.logTail() + "\n" + bundle.diff(), TEST_GAP);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result(TriageCategory.TEST_GAP, 0.88, evidence,
                "TODO/not-implemented/missing-assertion marker found",
                TriageResult.RecommendedAction.FILE_BUG));
    }

    private Optional<TriageResult> checkFlaky(FailureBundle bundle) {
        boolean mixedHistory = bundle.last5Runs().stream().anyMatch("PASS"::equalsIgnoreCase)
                && bundle.last5Runs().stream().anyMatch("FAIL"::equalsIgnoreCase);
        List<String> passedOnRetry = matchingLines(bundle.logTail(), PASSED_ON_RETRY);

        if (!mixedHistory && passedOnRetry.isEmpty()) {
            return Optional.empty();
        }
        List<String> evidence = mixedHistory ? List.of("last5Runs=" + bundle.last5Runs()) : passedOnRetry;
        return Optional.of(result(TriageCategory.FLAKY_TEST, 0.9, evidence,
                "Test does not fail consistently across recent runs",
                TriageResult.RecommendedAction.RETRY));
    }

    /** By the time we get here, last5Runs (if known) is either empty or all-FAIL — checkFlaky already caught mixed history. */
    private Optional<TriageResult> checkAssertionFailure(FailureBundle bundle) {
        List<String> evidence = matchingLines(bundle.logTail(), ASSERTION_FAILURE);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result(TriageCategory.PRODUCT_BUG, 0.88, evidence,
                "Consistent assertion failure across known run history",
                TriageResult.RecommendedAction.FILE_BUG));
    }

    private static List<String> matchingLines(String text, Pattern pattern) {
        return Arrays.stream(text.split("\n"))
                .filter(line -> pattern.matcher(line).find())
                .limit(3)
                .toList();
    }

    private static TriageResult result(TriageCategory category, double confidence, List<String> evidence,
                                        String rootCause, TriageResult.RecommendedAction action) {
        return new TriageResult(
                category,
                TriageResult.Tier.RULES,
                confidence,
                TriageResult.OwnerSquad.UNASSIGNED,
                rootCause,
                evidence,
                "",
                confidence < 0.85,
                action
        );
    }
}
