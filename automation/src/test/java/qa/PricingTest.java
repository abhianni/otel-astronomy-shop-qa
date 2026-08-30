package qa;

import oteldemo.Demo.OrderItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BE-01 — checkout/main.go stores OrderItem.cost as the UNIT price (converted, not
 * multiplied); quantity is only applied when summing the charge total. Proven here by
 * placing the same product at two different quantities and asserting cost is identical.
 */
class PricingTest {

    @Test
    void orderItemCostIsUnitPriceInvariantAcrossQuantity() {
        String productId = TestSupport.firstProductId();

        String userQty1 = TestSupport.uniqueUser("be01-qty1");
        String userQty5 = TestSupport.uniqueUser("be01-qty5");

        TestSupport.addItem(userQty1, productId, 1);
        TestSupport.addItem(userQty5, productId, 5);

        OrderItem costQty1 = TestSupport.placeOrder(userQty1, "USD").getOrder().getItems(0);
        OrderItem costQty5 = TestSupport.placeOrder(userQty5, "USD").getOrder().getItems(0);

        assertEquals(costQty1.getCost().getUnits(), costQty5.getCost().getUnits());
        assertEquals(costQty1.getCost().getNanos(), costQty5.getCost().getNanos());
    }
}
