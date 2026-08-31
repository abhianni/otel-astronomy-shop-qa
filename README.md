# OpenTelemetry Astronomy Shop — QA EM Assignment

Quality strategy and artifacts for the [OpenTelemetry Demo](https://github.com/open-telemetry/opentelemetry-demo) (Astronomy Shop).

## Deliverables (this repo)

| Section | Status |
|---|---|
| [test-strategy.md](./test-strategy.md) | Done |
| [test-cases/](./test-cases/) | Done (checkout + cart UI, checkout/payment backend) |
| [automation/](./automation/) | Done — Java/gRPC + HTTP smoke (`./gradlew test`) |
| [automation-strategy.md](./automation-strategy.md) | Done |
| [agentic/](./agentic/) | Design + schemas/CLI + Tiers 1-3 (rules, cache, golden retrieval) done — Tier 4 (LLM) + CLI transcript pending |
| [REFLECTION.md](./REFLECTION.md) | Attached link |

## Target system

```bash
git clone --depth=1 https://github.com/open-telemetry/opentelemetry-demo
cd opentelemetry-demo
docker compose up -d
# Frontend: http://localhost:8080
```

## Run

Single root Gradle multi-project build (`automation` + `agentic`):

```bash
./gradlew test              # both modules
./gradlew :automation:test  # buy-path suite only (needs the demo stack running)
./gradlew :agentic:test     # CI triage agent only (no demo stack needed)
```

See [automation/README.md](./automation/README.md) for env overrides, coverage matrix, and what
stays manual, and [agentic/README.md](./agentic/README.md) for the triage agent's CLI usage.

## Approach (short)

Protect the **buy path** (cart → checkout → payment → shipping). Thin PR gates, deep nightly P0, domain-based ownership for a 4-QA pod — not 1:1 service ownership. Automation is **API-first** (gRPC + frontend HTTP); UI browser E2E and chaos stay deliberate gaps.
