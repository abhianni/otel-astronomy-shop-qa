# Agent design — CI Triage Agent

## Scope

This agent does exactly one job: **classify a CI test failure into a category and decide
whether a human needs to look at it.** It does not generate tests and it does not self-heal
anything — those are explicitly out of scope for this deliverable. `test-strategy.md` §6 names
CI failure triage as the highest-leverage lever for a 4-person QA pod ("CI MTTR" in §5), and
`automation-strategy.md` already assumes this exists downstream of the Java/Gradle suite in
`automation/`. This design is that piece.

**Architecture decision:** a **linear 4-tier cascade**, not a multi-agent graph. See §6 for why.

---

## 1. Cascade diagram

```
                        FailureBundle (redacted)
                                │
                                ▼
                    ┌───────────────────────┐
                    │ TIER 1 — Rules/regex  │   deterministic string/pattern match
                    │ (known error shapes)  │   e.g. "Could not resolve host port",
                    └──────────┬────────────┘   "Known bug (...) no longer reproduces"
                               │ match, high confidence
                               │────────────────────────────┐
                               │ no match / low confidence   │
                               ▼                             │
                    ┌───────────────────────┐                │
                    │ TIER 2 — Signature    │   normalize log (strip UUIDs/ports/          │
                    │ cache (local, offline)│   timestamps) → hash → look up prior          │
                    └──────────┬────────────┘   human-confirmed verdict for this signature  │
                               │ cache hit                    │
                               │──────────────────────────────┤
                               │ cache miss                   │
                               ▼                               │
                    ┌───────────────────────┐                  │
                    │ TIER 3 — Golden       │   lexical overlap against a small curated      │
                    │ RAG-lite (offline)    │   set of documented findings (test-cases/*.md,  │
                    └──────────┬────────────┘   Xfail comments) — keyword match, no vector DB │
                               │ good match                    │
                               │────────────────────────────────┤
                               │ weak/no match                  │
                               ▼                                 │
                    ┌───────────────────────┐                    │
                    │ TIER 4 — LLM          │   OPTIONAL. Skipped entirely if no API key is   │
                    │ (only if API key set) │   configured — offline mode runs Tiers 1–3 only.│
                    └──────────┬────────────┘                    │
                               │                                 │
                               ▼◀────────────────────────────────┘
                    ┌───────────────────────┐
                    │ GUARDRAILS            │   hybrid confidence gate (§ below)
                    │ (hybrid confidence)   │
                    └──────────┬────────────┘
                        confidence ≥ 0.85 │ confidence < 0.85
                               ▼          ▼
                     auto-post label   escalateToHuman = true
                     (still logged,    → human triage queue
                      never auto-acts)
```

Each tier either produces a confident-enough `TriageResult` and **short-circuits** (later tiers
never run), or falls through to the next tier. Tier 4 is the only tier that costs latency/money
and the only one that can be absent entirely.

### Target cascade (v1 → v3)

**v1 (now):** classify a new failure by escalating cheap → expensive. **Tier 1** maps the
*current* log to a category via direct pattern match and a hand-tuned confidence (no historical
lookup). **Tier 2** checks an exact signature cache for a prior **human-confirmed** verdict.
**Tier 3** retrieves similar labeled failures from the golden file (Jaccard today) and derives a
confidence from match strength and top-k category agreement. If every tier scores below the
short-circuit threshold (~0.85), **Tier 4** (optional LLM) proposes a label; **guardrails**
blend deterministic and model scores and decide auto-label vs escalate.

**v3 (future, §9):** Tier 3 upgrades to **embedding search** over golden + human-reviewed
cache entries, with similarity-calibrated confidence instead of fixed 0.9/0.6. **Eval** (offline)
runs the engine against the golden set to measure accuracy; it is not a runtime tier.

**Learning loop:** when a human confirms or overrides a label, that record is written to **Tier 2
cache** (exact repeats) and appended to the **retrieval corpus** (semantic neighbors). Tier 1
rules stay fixed; repeat and near-duplicate failures migrate out of Tier 4 over time.

---

## 2. Schemas

### Input — `FailureBundle`

Assembled by the existing CI collector step (`automation-strategy.md` §3), not by the agent. The
agent never gets raw filesystem/CI/Docker access — only this:

