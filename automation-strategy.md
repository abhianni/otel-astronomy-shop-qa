# Automation strategy

How we automate quality for Astronomy Shop with a **team of 4**, not just this assignment repo.

---

## 1. Why these tools

| Choice | Rationale |
|---|---|
| **Java 17 + JUnit 5** | Same ecosystem as many commerce backends; strong typing for money/`Money` fields; one `./gradlew test` for reviewers. |
| **gRPC stubs from `demo.proto`** | Checkout/cart/payment/currency speak gRPC. Hitting stubs exercises the real contracts without browser flakiness. |
| **JDK `HttpClient` + Gson for `:8080` smoke** | Covers the path the Next.js frontend uses (`/api/cart`, `/api/checkout`) without adding Playwright/Node to the critical path. |
| **`docker port` discovery** | Compose publishes random host ports for backends; hardcoding `.env` ports breaks CI and local setups. |
| **No Playwright in v1** | UI E2E earns its keep after API P0 is green. One flaky selector suite would poison the merge gate. |

**Rejected for v1:** Rest Assured-only (weaker for gRPC), pure Cypress fork of demo tests (UI-bound), full Testcontainers rewrite of 20 services (reviewers already run Compose).

---

## 2. Structure for a team of 4

Mirror the pod model in `test-strategy.md` — packages own **domains**, not “all tests in one class.”

```
automation/
  src/test/java/
    commerce/          # 2 QAs — cart, checkout, payment, currency, shipping contracts
    catalog/           # 1 QA — product-catalog contracts + browse HTTP smoke
    platform/          # 1 QA — health checks, flagd fixtures, flake quarantine helpers
    support/           # shared clients, fixtures, Xfail, port discovery
```

**Ownership rules**

| Role | Owns | Does not own |
|---|---|---|
| Commerce QA | P0 buy-path automation, nightly reds on checkout | Every unit test inside Go/Java/.NET services |
| Catalog QA | Product list/get contracts, currency display smoke | Payment decline matrix |
| Platform QA | CI workflow, artifact upload, env readiness job, quarantine process | Feature assertions for commerce |
| All | Tag tests `@Smoke` / `@Nightly` / `@Chaos` | Untagged “misc” dumping ground |

Repo layout for day-to-day:

- `automation/` — runnable suite (this assignment)
- `test-cases/` — manual + exploratory source of truth
- Failures export JSON → future `agentic/` triage (category + owner squad)

---

## 3. Where tests run in CI/CD

| Trigger | Suite | Blocking? | Artifacts |
|---|---|---|---|
| **Pull request** (paths: `src/checkout/**`, `src/cart/**`, `src/payment/**`, `automation/**`) | `@Smoke`: happy checkout, empty cart, invalid card, cart merge, HTTP products | Yes — merge | JUnit XML, failing test stdout |
| **Nightly schedule** | Full suite including boundary + currency + xfail trackers | Yes — release candidate | JUnit + container logs snippet on fail |
| **Pre-release / tag** | Nightly + **manual** CHK-04/05 chaos checklist | Yes — prod | Sign-off note in release ticket |
| **Workflow_dispatch** | Full or tagged subset | No | Same as nightly |

Conceptual GitHub Actions shape (implement when this repo is public):

```yaml
on:
  pull_request:
  schedule:
    - cron: "0 2 * * *"
jobs:
  buy-path:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Start Astronomy Shop
        run: |
          git clone --depth=1 https://github.com/open-telemetry/opentelemetry-demo demo
          cd demo && docker compose up -d
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - name: Wait for frontend
        run: |
          for i in $(seq 1 60); do curl -sf http://localhost:8080 >/dev/null && exit 0; sleep 5; done; exit 1
      - name: Run automation
        working-directory: automation
        run: ./gradlew test
      - uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: test-reports
          path: automation/build/reports/tests/test/
```

**How failures surface**

1. Job red → JUnit report in Actions UI  
2. Artifact zip for local open of HTML report  
3. (Month-2) failure JSON bundle → triage agent → label `flake` / `product_bug` / `environment` / `test_gap` → assign Commerce or Platform  
4. Human still owns quarantine and merge policy

---

## 4. Flakiness policy

| Rule | Detail |
|---|---|
| Retries | **Max 1** retry in CI; both attempts logged |
| Quarantine | `@Disabled("quarantine: <ticket>")` or tag excluded from merge gate within 24h of confirmed flake |
| No silent loops | Never `while (fail) retry` in test code |
| Attribution | Prefer gRPC assertions over UI waits; HTTP smoke uses short connect timeout + `Assumptions` if proxy down |
| Chaos tests | Never on PR gate; nightly or manual only |
| Xfail ≠ flake | Known product bugs use `Xfail.expectFailure` — they **pass** while the bug lives and **fail loudly** when fixed |

Flake rate target: **&lt;5%** of suite; if nightly stability &lt;90% for 7 days, freeze new tests and burn down quarantine.

---

## 5. Test data

| Concern | Approach |
|---|---|
| Users | Fresh `userId` per test (`UUID`) — no shared carts |
| Catalog | Read live `ListProducts` — no hardcoded product IDs (IDs can drift across demo versions) |
| Cards | Demo defaults only: valid `4432-8015-6152-0454`; invalid/expired fixtures in `GrpcClients` |
| Money | Assert contracts (unit price invariant, currency code, rejection) — avoid brittle absolute totals unless computed from live prices |
| Secrets | None — no real PSPs, no API keys in repo |
| Seed | Rely on demo image seed catalog; document if a fork removes products |
| Isolation | Do not depend on load-generator traffic; tests create their own cart state |

---

## 6. What we automate next (post-assignment)

1. Tag split (`@Smoke` / `@Nightly`) wired into Actions  
2. One Playwright happy-path **after** API suite is stable in CI  
3. flagd fixture pack for CHK-04 under `@Chaos`  
4. Feed failure bundles into CI triage agent  

Until then: **API-first buy path, thin HTTP smoke, manual chaos** — boring on purpose.
