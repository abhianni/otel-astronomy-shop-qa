package qa;

import io.grpc.StatusRuntimeException;
import oteldemo.Demo.AddItemRequest;
import oteldemo.Demo.CartItem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Findings below are from running against the live stack, not just reading source — the
 * pinned `latest` image doesn't exactly match the locally cloned commit (version skew),
 * so a couple of assumptions from static code reading turned out wrong in practice.
 * Always verify defects empirically before writing them up.
 */
class QuantityBoundaryTest {

    // BE-04a/b — a quantity <= 0 line correctly makes PlaceOrder fail (good: matches the
    // "empty cart can't check out" contract from CHK-02).
    @ParameterizedTest(name = "quantity {0} is rejected like an empty cart (BE-04a/b)")
    @ValueSource(ints = {0, -1})
    void quantityIsRejectedLikeAnEmptyCart(int quantity) {
        String productId = TestSupport.firstProductId();
        String userId = "be04-q" + quantity + "-" + UUID.randomUUID();
        GrpcClients.cart.addItem(AddItemRequest.newBuilder()
                .setUserId(userId)
                .setItem(CartItem.newBuilder().setProductId(productId).setQuantity(quantity))
                .build());

        assertThrows(StatusRuntimeException.class, () -> TestSupport.placeOrder(userId, "USD"));
    }

    // BE-04a/b (message defect) — confirmed. The rejection is correct, but for both
    // qty=0 and qty=-1 the error text reads "shipping quote failure: failed POST to
    // email service: expected 200, got 400" — blaming the email service for what is
    // actually a shipping/quantity problem (shipping's real dependency is the `quote`
    // HTTP service, confirmed via its own logs). A misleading cross-service error sends
    // whoever triages this straight to the wrong team.
    @ParameterizedTest(name = "quantity {0} rejection should point at the real cause, not email (BE-04a/b msg)")
    @ValueSource(ints = {0, -1})
    void quantityRejectionShouldPointAtRealCause(int quantity) {
        Xfail.expectFailure(
                "error message blames \"email service\" for a shipping/quantity failure — see BE-04",
                () -> {
                    String productId = TestSupport.firstProductId();
                    String userId = "be04-q" + quantity + "-msg-" + UUID.randomUUID();
                    GrpcClients.cart.addItem(AddItemRequest.newBuilder()
                            .setUserId(userId)
                            .setItem(CartItem.newBuilder().setProductId(productId).setQuantity(quantity))
                            .build());

                    StatusRuntimeException ex =
                            assertThrows(StatusRuntimeException.class, () -> TestSupport.placeOrder(userId, "USD"));
                    String description = ex.getStatus().getDescription();
                    assertFalse(description != null && description.toLowerCase().contains("email service"));
                });
    }

    // BE-04c — confirmed defect, the most severe of this set. A single PlaceOrder call
    // with an extreme quantity (int32 max) does not fail fast: it hangs until the client
    // deadline, and observed side effects during this investigation included the
    // `checkout` container restarting mid-call ("Connection dropped", RestartCount
    // incremented) and a shipping-quote log line of `dollars=19305877986` (~$19.3B)
    // instead of a rejection. A crafted quantity value can destabilize the checkout
    // service — this should ship-block, not just fail a test.
    @org.junit.jupiter.api.Test
    void extremeQuantityIsRejectedFastNotLeftToHangOrDestabilizeCheckout() {
        Xfail.expectFailure(
                "no upper bound on quantity — checkout hangs/restarts instead of validating — see BE-04",
                () -> {
                    String productId = TestSupport.firstProductId();
                    String userId = "be04-int32max-" + UUID.randomUUID();
                    GrpcClients.cart.addItem(AddItemRequest.newBuilder()
                            .setUserId(userId)
                            .setItem(CartItem.newBuilder().setProductId(productId).setQuantity(Integer.MAX_VALUE))
                            .build());

                    long start = System.currentTimeMillis();
                    // desired: fails fast with INVALID_ARGUMENT; actual: hangs/drops near the deadline
                    assertThrows(StatusRuntimeException.class, () -> GrpcClients.checkout
                            .withDeadlineAfter(8, TimeUnit.SECONDS)
                            .placeOrder(TestSupport.orderRequest(userId, "USD")));
                    assertTrue(System.currentTimeMillis() - start < 500);
                });
    }
}