```java
package qa.triage;

/** Everything the agent may see for one failing test. Redaction already applied (§4). */
public record FailureBundle(
        String testName,        // fully-qualified, e.g. "qa.CurrencyTest.unknownCurrencyCodeIsRejectedNotSilentlyZeroed"
        String logTail,         // last ~200 lines of stdout/stderr, REDACTED before this record exists
        String diff,            // unified diff, truncated to hunks touching paths related to testName
        String buildUrl,        // link back to the CI run, for the human reviewing the label
        int attemptNumber,      // CI's own retry count (1 or 2) — a strong flaky_test prior
        List<String> last5Runs  // last 5 run outcomes, most-recent-first, "PASS"/"FAIL" — Tier 1 flaky signal
) {}
```

### Output — `TriageResult`

```java
package qa.triage;

import java.util.List;

/**
 * Strict output contract. Whichever tier resolves the bundle must produce exactly this
 * shape — the canonical constructor is the enforcement point, not prose in a prompt.
 */
public record TriageResult(
        Category category,
        Tier decidedAtTier,      // which cascade tier produced this — required for the eval metrics in §5
        double confidence,       // 0.0–1.0
        OwnerSquad owner,        // mirrors test-strategy.md §3 pod model
        String rootCause,        // one sentence, not a log dump
        List<String> evidence,   // exact quoted lines from logTail/diff that justify the category
        String relatedTestCase,  // e.g. "BE-06" if it matches a documented finding, else ""
        boolean escalateToHuman, // set by guardrails, never by a tier directly
        RecommendedAction recommendedAction  // RETRY / FILE_BUG / ESCALATE / QUARANTINE / IGNORE
) {
    public TriageResult {
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
        evidence = List.copyOf(evidence);
    }

    /** Exactly these four wire values — no fifth "unknown" bucket (see §4 fallback rule). */
    public enum Category {
        FLAKY_TEST("flaky_test"),
        PRODUCT_BUG("product_bug"),
        ENVIRONMENT_ISSUE("environment_issue"),
        TEST_GAP("test_gap");

        private final String wireValue;
        Category(String wireValue) { this.wireValue = wireValue; }

        @com.fasterxml.jackson.annotation.JsonValue
        public String wireValue() { return wireValue; }
    }

    public enum Tier { RULES, SIGNATURE_CACHE, GOLDEN_RAG, LLM }

    public enum OwnerSquad { COMMERCE, CATALOG, PLATFORM, UNASSIGNED }
}
```

Build stays Gradle-only (same `automation/build.gradle` toolchain) — no Maven module, no second
build system for this piece.

---

## 3. What the agent decides vs. what stays human

| Decision | Agent | Human |
|---|---|---|
| Category (`flaky_test` / `product_bug` / `environment_issue` / `test_gap`) | Proposes | Confirms or overrides |
| Confidence + which tier resolved it | Computes | Reads, doesn't edit |
| Owner squad suggestion | Proposes (from test-strategy.md pod map) | Reassigns if wrong |
| Escalate to human queue | Decides (guardrail threshold) | — |
| Quarantine a test (`@Disabled`) | **Never** | Always — agent only labels, never edits test files |
| File a bug ticket | **Never** | Always — agent proposes `rootCause` text, human files it |
| Merge / block a PR | **Never** | Always — CI gate itself, not this agent |
| Retry a flaky test | **Never** | CI's existing retry policy (`automation-strategy.md` §4), not this agent |
| Confirm/override feeds back into Tier 2 signature cache | — | Human's final call is what gets cached for next time |

The agent is read-only against the codebase and write-only to a label/comment. This mirrors the
"Ask First" / no-destructive-automation posture used everywhere else in this repo.

---

## 4. Guardrails — hybrid confidence → escalate

| Confidence from resolving tier | Behavior |
|---|---|
| ≥ 0.85 | Auto-post `TriageResult` as the label; `escalateToHuman = false` |
| 0.50 – 0.84 | Post as a *suggested* label; `escalateToHuman = true` (needs human confirmation before anyone acts on it) |
| < 0.50, or Tier 4 unavailable/failed and Tiers 1–3 found nothing | `escalateToHuman = true`; `category` still gets the best available guess (never a 5th "unknown" value, since the category enum is fixed at exactly four) — default fallback is `TEST_GAP` at `confidence = 0.0`, on the reasoning that an unclassifiable failure is most often a sign the test/log itself needs a human look before anything else, not a guess at product vs. environment |

