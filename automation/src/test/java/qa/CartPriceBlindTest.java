package qa;

import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BE-05 — cart stores product_id + quantity only; price is resolved at PlaceOrder time.
 */
class CartPriceBlindTest {

    @Test
    void cartItemHasNoPriceFieldOnlyProductAndQuantity() {
        String userId = TestSupport.uniqueUser("be05");
        String productId = TestSupport.firstProductId();
        TestSupport.addItem(userId, productId, 2);

        Cart cart = TestSupport.getCart(userId);
        assertEquals(1, cart.getItemsCount());

        CartItem item = cart.getItems(0);
        assertEquals(productId, item.getProductId());
        assertEquals(2, item.getQuantity());
        // Proto CartItem only has product_id + quantity — price lives on Product / OrderItem.
        assertEquals(2, item.getDescriptorForType().getFields().size());
        assertEquals(2, item.getAllFields().size());
    }
}
