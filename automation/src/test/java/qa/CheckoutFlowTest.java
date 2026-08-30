package qa;

import io.grpc.StatusRuntimeException;
import oteldemo.Demo.OrderResult;
import oteldemo.Demo.PlaceOrderResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automates CHK-01 / CHK-02 / CHK-03 / CHK-06 at the checkout gRPC boundary.
 * Flag/chaos and shipping-after-charge (CHK-04/05) stay manual / pre-release.
 */
class CheckoutFlowTest {

    @Test
    void happyPathPlacesOrderForTwoItemsAndClearsCart() {
        String userId = TestSupport.uniqueUser("chk01");
        TestSupport.addItem(userId, TestSupport.firstProductId(), 1);
        TestSupport.addItem(userId, TestSupport.secondProductId(), 1);

        PlaceOrderResponse response = TestSupport.placeOrder(userId, "USD");
        OrderResult order = response.getOrder();

        assertFalse(order.getOrderId().isBlank());
        assertFalse(order.getShippingTrackingId().isBlank());
        assertEquals(2, order.getItemsCount());
        assertEquals(0, TestSupport.getCart(userId).getItemsCount(), "cart must empty after successful order");
    }

    @Test
    void emptyCartCannotPlaceOrder() {
        String userId = TestSupport.uniqueUser("chk02");

        assertThrows(StatusRuntimeException.class, () -> TestSupport.placeOrder(userId, "USD"));
        assertEquals(0, TestSupport.getCart(userId).getItemsCount());
    }

    @Test
    void invalidCardFailsAndLeavesCartIntact() {
        String userId = TestSupport.uniqueUser("chk03");
        String productId = TestSupport.firstProductId();
        TestSupport.addItem(userId, productId, 1);

        assertThrows(StatusRuntimeException.class, () -> GrpcClients.checkout.placeOrder(
                TestSupport.orderRequest(userId, "USD", GrpcClients.INVALID_CARD)));

        assertEquals(1, TestSupport.getCart(userId).getItemsCount());
        assertEquals(productId, TestSupport.getCart(userId).getItems(0).getProductId());
    }

    @Test
    void expiredCardFailsAndLeavesCartIntact() {
        String userId = TestSupport.uniqueUser("chk03-exp");
        TestSupport.addItem(userId, TestSupport.firstProductId(), 1);

        assertThrows(StatusRuntimeException.class, () -> GrpcClients.checkout.placeOrder(
                TestSupport.orderRequest(userId, "USD", GrpcClients.EXPIRED_CARD)));

        assertEquals(1, TestSupport.getCart(userId).getItemsCount());
    }

    @Test
    void placeOrderInEurReturnsEurCosts() {
        String userId = TestSupport.uniqueUser("chk06");
        TestSupport.addItem(userId, TestSupport.firstProductId(), 1);

        OrderResult order = TestSupport.placeOrder(userId, "EUR").getOrder();

        assertEquals("EUR", order.getShippingCost().getCurrencyCode());
        assertTrue(order.getItemsCount() >= 1);
        assertEquals("EUR", order.getItems(0).getCost().getCurrencyCode());
    }
}
