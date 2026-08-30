# Test cases index

Manual cases for the two highest-risk Astronomy Shop flows.

| File | Flow | Cases | Focus |
|---|---|---|---|
| [checkout-flow.md](./checkout-flow.md) | Checkout → payment → shipping (UI) | CHK-01 … CHK-06 | Money path, partial failure, flags, currency (browser-level) |
| [cart-flow.md](./cart-flow.md) | Cart persistence (UI) | CART-01 … CART-05 | Session, merge qty, Valkey, concurrency (browser-level) |
| [checkout-payment-backend.md](./checkout-payment-backend.md) | Checkout/payment backend (API/gRPC) | BE-01 … BE-06 | Pricing math, quantity boundaries, currency precision, charge reconciliation — bypasses the UI entirely |

**Total:** 17 cases (prefer depth over a long thin suite).

**How we use these with the team**

- P0 cases are release-blocking when they fail on a candidate build.
- Resilience cases (flags, Valkey down, shipping fault) run in pre-release or dedicated chaos windows, not necessarily every PR.
- Each case ends with **pre-ship quality risks** — things to escalate even if the happy path is green.
