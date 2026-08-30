# Manual test cases — Checkout flow (P0)

**System:** OpenTelemetry Astronomy Shop (`http://localhost:8080`)  
**Services under test:** `frontend` → `checkout` → `cart`, `product-catalog`, `currency`, `shipping`, `payment` (+ `email` best-effort)  
**Why this flow:** Money path with multi-service orchestration and known partial-failure modes (charge before ship; email/cart-empty non-fatal).

**Shared preconditions (unless overridden)**

- Stack healthy: `docker compose up` (or equivalent); frontend reachable.
- Fresh browser session (or clear site data) so cart/`userId` session is clean.
- Currency left at default **USD** unless the case says otherwise.
- Valid default card on the cart form: `4432-8015-6152-0454`, CVV `672`, expiry in the future (form defaults to Jan 2030).

---

## CHK-01 — Happy path: place order with two items

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | End-to-end / functional |
| **Preconditions** | Shared preconditions. Catalog has ≥2 products. |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Open homepage. Click first product. Add to cart. | Redirect/navigate to cart; cart count = **1**. |
| 2 | Return home. Click a different product. Add to cart. | Cart count = **2**; both line items visible with correct names/prices. |
| 3 | On cart page, confirm shipping/payment form defaults populated. Click **Place Order**. | Request succeeds; navigate to order confirmation (`/cart/checkout/{orderId}`). |
| 4 | Inspect confirmation. | Order ID present; **2** checkout items; shipping tracking id present; totals include items + shipping. |
| 5 | Open cart again (same session). | Cart is **empty** (post-success empty-cart ran). |

### Edge cases to probe while here

- Change currency to **EUR** before place order → confirmation amounts in EUR; no USD leftover on the confirmation screen.
- Place order twice quickly (double-submit) → no duplicate charge UX; second attempt should not create a silent second success for the same cart (cart already empty).

### Pre-ship quality risks

- Confirmation shows success while cart still contains items → empty-cart failure swallowed after ship.
- Totals ignore shipping or show wrong currency after currency switch.
- Order ID missing / navigation fails but backend charged (orphan charge).

---

## CHK-02 — Empty cart cannot place an order

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Negative |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Ensure cart is empty (Empty Cart or fresh session). | Cart shows empty state. |
| 2 | Attempt to place order (if UI allows) or call checkout API with session `userId` and empty cart. | Order is **rejected**; clear user-facing error; **no** confirmation page with a fake order id. |
| 3 | Check payment / order side effects (logs or UI). | No transaction / no shipping tracking created for this attempt. |

### Edge cases

- Empty cart after removing last item vs never-added — same rejection.
- Stale confirmation URL from a prior order must not be treatable as a new successful checkout.

### Pre-ship quality risks

- UI hides Place Order but API still accepts empty cart and returns Internal with opaque error.
- Charge attempted with zero/empty line items.

---

## CHK-03 — Invalid credit card rejected before “success”

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Negative / payment |
| **Preconditions** | Cart has ≥1 valid catalog item. |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Add one product to cart. | Cart count = 1. |
| 2 | Set card number to an invalid value (e.g. `1234-5678-9012-3456`). Place order. | Checkout fails; user sees an error (not a blank hang). No confirmation page. |
| 3 | Re-open cart. | Item **still in cart** (charge did not succeed; cart should not have been emptied). |

### Edge cases

- Card type not Visa/Mastercard (e.g. Amex test number if accepted by validator) → explicit “only VISA or MasterCard” style failure.
- Expired card (month/year in the past) → expiry error; cart retained.
- Valid Luhn but wrong type vs invalid Luhn — both must fail closed.

### Pre-ship quality risks

- Payment throws; checkout maps everything to generic `Internal` with no actionable UI message.
- Cart emptied even though charge failed (ordering bug).
- Feature flag `paymentFailure` left on in shared env → intermittent “invalid token” errors misread as card-validation bugs.

---

## CHK-04 — Payment unreachable / charge failure (flagd)

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Resilience |
| **Preconditions** | Access to flagd UI / flags: enable `paymentUnreachable` **or** set `paymentFailure` to a high probability for a controlled window. Document which flag you used. |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Enable failure flag. Add item; place order with **valid** default card. | Order fails; user-visible error. |
| 2 | Confirm cart state. | Items remain in cart. |
| 3 | Disable flag. Retry place order with same cart. | Order succeeds; confirmation shown; cart empties. |

### Edge cases

- Flag flipped mid-checkout under load-generator — error rate should track flag, not flap randomly after disable.
- Distinguish **unreachable payment** (connection/bad address) vs **business decline** (invalid card) in logs/traces for triage.

### Pre-ship quality risks

- After payment outage, successful retry double-charges or creates duplicate orders without clearing first attempt state.
- UI shows success toast while gRPC failed (frontend error handling gap).
- Flag left enabled in CI → nightly “flake” that is actually env config.

---

## CHK-05 — Shipping failure after successful payment (partial commit)

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Resilience / consistency |
| **Note** | Hardest case. Prefer controlled fault (shipping container pause/kill, or network deny to shipping) during Place Order **after** you can confirm charge would succeed. If fault injection is unavailable, mark as **exploratory + code-review gate** and still ship-block on unclear handling. |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Cart with ≥1 item; valid card. Inject shipping failure for this attempt. | Checkout returns failure (e.g. Unavailable / user error). **No** success confirmation. |
| 2 | Observe payment outcome (logs/traces: `demo.payment.transaction.id` / charge span). | Document whether charge already committed. |
| 3 | Observe cart. | Cart should still contain items if order did not complete. |
| 4 | Restore shipping; place order again. | Exactly one successful customer-visible order for the intended purchase; no unexplained duplicate charge. |

### Edge cases

- Shipping quote (`/get-quote`) fails vs ship (`/ship-order`) fails — both must fail closed before confirmation.
- Email down while shipping up — order may still succeed (email is best-effort); confirmation should still appear; flag email as non-blocking only if product accepts that.

### Pre-ship quality risks

- **Charged + no tracking + cart retained** with no compensation path — highest money risk in this demo’s ordering (`charge` then `ship`).
- Success page rendered from client state even when ship RPC failed.
- Ops has no runbook for “payment span OK, checkout error Unavailable”.

---

## CHK-06 — Currency conversion on checkout totals

| Field | Detail |
|---|---|
| **Priority** | P1 |
| **Type** | Functional |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Add item; note USD line price. | Prices visible in USD. |
| 2 | Switch currency to another supported code (e.g. EUR). | Cart/catalog prices update; no mixed currencies on one screen. |
| 3 | Place order. | Confirmation `user_currency` / amounts match selected currency; shipping cost also converted. |

### Edge cases

- Unsupported / typo currency via API → clear failure, no charge.
- Switch currency after form fill but before submit — last selected currency wins consistently in request payload.

### Pre-ship quality risks

- Item prices converted but shipping left in USD (or nanos/units formatting bugs → `$0.00` / wrong decimals).
- Frontend shows EUR while `PlaceOrderRequest.user_currency` still sends USD.

---

## Checkout — ship checklist (quality risks to flag)

Before calling the buy path “done”:

1. Partial failure after charge is understood and either prevented, compensated, or explicitly accepted with monitoring.
2. Failed payments never empty the cart.
3. Empty cart cannot produce an order id.
4. Feature flags used in demos are reset in shared QA/CI environments.
5. Confirmation page data comes from server order result, not only client optimistic state.
