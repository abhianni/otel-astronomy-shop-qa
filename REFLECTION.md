# Reflection

**Scope:** what this assignment actually surfaced, what's deliberately left open, and how a 4-QA pod would carry it forward.

---

## 1. Issues found (confirmed against the live stack, not just static reading)

| # | Issue | Severity | Evidence |
|---|---|---|---|
| 1 | **Unsupported currency code silently completes a $0.00 order.** `currency` looks up rates on a plain map with no existence check; an unknown `to_code` default-constructs a `0.0` rate. `checkout.PlaceOrder` with `user_currency: "XYZ"` doesn't reject — it ships a real order at `{units:0, nanos:0}`. | **Ship-blocker (P0)** | `test-cases/checkout-payment-backend.md` BE-06; encoded as `Xfail.expectFailure` in `automation/tests/backend/currency.spec.ts` / `CurrencyTest` until fixed |
| 2 | **Quantity boundaries misbehave.** Qty 0/-1 surfaces a misleading "email service" error instead of a validation error; `int32`-max quantity hangs/destabilizes checkout. | **Ship-blocker (P0)** | BE-04, `QuantityBoundaryTest` |
| 3 | **Partial-commit risk on the buy path.** `chargeCard` runs before `shipOrder`; a shipping failure after a successful charge leaves a paid, unshipped order. Email failure is logged-and-swallowed; cart-empty failure after ship is ignored (`_ = emptyUserCart`). Tests that only assert "order ID returned" would miss all of this. | **P0 design risk, not yet a confirmed live bug** | `test-strategy.md` §1 |
| 4 | **No independent way to audit the charged amount.** `ChargeResponse` doesn't return the amount — reconciliation depends on log-scraping `payment`'s stdout. | **Observability gap** | BE-03 |
| 5 | **Cart never freezes price.** No "price changed since you added this" signal; acceptable for a demo, but would need to be a deliberate, documented decision in a real commerce flow. | **Accepted risk** | BE-05 |

## 2. Known gaps — deliberately not covered this round

| Gap | Why it's open | What it would take |
|---|---|---|
| **Third-party payment integration test** | The demo's `payment` service is a mock charge — there is no real PSP (Stripe/Adyen/etc.) in this stack, so there's nothing live to integration-test against. Automation only asserts the mock's contract (amount/currency echoed, decline on invalid card). | If this stack were ever backed by a real gateway: sandbox-account contract tests for auth/capture/decline/webhook, plus a chaos case for gateway timeout distinct from the existing `paymentUnreachable`/`paymentFailure` flagd knobs. |
| **Load / performance test** | `test-strategy.md` §2 scopes a "smoke baseline under load-generator / k6-lite on checkout latency & error rate" as a v1 intent, but no such suite exists yet in `automation/` — this assignment stayed API-functional, not load. | A small k6 (or reuse of the demo's own `load-generator`) script hitting checkout end-to-end, gated on p95 latency + error-rate regression only — no capacity model, trend-only per the strategy doc. |

Both are named explicitly here rather than silently dropped, per the strategy doc's own principle: automate decision-critical paths, and call out — don't hide — what's left to manual/exploratory or a later iteration.

## 3. Ownership going forward

No change from the pod model already decided in `test-strategy.md` §3 — this just restates it as the actionable split:

| Squad | Owns | QAs |
|---|---|---|
| **Commerce** | Buy path core: checkout, cart, payment — includes the BE-04 quantity-boundary issue above | **2** |
| **Catalog & experience** | Product listing/catalog, recommendation, frontend — browse/discover, contract + smoke only | **1** |
| **Platform** | Env readiness, flagd, load-generator, kafka, otel-collector, valkey/postgres, flake quarantine — **plus shipping, currency, and post-order flow (ship → empty cart → email → Kafka)**, since those terminate in the same Postgres/Kafka paths this squad already tests and understands end-to-end | **1** |

Default assignee for the two confirmed P0 issues above: **BE-04** (quantity boundaries) is Commerce's, since it sits in `checkout`. **BE-06** (unsupported currency) moves to **Platform** along with the rest of `currency` ownership. One Commerce QA rotates weekly onto Platform for release week (`test-strategy.md` §3's floater rule) — that's also who'd pick up the load-test buildout in §2 once it's prioritized.