**Redaction (mandatory, before a `FailureBundle` even exists):** `logTail`/`diff` are scrubbed for
credential-shaped strings before the collector step hands them to any tier — same rule already
in force for this repo generally. Tiers 1–3 never need this since they're local pattern
matching; it matters most for Tier 4, which is the only tier whose input leaves the CI runner.

**Prompt-injection guardrail (Tier 4 only):** log/diff content is framed as data to classify,
never as instructions — a test's own stdout is attacker- or bug-controllable and must never be
able to tell the agent what label to assign.

### 4.1 Deterministic confidence (Tiers 1–3) — not ML, not record lookup

Confidence in Tiers 1–3 is **not** a model probability and **Tier 1 does not search existing
records**. Each tier assigns a policy score in `[0, 1]` meaning *"how much should we trust
this answer without calling an LLM?"*

| Tier | What drives confidence | Uses existing records? |
|---|---|---|
| **Tier 1 — Rules** | Hand-tuned constant per rule, based on signal unambiguity (e.g. `ECONNREFUSED` → 0.95; bare `OutOfMemoryError` → 0.78 because it could be CI runner heap) | **No** — regex/logic on the *current* `FailureBundle` only |
| **Tier 2 — Cache** | Fixed **0.95** on signature hit — prior human-confirmed verdict for this normalized failure shape | **Yes** — exact hash lookup in `.cache/triage-cache.json` |
| **Tier 3 — Golden RAG-lite** | Fixed **0.9** if top-k goldens agree on category, else **0.6** if only the best Jaccard match agrees; below similarity threshold → 0.0 | **Yes** — token overlap on `golden/labeled-failures.jsonl` |

