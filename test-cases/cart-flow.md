# Manual test cases — Cart flow (P0)

**System:** OpenTelemetry Astronomy Shop (`http://localhost:8080`)  
**Services under test:** `frontend` → `cart` (Valkey-backed) → used by `checkout`  
**Why this flow:** Cart is the source of truth for `PlaceOrder`. Quantity merge, session TTL, and Valkey failures show up as “checkout is broken” if under-tested.

**Shared preconditions**

- Stack healthy; frontend at `:8080`.
- Fresh session unless the case needs a stale one.
- Use distinct products when the case says “two items”.

---

## CART-01 — Add item and persist across navigation

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Functional |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Open a product PDP. Set quantity if UI allows; Add to cart. | Land on cart (or cart updates); count reflects added qty. |
| 2 | Navigate to home, then another product page, then open cart icon. | Same item and quantity still present (session cart persisted). |
| 3 | Refresh the cart page. | Cart contents unchanged after refresh. |

### Edge cases

- Add quantity `1` twice for the **same** product → quantity becomes **2** (server merges), not two line rows.
- Add two different products → two lines; badge count = sum of quantities.

### Pre-ship quality risks

- Frontend shows badge increment but `GET /api/cart` returns empty (optimistic UI only).
- Refresh creates a new session `userId` and “loses” the cart — looks like a cart bug, is a session bug.

---

## CART-02 — Update quantity and remove / empty cart

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Functional |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Add product A (qty 1) and product B (qty 1). | Two lines; count = 2. |
| 2 | Increase quantity of A (UI control or re-add). | Line A quantity updates; totals update; B unchanged. |
| 3 | Remove B or set B to 0 if supported. | Only A remains; badge updates. |
| 4 | Click **Empty Cart**. | Cart empty; badge 0; checkout form should not complete an order. |

### Edge cases

- Empty cart on already-empty cart — idempotent success, no error spam.
- Extremely large quantity (e.g. 9999) — either capped with validation or accepted with explicit total; must not overflow UI/money math silently.

### Pre-ship quality risks

- Empty Cart clears UI but Valkey still has items → checkout still places order.
- Quantity decrease below 1 leaves ghost line items with qty 0 that checkout still prices.

---

## CART-03 — Stale / expired session cart

| Field | Detail |
|---|---|
| **Priority** | P1 |
| **Type** | Session / TTL |
| **Note** | Cart keys expire after **60 minutes** in Valkey. Prefer accelerating TTL in a QA env if available; otherwise document time-box or simulate by deleting the session key / using a new browser profile mid-flow. |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Add items; confirm cart non-empty. | Items visible. |
| 2 | Expire or invalidate cart storage for that `userId` (TTL wait, key delete, or new session). | Next cart fetch shows **empty** (demo returns empty cart when missing), not a 500. |
| 3 | Attempt checkout if form still open from old page. | Order fails or operates on empty cart — **no** charge for items the user no longer has server-side. |

### Edge cases

- Two tabs: Tab 1 empties cart; Tab 2 still shows old React state — Place Order from Tab 2 must follow **server** cart.
- New browser profile mid-shop — new `userId`, empty cart (expected).

### Pre-ship quality risks

- Client-only cart state allows checkout of expired server cart contents.
- After TTL, GetCart errors instead of empty → cascading checkout Internal errors.

---

## CART-04 — Cart storage unavailable (Valkey down)

| Field | Detail |
|---|---|
| **Priority** | P0 |
| **Type** | Resilience |
| **Preconditions** | Ability to stop/pause Valkey/redis dependency used by cart (label clearly in notes). |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | With Valkey down, add item from UI. | User-visible failure; not a silent no-op. |
| 2 | Restore Valkey. Retry add. | Add succeeds; cart readable. |
| 3 | Optional: Valkey down during GetCart on cart page. | Error state; page does not pretend the cart is empty if the failure is “storage down” (distinguish empty vs error if UI can). |

### Edge cases

- Intermittent Valkey blips under load-generator — error rate correlates with dependency, not random UI selectors.
- Checkout called while cart storage down → fail before payment.

### Pre-ship quality risks

- UI treats `FailedPrecondition` as empty cart → user “loses” items and retries blindly.
- Checkout still charges using a stale in-memory frontend list while cart service is down.

---

## CART-05 — Concurrent updates (two tabs)

| Field | Detail |
|---|---|
| **Priority** | P1 |
| **Type** | Concurrency |

### Steps

| # | Action | Expected result |
|---|---|---|
| 1 | Same session: Tab 1 and Tab 2 open on shop. | Same `userId`. |
| 2 | Tab 1 adds product A; Tab 2 adds product B; refresh both carts. | Final cart contains **both** A and B (last write wins per full cart blob is OK only if both merges applied — verify no lost add). |
| 3 | Tab 1 empties cart; Tab 2 places order without refresh. | Order must not succeed with Tab 2’s stale non-empty UI if server cart is empty. |

### Edge cases

- Both tabs add the **same** product once each → quantity 2 after both GetCart refreshes.
- One tab mid-checkout while the other empties — payment must not proceed on empty server cart.

### Pre-ship quality risks

- Last full-cart overwrite drops the other tab’s add (lost update).
- Stale Tab 2 successfully places an order for items already emptied/paid.

---

## Cart — ship checklist (quality risks to flag)

1. Server cart is authoritative for checkout — never charge from UI-only state.  
2. Re-add merges quantity; tests cover merge, not only first insert.  
3. Empty vs storage-error are distinguishable to the user.  
4. Multi-tab / TTL behaviors have an explicit expected product decision written down.  
5. Cart failures fail **before** payment in the checkout path.
