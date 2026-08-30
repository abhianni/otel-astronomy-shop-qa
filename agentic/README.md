# CI Triage Agent

Implements the schemas and CLI scaffold from [`AGENT-DESIGN.md`](./AGENT-DESIGN.md): a linear
4-tier cascade (rules → signature cache → golden RAG-lite → optional LLM → guardrails) that
classifies a CI failure into exactly one of `flaky_test` / `product_bug` / `environment_issue` /
`test_gap`, plus whether it should be escalated to a human.

**Status:** schemas, CLI, and Tiers 1–3 (deterministic rules, signature cache, golden
retrieval) are implemented. Tier 4 (LLM) is not — a bundle none of Tiers 1–3 is confident
about (`confidence < 0.85`) falls back to `StubTriageEngine`'s fixed guardrail result
(`test_gap`, confidence `0.0`, `escalateToHuman = true`). See `AGENT-DESIGN.md` §8 for
what's next.

## Layout

| Path | What |
|---|---|
| `src/main/java/qa/triage/FailureBundle.java` | Input record — test name, redacted log tail, scoped diff, build URL, attempt number, last 5 run outcomes |
| `src/main/java/qa/triage/TriageCategory.java` | The four exact wire values (`flaky_test`, `product_bug`, `environment_issue`, `test_gap`) |
| `src/main/java/qa/triage/TriageResult.java` | Output record — category, tier, confidence, owner squad, root cause, evidence, escalation flag, recommended action |
| `src/main/java/qa/triage/GoldenExample.java` | One labeled row from `golden/labeled-failures.jsonl` |
| `src/main/java/qa/triage/engine/CiTriageEngine.java` | The interface the cascade implements |
| `src/main/java/qa/triage/engine/Tier1RuleEngine.java` | Tier 1 — deterministic regex rules over the log tail/diff/run history |
| `src/main/java/qa/triage/engine/SignatureCache.java` | Tier 2 — hash(normalized test name + normalized error signature) → prior verdict; in-memory + optional JSON file at `.cache/triage-cache.json` |
| `src/main/java/qa/triage/engine/GoldenRetriever.java` | Tier 3 — top-k (`k=3`) Jaccard token overlap against `golden/labeled-failures.jsonl`, no vector DB |
| `src/main/java/qa/triage/engine/CascadeTriageEngine.java` | Orchestrator: each tier short-circuits at confidence `>= 0.85`, else falls through, ending at the stub |
| `src/main/java/qa/triage/engine/StubTriageEngine.java` | Placeholder for Tier 4 (see Status above) |
| `src/main/java/qa/triage/cli/TriageCli.java` | Reads a `FailureBundle` JSON (file arg or stdin), prints a `TriageResult` JSON |
| `golden/labeled-failures.jsonl` | 8 hand-labeled example failures Tier 3 retrieves against; also a starting eval set |

## Build

From the repo root (this module is wired into the root multi-project build):

```bash
./gradlew :agentic:test
```

Or everything in the repo, including `automation/`:

```bash
./gradlew test
```

## Run

**Without `LLM_API_KEY`** (offline — Tiers 1-3 run regardless; only the stub fallback for Tier 4
cares about this variable, and it doesn't yet use it):

```bash
./gradlew :agentic:run --args="path/to/bundle.json"
# or via stdin:
./gradlew :agentic:run < path/to/bundle.json
```

**With `LLM_API_KEY` set** — same output today (the stub ignores it), but the CLI logs that Tier
4 would use it once the cascade exists:

```bash
LLM_API_KEY=sk-... ./gradlew :agentic:run --args="path/to/bundle.json"
# TODO: once Tier 4 is implemented, load this key via an env var / secrets vault at
# call time — never hardcode it in code or commit it to the repo.
```

For piping real stdin without Gradle's daemon in the way, build the plain distribution once and
run the generated script directly:

```bash
./gradlew :agentic:installDist
cat path/to/bundle.json | ./agentic/build/install/agentic/bin/agentic
```

## Example input/output

Input (`bundle.json`):

```json
{
  "testName": "qa.PricingTest.orderItemCostIsUnitPriceInvariantAcrossQuantity",
  "logTail": "java.lang.IllegalStateException: Could not resolve host port for currency:7001",
  "diff": "",
  "buildUrl": "https://example.com/actions/runs/504",
  "attemptNumber": 1,
  "last5Runs": []
}
```

Output (Tier 1 matches the `environment_issue` example from `AGENT-DESIGN.md` §7 — it's
`GrpcClients`' own port-resolution error message):

```json
{
  "category" : "environment_issue",
  "decidedAtTier" : "RULES",
  "confidence" : 0.95,
  "owner" : "UNASSIGNED",
  "rootCause" : "Log tail shows a connection/DNS failure reaching a dependency",
  "evidence" : [ "java.lang.IllegalStateException: Could not resolve host port for currency:7001" ],
  "relatedTestCase" : "",
  "escalateToHuman" : false,
  "recommendedAction" : "RETRY"
}
```

A bundle none of Tiers 1-3 can confidently classify (`confidence < 0.85`) falls back to the
same `test_gap` / `NONE` / `escalateToHuman: true` stub result Tier 4 will eventually replace.

## Tiers 2 & 3

**Tier 2 — signature cache** (`SignatureCache`): the signature is `sha256(normalize(testName) +
normalize(first exception-shaped line of logTail))`, with ports/ids/timestamps stripped so
near-identical failures across runs collapse to the same key. In-memory `Map`, optionally
backed by a JSON file (default `agentic/.cache/triage-cache.json`, gitignored) so entries
survive across CLI runs. `CascadeTriageEngine.signatureCache()` exposes `put(bundle, result)`
for future eval/golden tooling to seed confirmed verdicts — nothing calls it automatically yet.
A cache hit returns the prior verdict at confidence `0.95` with `"cache_hit: <signature>"`
appended to evidence.

**Tier 3 — golden retrieval** (`GoldenRetriever`): loads `golden/labeled-failures.jsonl`, then
for a given bundle scores every golden example by Jaccard similarity of whitespace/punctuation
tokens over `testName + logTail` (plain token overlap, no embeddings/vector DB) and takes the
top 3. If those 3 agree on `expected_category` and the best match is similar enough, Tier 3
short-circuits at confidence `0.9`; otherwise it's a low-confidence guess the cascade falls
through past. The retrieved examples are attached to `evidence` either way — the "context" a
future Tier 4 (LLM) would consume.
