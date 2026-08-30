package qa;

import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.EmptyCartRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automates CART-01 / CART-02 at the cart gRPC boundary (session/TTL/Valkey-down stay manual).
 */
class CartTest {

    @Test
    void addItemPersistsAndIsReadable() {
        String userId = TestSupport.uniqueUser("cart01");
        String productId = TestSupport.firstProductId();

        TestSupport.addItem(userId, productId, 1);

        Cart cart = TestSupport.getCart(userId);
        assertEquals(1, cart.getItemsCount());
        assertEquals(productId, cart.getItems(0).getProductId());
        assertEquals(1, cart.getItems(0).getQuantity());
    }

    @Test
    void reAddSameProductMergesQuantity() {
        String userId = TestSupport.uniqueUser("cart-merge");
        String productId = TestSupport.firstProductId();

        TestSupport.addItem(userId, productId, 1);
        TestSupport.addItem(userId, productId, 2);

        Cart cart = TestSupport.getCart(userId);
        assertEquals(1, cart.getItemsCount(), "same product_id must stay one line");
        assertEquals(3, cart.getItems(0).getQuantity());
    }

    @Test
    void emptyCartClearsItems() {
        String userId = TestSupport.uniqueUser("cart-empty");
        TestSupport.addItem(userId, TestSupport.firstProductId(), 1);
        TestSupport.addItem(userId, TestSupport.secondProductId(), 1);

        GrpcClients.cart.emptyCart(EmptyCartRequest.newBuilder().setUserId(userId).build());

        Cart cart = TestSupport.getCart(userId);
        assertTrue(cart.getItemsList().isEmpty()
                || cart.getItemsList().stream().mapToInt(CartItem::getQuantity).sum() == 0);
    }

    @Test
    void unknownUserReturnsEmptyCartNotError() {
        Cart cart = TestSupport.getCart(TestSupport.uniqueUser("never-seen"));
        assertEquals(0, cart.getItemsCount());
    }
}
