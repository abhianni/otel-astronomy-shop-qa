# Manual test cases — Checkout/payment backend (pricing, quantity, currency) (P0)

**System:** OpenTelemetry Astronomy Shop — service layer, **bypassing the browser/frontend**.
**Services under test:** `cart` (`:7070`), `checkout` (`:5050`), `currency` (`:7001`), `payment` (`:50051`), `product-catalog`.
**Why this flow:** `checkout-flow.md` and `cart-flow.md` only exercise these services through the UI (session, redirect, display). They don't prove the money math itself — quantity × price, currency conversion precision, and whether the amount charged actually matches the computed order total. That logic lives entirely server-side and needs to be hit directly.

**Tooling:** call RPCs directly with `grpcurl -plaintext -proto pb/demo.proto localhost:<port> oteldemo.<Service>/<Method> -d '<json>'` against the cloned `opentelemetry-demo` repo's proto file. No UI involved.

**Shared preconditions**

- Core stack up (`docker compose up`); use a throwaway `user_id` per case (e.g. `be-test-01`) so cases don't share cart state.
- Note product IDs/prices from `product-catalog` (`GetProduct`) before each case so expected totals can be computed independently, not eyeballed.

---

## BE-01 — Order total = Σ(unit price × quantity) per line + shipping

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Functional / money math |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | `AddItem` product A qty **3**, product B qty **1** for the same `user_id`. | Both accepted. |
| 2 | Note each product's `price_usd` from `product-catalog.GetProduct`. | Baseline unit prices recorded. |
| 3 | `PlaceOrder` for that `user_id`, `user_currency=USD`, valid card + address. | Succeeds; `PlaceOrderResponse.order.items` has 2 `OrderItem`s. |
| 4 | Inspect `items[].cost`. | **This is the unit price, not the extended line total** — `checkout/main.go:549-552` sets `Cost` from `convertCurrency(product price)` before quantity is applied; multiplication only happens later when summing the charge total (`main.go:351-354`). |

### Edge cases

- Same product added twice (server merges quantity in `cart`) vs. two distinct products each qty 1 — both must still price correctly per line.
- Quantities that differ per line (not just "cart has 2 items") — a bug that only multiplies the first line or a flat per-order price won't show up with qty=1 everywhere.

### Pre-ship quality risk

Any downstream consumer (invoice/report/analytics) that reads `OrderItem.cost` and assumes it's the line total will under-report for any `quantity > 1`. Document that `cost` is **unit price**; if a "line total" field is ever added, it must be `cost × quantity`, computed the same way `checkout` computes its charge total.

---

## BE-02 — Currency conversion precision (units/nanos), item price and shipping

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Functional / precision |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | `currency.Convert` a known USD `Money` (e.g. `units:15, nanos:990000000` = $15.99) to `to_code="EUR"`. | Conversion uses the service's fixed rate table (`src/currency/src/server.cpp:57-73`, e.g. `USD: 1.1305`, `EUR: 1.0` anchor). Independently compute expected EUR value and compare — don't just eyeball the UI. |
| 2 | Repeat conversion to a high-multiplier currency (e.g. `JPY: 126.40`). | `nanos` stays within `-999,999,999..999,999,999`; no overflow/precision loss when the multiplier is large. |
| 3 | `PlaceOrder` with `user_currency=EUR` for a cart with a fractional-cent item. | `OrderResult.shippingCost` **and** each `item.cost` are individually convertible/verifiable against direct `Convert` calls for the same inputs — not just "some EUR number showed up." |

### Pre-ship quality risk

Exchange rates are a hardcoded static table, not a live feed (acceptable for a demo, but must be called out if this pattern is ever reused for something real). Nanos boundary handling on high-multiplier currencies is untested — verify explicitly rather than assuming the conversion math handles it.

---

