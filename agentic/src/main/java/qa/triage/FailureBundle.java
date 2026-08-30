package qa.triage;

import java.util.List;

/**
 * Everything the CI Triage Agent may see for one failing test. Assembled by the CI
 * collector step (see agentic/AGENT-DESIGN.md §1) — the agent itself has no
 * filesystem/CI/Docker access, only this. logTail/diff are expected to already be
 * redacted before this record is constructed.
 *
 * @param last5Runs outcome of this test's last 5 CI runs, most-recent-first, each
 *                  either {@code "PASS"} or {@code "FAIL"}. Empty if history is
 *                  unknown/unavailable. Used by Tier 1's flaky-test rule.
 */
public record FailureBundle(
        String testName,
        String logTail,
        String diff,
        String buildUrl,
        int attemptNumber,
        List<String> last5Runs
) {
    public FailureBundle {
        last5Runs = last5Runs == null ? List.of() : List.copyOf(last5Runs);
    }
}
