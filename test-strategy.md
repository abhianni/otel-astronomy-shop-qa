# Test Strategy — OpenTelemetry Astronomy Shop

**System:** ~20 polyglot microservices (Go, Java, .NET, Node, Python, …), gRPC + REST, Docker Compose.  
**Audience:** QA EM owning quality for a pod of 4 QAs.  
**Bias:** Protect the buy path. Everything else earns its automation budget.

---

## 1. Where quality risk concentrates

Risk is not evenly spread across 20 services. It concentrates where **money, state, and fan-out** meet.

### P0 — Checkout → Payment → Shipping

`frontend` → `checkout` (`PlaceOrder`) fans out to **cart → product-catalog → shipping quote → currency → payment → ship → empty cart → email → Kafka**.

Priority here is P0 regardless of owner. Execution splits across two squads (§3): Commerce owns `checkout`/`cart`/`payment`; Platform owns `shipping`/`currency`/the post-order tail (`ship → empty cart → email → Kafka`), since that tail is where Postgres/Kafka live and Platform already has that context.

| Why it hurts | Evidence in this system |
|---|---|
| Money path | `chargeCard` runs before `shipOrder`. A shipping failure after a successful charge is a partial commit. |
| Multi-hop failure modes | One `PlaceOrder` spans gRPC (cart, catalog, currency, payment) and HTTP (shipping, email). Timeouts and mixed status codes are normal. |
| Silent degradation | Email failure is logged and swallowed; cart empty failures are ignored after ship (`_ = emptyUserCart`). Tests that only assert “order ID returned” miss these. |
| Chaos knobs | Feature flags (`paymentUnreachable`, `paymentFailure`, catalog failure) make intermittent production-like faults cheap to trigger and easy to mis-triage as flakes. |

### P0 — Cart persistence

Cart is **session-scoped state in Valkey** (.NET). Wrong quantity, lost session, or Valkey blips break checkout before payment starts.

| Why it hurts | Evidence |
|---|---|
| State correctness | Re-adding the same `product_id` **increments** quantity; easy to under-test. |
| TTL / session | Keys expire in **60 minutes** — stale-session bugs look like “empty cart” bugs. |
| Dependency | Valkey down → `FailedPrecondition` on add/get/empty; UI must fail loudly, not silently clear. |

### P1 — Product catalog (supporting)

Catalog wrongness poisons checkout totals and recommendations. Treat as **contract + smoke**, not a full E2E matrix.

### Deliberately lower priority for deep QA investment

Ads, chatbot, image-provider, react-native-app, most observability sidecars: high surface, low revenue impact. Platform squad owns **health**, not feature depth.

```
Browse ──▶ Cart (Valkey) ──▶ Checkout orchestrator
                                 │
                    ┌────────────┼────────────┐
                    ▼            ▼            ▼
               Catalog      Currency     Shipping quote
                    └────────────┬────────────┘
                                 ▼
                              Payment  ──▶ Ship ──▶ Empty cart
                                              │
                                         Email / Kafka (best-effort)
```

---

## 2. Test pyramid — what we automate, what we don’t

| Layer | What | Owner | Intent |
|---|---|---|---|
| **Unit** | Service logic (money math, card validation, cart merge) | Dev (per service) | Fast feedback inside the language of each service. QA does not own this suite. |
| **Contract** | Proto / OpenAPI shapes for checkout, cart, payment, catalog | Dev + QA Commerce | Catch breaking field renames and status semantics before UI E2E. |
| **Integration** | Cart↔checkout, checkout↔payment, checkout↔shipping (API/gRPC) | QA Commerce | Prove orchestration and partial-failure handling without a browser. |
| **E2E** | **2–3** P0 UI paths only (happy checkout; declined/invalid card; cart add→checkout) | QA | Confidence that the user path still wires. Keep the suite tiny. |
| **Performance** | Smoke baseline under load-generator / k6-lite on checkout latency & error rate | Platform + Commerce | No capacity model in v1 — trend and gate on regressions only. |

### Deliberately leave unautomated