## BE-03 — Payment charge amount reconciles with checkout's computed total

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Money integrity |
| **Preconditions** | Access to `payment` container logs (`docker compose logs -f payment`). |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Add known items (fixed qty, fixed prices) to cart. | — |
| 2 | Independently compute expected total = Σ(unit price × qty) + shipping, per `checkout/main.go:345-354`. | — |
| 3 | `PlaceOrder`; simultaneously tail `payment` logs for the `"Transaction complete."` line. | Log includes `amount: {units, nanos, currencyCode}` (`src/payment/charge.js:99-100`) — compare to your independently computed total. |
| 4 | Repeat with `user_currency` != USD in the mix. | Logged amount reflects the **converted** total, not raw USD. |

### Pre-ship quality risk

**There is no way to verify the charged amount from the API alone.** `ChargeResponse` only returns a `transaction_id` (`pb/demo.proto:193`), and the payment span only sets a boolean `demo.payment.charged` attribute — the amount never reaches a span attribute or the response object (`src/payment/charge.js:89-91`). If this app log line is ever removed or its format changes, there is **zero remaining signal** that checkout charged the right amount. Flag this as an observability/testability gap: recommend the charged amount be added to a span attribute (already have `demo.order.amount` on the checkout side, `main.go:392`) so reconciliation doesn't depend on parsing an app log line.

---

## BE-04 — Quantity boundary handling at the API layer

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Negative / boundary — **bypasses any UI-side clamping** |
| **Note** | Static reading of the locally cloned `checkout` source predicted a different bug than what the running `latest` image actually does (version skew between the pinned demo image and this repo's `HEAD`). Findings below are from running `automation/tests/backend/quantity-boundary.spec.ts` against the live stack — **verify empirically, don't ship a finding on source reading alone.** |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | `AddItem` with `quantity: 0`, then `PlaceOrder`. | Rejected — correct, matches the empty-cart contract (CHK-02). |
| 2 | `AddItem` with `quantity: -1`, then `PlaceOrder`. | Also rejected, same as qty 0. |
| 3 | Inspect the rejection error text for cases 1–2. | See message-integrity risk below. |
| 4 | `AddItem` with `quantity: 2147483647` (int32 max), then `PlaceOrder` with an 8s deadline. | See stability risk below. |

### Pre-ship quality risk — confirmed against the running stack

- **qty = 0 / qty = -1**: `PlaceOrder` is correctly rejected (good), but the error text reads `"shipping quote failure: failed POST to email service: expected 200, got 400"` — blaming the email service for what's actually a shipping/quantity problem (shipping's real dependency is a separate `quote` HTTP service, confirmed via its own logs, which never mention email). Whoever triages this on a real error budget gets sent to the wrong team. `cart.AddItemAsync` (`src/cart/src/services/CartService.cs`) still has no explicit quantity validation — the rejection is coming from somewhere downstream, not intentional input validation.
- **qty = 2147483647 (int32 max)**: does **not** fail fast. The call hangs until the client-side deadline, and during this investigation the `checkout` container was observed to restart mid-call (`docker inspect checkout` showed an incremented `RestartCount`, and the client saw `Connection dropped`), while `shipping`'s own logs logged a quote of `dollars=19305877986` (~$19.3B) for the same request instead of a rejection. **A single crafted quantity value can destabilize the checkout service** — this is the most severe finding in this set and should ship-block regardless of the exact root cause line.

Add quantity validation (`> 0`, reasonable upper bound) in `cart` before it ever reaches checkout's pricing/shipping-quote path, and fix the error-wrapping that misattributes shipping failures to the email service.

---

## BE-05 — Cart is price-blind; checkout always re-resolves price at charge time

| Field | Detail |
|---|---|
| **Priority** | P1 |
| **Type** | Design/consistency verification |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Inspect `Cart.items[]` shape returned by `GetCart`. | `CartItem` only has `product_id` and `quantity` (`pb/demo.proto:29-32`) — **no price field**. Cart never freezes a price. |
| 2 | Confirm `checkout.prepOrderItems` (`main.go:537-555`) calls `product-catalog.GetProduct` fresh for every line, every `PlaceOrder` call — it never reads a cached/cart-time price. | Price is always resolved at charge time, not add-to-cart time. |
| 3 | If product-catalog data can be edited/restarted between "add to cart" and "place order" in your environment, re-run the same cart's `PlaceOrder` before/after the change and diff `item.cost`. | Charged price reflects whatever `product-catalog` returns **now**, not what was shown when the item was added. |

