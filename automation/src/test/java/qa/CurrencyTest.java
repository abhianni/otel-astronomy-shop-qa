package qa;

import io.grpc.StatusRuntimeException;
import oteldemo.Demo.AddItemRequest;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.CurrencyConversionRequest;
import oteldemo.Demo.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyTest {

    // BE-02 — round-trip precision, no hardcoded rate assumptions (rates can change).
    @Test
    void usdToEurToUsdRoundTripStaysWithinOneCent() {
        Money original = Money.newBuilder().setCurrencyCode("USD").setUnits(15).setNanos(990_000_000).build(); // $15.99

        Money toEur = GrpcClients.currency.convert(
                CurrencyConversionRequest.newBuilder().setFrom(original).setToCode("EUR").build());
        Money backToUsd = GrpcClients.currency.convert(
                CurrencyConversionRequest.newBuilder().setFrom(toEur).setToCode("USD").build());

        assertTrue(Math.abs(toCents(original) - toCents(backToUsd)) <= 1);
    }

    // BE-06 — confirmed defect (see test-cases/checkout-payment-backend.md).
    // currency's rate lookup (src/currency/src/server.cpp) has no existence check: an
    // unknown code silently default-constructs a 0.0 rate. Live testing showed this is
    // used as a MULTIPLIER, not a divisor, so Convert returns a clean {units:0, nanos:0}
    // rather than the originally-hypothesized inf/nan garbage. This test asserts the
    // CORRECT behavior (should be rejected) and is expected to fail until validated.
    @Test
    void unknownCurrencyCodeIsRejectedNotSilentlyZeroed() {
        Xfail.expectFailure("currency.Convert has no rate-table existence check — see BE-06", () ->
                assertThrows(StatusRuntimeException.class, () -> GrpcClients.currency.convert(
                        CurrencyConversionRequest.newBuilder()
                                .setFrom(Money.newBuilder().setCurrencyCode("USD").setUnits(10).setNanos(0))
                                .setToCode("XYZ")
                                .build())));
    }

    // BE-06 checkout-level — the real, more severe finding. Because Convert silently
    // zeroes instead of erroring, checkout.PlaceOrder with an unsupported user_currency
    // does not just mis-price an item, it completes a REAL order (order_id + shipping
    // tracking id issued) with item cost AND shipping cost both $0.00 in a nonexistent
    // currency. Confirmed via direct repro against the live stack. This is an
    // immediately reachable free-order/revenue-loss bug, not a hypothetical one.
    @Test
    void placeOrderWithUnsupportedCurrencyIsRejectedNotCompletedAsAFreeOrder() {
        Xfail.expectFailure(
                "checkout.PlaceOrder silently completes a $0.00 order for an unsupported currency code — see BE-06",
                () -> {
                    String productId = TestSupport.firstProductId();
                    String userId = "be06-badcurrency-" + UUID.randomUUID();
                    GrpcClients.cart.addItem(AddItemRequest.newBuilder()
                            .setUserId(userId)
                            .setItem(CartItem.newBuilder().setProductId(productId).setQuantity(1))
                            .build());

                    assertThrows(StatusRuntimeException.class, () -> TestSupport.placeOrder(userId, "XYZ"));
                });
    }

    private static long toCents(Money m) {
        return m.getUnits() * 100 + Math.round(m.getNanos() / 1e7);
    }
}