Short-circuit threshold is **0.85** everywhere: at or above → auto-label; below → fall through
(or `escalateToHuman = true` on the tier's provisional result).

Only when Tier 4 runs does hybrid blending apply: `finalScore = 0.6 × best_deterministic + 0.4 ×
model_confidence` (see `Guardrails.java`). Tiers 1–3 never call an embedding model or LLM.

Constants are **starting guesses** — tune from weekly accuracy / escalation samples once real
nightly failures flow through the cascade.

---

## 5. Eval metrics

| Metric | Definition | Target |
|---|---|---|
| **Accuracy** | Agent's `category` (at time of posting) matches the human's final confirmed category, sampled weekly | ≥85% agreement |
| **Escalation rate** | `escalateToHuman = true` ÷ total triaged | No fixed target at launch — tracked as a *health* signal. High and flat = cascade isn't learning; declining over weeks = Tier 2 cache is absorbing repeat failures correctly |
| **Tier resolution mix** | % resolved at each of Tier 1/2/3/4 vs. escalated with no tier match | Healthy state: majority resolved at Tiers 1–2 (near-zero cost); Tier 4 usage trending down as Tier 3's golden set and Tier 2's cache grow |
| **CI MTTR** (already defined in `test-strategy.md` §5) | Time from red CI → classified + assigned | <30 min median — this agent is the mechanism, not a separate metric |

Accuracy and escalation rate together catch the two failure modes that matter: a cascade that's
*wrong* (bad accuracy) and a cascade that's *useless* (escalates everything, defeating the point).



## 6. Worked examples

### Example A — `flaky_test`, resolved at Tier 1

Input:

```json
{
  "testName": "qa.CartTest.addItemThenGetCartReturnsPersistedQuantity",
  "logTail": "...\nio.grpc.StatusRuntimeException: UNAVAILABLE: io exception\nCaused by: java.net.SocketException: Connection reset by peer\n\tat qa.GrpcClients... (attempt 1 of 2)\n...",
  "diff": "",
  "buildUrl": "https://example.com/actions/runs/501",
  "attemptNumber": 1
}
```

Output:

```json
{
  "category": "flaky_test",
  "decidedAtTier": "RULES",
  "confidence": 0.95,
  "owner": "COMMERCE",
  "rootCause": "Transient gRPC connection reset on attempt 1 of 2, matches known transient-network signature",
  "evidence": ["UNAVAILABLE: io exception", "Connection reset by peer"],
  "relatedTestCase": "",
  "escalateToHuman": false
}
```

### Example B — `product_bug`, resolved at Tier 3 (Golden RAG-lite)

Input (abbreviated):

```json
{
  "testName": "qa.CurrencyTest.placeOrderWithUnsupportedCurrencyIsRejectedNotCompletedAsAFreeOrder",
  "logTail": "...\norg.opentest4j.AssertionFailedError: Known bug (checkout.PlaceOrder silently completes a $0.00 order for an unsupported currency code — see BE-06) no longer reproduces — promote this assertion out of Xfail.\n...",
  "diff": "diff --git a/src/currency/src/server.cpp b/src/currency/src/server.cpp\n+  if (currency_conversion.find(to_code) == currency_conversion.end()) {\n+    return Status(StatusCode::INVALID_ARGUMENT, \"unsupported currency code\");\n+  }\n",
  "buildUrl": "https://example.com/actions/runs/502",
  "attemptNumber": 1
}
```

Output:

```json
{
  "category": "test_gap",
  "decidedAtTier": "GOLDEN_RAG",
  "confidence": 0.9,
  "owner": "COMMERCE",
  "rootCause": "BE-06 currency fix landed; the Xfail wrapper in CurrencyTest is now stale and needs to be promoted to a real assertion",
  "evidence": [
    "Known bug (... BE-06) no longer reproduces — promote this assertion out of Xfail.",
    "+  if (currency_conversion.find(to_code) == currency_conversion.end()) {"
  ],
  "relatedTestCase": "BE-06",
  "escalateToHuman": false
}
```

*(Note: this specific example resolves as `test_gap`, not `product_bug` — the product got*
*fixed, so what's actually broken now is the test scaffolding. See Example B' below for the*
*inverse case, a genuine still-open `product_bug`.)*

### Example B' — `product_bug`, resolved at Tier 3

Input (abbreviated) — same `Xfail` mechanism, but this time the assertion still fails as
documented (bug still open):

```json
{
  "testName": "qa.QuantityBoundaryTest.extremeQuantityIsRejectedFastNotLeftToHangOrDestabilizeCheckout",
  "logTail": "...\norg.opentest4j.AssertionFailedError: expected: <true> but was: <false>\n\tat qa.Xfail.expectFailure (elapsed 8003ms, deadline exceeded)\n...",
  "diff": "",
  "buildUrl": "https://example.com/actions/runs/503",
  "attemptNumber": 1
}
```

Output:

```json
{
  "category": "product_bug",
  "decidedAtTier": "GOLDEN_RAG",
  "confidence": 0.85,
  "owner": "COMMERCE",
  "rootCause": "Matches documented BE-04c: no upper bound on cart quantity, checkout hangs/restarts on int32-max instead of validating",
  "evidence": ["elapsed 8003ms, deadline exceeded"],
  "relatedTestCase": "BE-04",
  "escalateToHuman": false
}
```

### Example C — `environment_issue`, resolved at Tier 1

Input:

```json
{
  "testName": "qa.PricingTest.orderItemCostIsUnitPriceInvariantAcrossQuantity",
  "logTail": "...\njava.lang.IllegalStateException: Could not resolve host port for currency:7001. Is the stack running (\"docker compose up\")? Or set CURRENCY_HOST_PORT explicitly.\n...",
  "diff": "",
  "buildUrl": "https://example.com/actions/runs/504",
  "attemptNumber": 1
}
```

Output:

```json
{
  "category": "environment_issue",
  "decidedAtTier": "RULES",
  "confidence": 0.97,
  "owner": "PLATFORM",
  "rootCause": "Demo stack (or the currency container specifically) was not up when the suite ran — matches GrpcClients' own port-resolution failure message",
  "evidence": ["Could not resolve host port for currency:7001"],
  "relatedTestCase": "",
  "escalateToHuman": false
}
```

---

## 7. Offline mode

Tiers 1–3 are pure Java, no network, no API key — this is the default and required mode. Tier 4
is guarded by a single check (API key env var present); when absent, a `NoopLlmTier` stub
returns "no result" and the cascade proceeds straight to guardrails with whatever Tiers 1–3
produced. The JUnit suite for this agent (next artifact, alongside the CLI transcript) runs
entirely against `NoopLlmTier` — no test in this repo may require a live LLM call to pass.

## 8. Out of scope

- The CLI implementation and its required transcript artifact — next piece of `agentic/`.
- Tier 4's actual prompt template and model choice — deferred to the CLI implementation, not this
  design doc.
- Any auto-remediation (auto-quarantine, auto-file, auto-merge) — deliberately excluded, see §3.

---

## 9. Future implementation — semantic retrieval & calibrated confidence

*Not in v1. Documented here so reviewers see the evolution path without mistaking today's
fixed constants for a finished ML system.*

### 9.1 What stays unchanged

- **Tier 1 hard rules** for obvious shapes (`ECONNREFUSED`, mixed `last5Runs`, etc.) — cheap,
  explainable, should not be replaced by embeddings.
- **Tier 2 exact signature cache** — hash hit remains the highest-trust path for repeat failures.
- **Human gate** — confirm/override still seeds cache; agent never auto-quarantines or merges.

### 9.2 What embeddings would improve (Tier 3 + confidence)

Today Tier 3 uses **Jaccard token overlap** on a small golden file and maps matches to fixed
scores (0.9 / 0.6). That breaks down when:

- Log wording drifts but semantics match ("connection reset" vs "ECONNREFUSED").
- The golden corpus grows beyond ~30 rows (manual token overlap gets noisy).
- We want confidence **derived from similarity**, not a hand-picked constant.

**Proposed month-3 upgrade:** replace (or augment) Jaccard with **embedding search** over a
combined corpus:

| Source | Indexed text | Purpose |
|---|---|---|
| `golden/labeled-failures.jsonl` | `testName + logTail + notes` | Curated ground truth |
| Tier 2 cache entries | same fields from human-confirmed failures | Learn from production triage, not just hand-authored goldens |
| Optional: automation failure exports | JUnit XML + log snippet | Auto-grow corpus from real nightlies |

Retrieval flow:

```
query = embed(normalize(testName + logTail))
top-k = vector_index.search(query, k=5)
confidence = map(cosine_similarity, category_agreement)
```

Example calibration (tune on held-out goldens):

| Signal | Confidence mapping |
|---|---|
| Top-1 similarity ≥ 0.85 and top-3 agree on category | 0.90–0.95 (short-circuit) |
| Top-1 similarity 0.60–0.84 | 0.55–0.75 (suggest, escalate) |
| Top-1 similarity < 0.60 or category split in top-k | fall through to Tier 4 LLM |
| Tier 1 rule agrees with top-1 golden category | +0.05 boost (hybrid agreement) |

This replaces fixed `0.9` / `0.6` with **similarity-derived** scores while keeping Tier 1 rules
as a separate, auditable signal.

### 9.3 Prerequisites before building it

| Prerequisite | Why |
|---|---|
| Human confirm → cache loop wired (`triage confirm` CLI or dashboard) | Embeddings over an empty cache teach nothing |
| ≥50–100 labeled failures (golden + confirmed cache) | Enough to calibrate similarity → confidence and measure regression |
| Held-out eval set + LLM-as-judge (§5 metrics) | Prove embedding tier beats Jaccard on accuracy, not just "feels smarter" |
| Cost/latency budget per nightly failure | Embedding API or local model must beat LLM-only on $/classification |

### 9.4 Explicit non-goals for the embedding tier

- **Not** replacing Tier 1 regex — keep deterministic catches for env and flake priors.
- **Not** a vector DB for the assignment's 8–10 goldens — Jaccard is sufficient at that scale.
- **Not** auto-healing from nearest neighbor — retrieval informs **classification only**;
  recommended actions still require human confirmation below the guardrail threshold.

### 9.5 Implementation sketch (when ready)

```
agentic/
  retrieval/
    EmbeddingRetriever.java    # interface: embed + search
    JaccardRetriever.java      # current Tier 3 (default until corpus grows)
    CachedEmbeddingIndex.java  # optional: sqlite/pgvector or on-disk annoy index
  golden/ + .cache/            # both indexed; cache refreshed on human confirm
```

Feature flag: `TRIAGE_RETRIEVAL=lexical|semantic` — run both in shadow mode on nightly CI,
compare category agreement vs human before flipping the default.

---