### Pre-ship quality risk

By design, there's no price-versioning or "price changed since you added this" signal to the user. Acceptable for a demo; call it out explicitly if this pattern is ever reused for a real commerce flow — a customer could see one price while browsing and be charged a different one with zero indication.

---

## BE-06 — Unsupported/malformed currency code must fail closed

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Negative / data integrity |
| **Note** | Static reading of `src/currency/src/server.cpp` predicted a division-by-zero/garbage-`Money` bug. Confirmed against the live stack (`automation/tests/backend/currency.spec.ts`) this hypothesis was **wrong in a worse direction** — see below. |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | `currency.Convert` with `to_code: "XYZ"` (not in the service's rate table) for a valid `from` Money. | Should fail with a clear error (e.g. `INVALID_ARGUMENT`). |
| 2 | Repeat with `to_code: ""` and a typo'd code (`"EURO"`). | Same — must fail closed. |
| 3 | Repeat via `checkout.PlaceOrder` with `user_currency` set to the same bad values, for a cart with a real item. | `checkout` should reject the order, not complete it. |

### Pre-ship quality risk — confirmed against the running stack, more severe than originally hypothesized

`src/currency/src/server.cpp` looks up rates with `currency_conversion[from_code]` / `currency_conversion[to_code]` on a plain `unordered_map` — **no existence check**, so an unknown code silently default-constructs a rate of `0.0`. Live testing (`Convert({from: USD $10, toCode: "XYZ"})`) showed this rate is used as a **multiplier**, not a divisor — the result is a clean `{currencyCode:"XYZ", units:0, nanos:0}`, not `inf`/`nan` garbage as originally predicted from the source alone.

**That clean zero is what makes this worse, not better.** Calling `checkout.PlaceOrder` directly with `user_currency: "XYZ"` for a cart holding a real item (`0PUK6V6EV0`, qty 1) **silently succeeds** — a real order is placed and fulfilled (an `order_id` and `shipping_tracking_id` are issued), with both `item.cost` and `shippingCost` at `{units:0, nanos:0}` in the fake currency:

```json
{"order":{"items":[{"item":{"productId":"0PUK6V6EV0","quantity":1},"cost":{"currencyCode":"XYZ","units":0,"nanos":0}}],"orderId":"e4455f54-a458-11f1-91b4-0242ac140011","shippingTrackingId":"6466c409-9e69-4417-9496-327ca41cc57f","shippingCost":{"currencyCode":"XYZ","units":0,"nanos":0},"shippingAddress":{"streetAddress":"1600 Amphitheatre Parkway","city":"Mountain View","state":"CA","country":"US","zipCode":"94043"}}}
```

This is a **free order** reachable with a single crafted field on a standard `PlaceOrder` call — no special access needed. It is a more severe, more directly exploitable ship-blocker than a garbage-money crash would have been: a crash fails loudly, this fails silently and fulfills the order.

---

## Backend pricing — ship checklist (quality risks to flag)

1. BE-06 (unsupported currency silently completes a $0.00 order) and BE-04 (quantity 0/-1 misleading error; int32-max hangs/restarts checkout) are both **confirmed live**, not speculation — both are ship-blockers.
2. `OrderItem.cost` is unit price, not line total — document this contract so nothing downstream double-counts or under-counts (BE-01).
3. No independent way to audit the charged amount from the API/response alone (BE-03) — track as an observability gap, not just a test gap.
4. Cart never freezes price (BE-05) — acceptable for this demo, but must be a documented, deliberate decision if reused elsewhere.