- Visual polish, marketing copy, ad creative
- Exhaustive 20-service combinatorial matrix
- Every feature-flag × currency × card-type permutation (sample, don’t enumerate)
- Email content rendering and Kafka consumer correctness beyond “message produced / consumed once”
- Exploratory chaos beyond a fixed flagd scenario pack

**Principle:** Automate *decision-critical* paths. Manual/exploratory covers *ambiguity* and *rare combinations*. Coverage % of endpoints is a vanity metric here.

---

## 3. QA ownership in a 4-person pod × ~20 services

Do **not** assign one QA per service. Assign **domains**; services are implementation details.

| Squad | Domain | Services (examples) | QA charter |
|---|---|---|---|
| **Commerce** (2 QAs) | Buy path core | checkout, payment, cart | P0 automation, release sign-off on payment path, triage queue for checkout failures |
| **Catalog & experience** (1 QA) | Browse / discover | product-catalog, recommendation, frontend, frontend-proxy | Contract tests, browse smoke, UX regressions that block add-to-cart |
| **Platform** (1 QA) | Env, signal & post-order integration | flagd, load-generator, kafka, otel-collector, valkey/postgres, **shipping, currency, post-order flow (ship → empty cart → email → Kafka)** | Env readiness gates, flake detection, observability of *test* health, **plus shipping/currency/post-order tests — these terminate in the same Postgres/Kafka paths this squad already instruments, so the owner who understands that data flow tests it end-to-end instead of splitting it across squads** |

**Floater rule:** One Commerce QA rotates weekly onto Platform for release week. Cross-cutting services (ad, fraud-detection) get **regression pack ownership**, not full-time owners.

**Definition of ownership:** write/maintain automation for the domain, own nightly reds for that domain, and be the default assignee for escaped P0/P1 in that domain. Not “you wrote every unit test.”

---

## 4. Quality gates in CI/CD

| Gate | When | Must pass | Blocks |
|---|---|---|---|
| **PR smoke** | Every PR touching app services | Contract checks for changed services + **1** E2E happy-path checkout | Merge |
| **Nightly P0** | Schedule | Full P0 API + E2E pack; upload logs/JUnit for triage | Release candidate promotion |
| **Pre-release** | Tag / release train | Nightly green + **manual** payment/shipping partial-failure checklist | Prod deploy |
| **Post-failure** | Any red CI | Triage classification (flake / product / env / test gap) → owner | Does not auto-merge fixes |

PR gate stays thin on purpose: a fat PR suite teaches people to ignore red. Depth lives in nightly; judgment lives in pre-release.

---

## 5. Metrics that change behavior

| Metric | Definition | Target / use |
|---|---|---|
| **Defect leakage** | Prod bugs ÷ (prod + pre-prod bugs) for Commerce domain | Trend down quarter-over-quarter; investigate spikes by squad |
| **Escaped defects (checkout)** | P0/P1 buy-path bugs found in prod | Zero P0 per release; any P0 triggers RCA within 48h |
| **Automation coverage** | Automated **P0 flows** ÷ defined P0 flows (not line/% endpoint coverage) | ≥80% of listed P0 flows; publish the list next to the number |
| **Regression stability** | % green nightly runs (7-day rolling) | ≥90%; below that, freeze new tests and fix flakes |
| **Flake rate** | Tests quarantined or retry-passing ÷ suite size | &lt;5%; quarantine &gt; merge-gate pollution |
| **CI MTTR** | Time from red nightly → classified + assigned | &lt;30 min median (agent-assisted triage is the lever) |

What we **do not** optimize: raw test count, UI coverage of every page, or 100% proto method automation.

---

## 6. Near-term quality levers (priority order)

1. **CI failure triage** — cut MTTR on nightly reds (highest leverage for a 4-person pod).  
2. **Contract + integration on checkout/cart** — catch money-path breaks before E2E flakes hide them.  
3. **Tiny E2E pack** — prove the user still can buy.  

Test generation and self-healing are later; neither beats a stable gate and a clear owner map.

---

*Document length target: ≤3 pages. Last aligned to Astronomy Shop checkout/cart/payment behavior in the OpenTelemetry demo.*
