package qa;

import oteldemo.Demo.AddItemRequest;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.CreditCardInfo;
import oteldemo.Demo.Empty;
import oteldemo.Demo.GetCartRequest;
import oteldemo.Demo.ListProductsResponse;
import oteldemo.Demo.PlaceOrderRequest;
import oteldemo.Demo.PlaceOrderResponse;
import oteldemo.Demo.Product;

import java.util.List;
import java.util.UUID;

final class TestSupport {

    private TestSupport() {
    }

    static String uniqueUser(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    static ListProductsResponse listProducts() {
        return GrpcClients.productCatalog.listProducts(Empty.getDefaultInstance());
    }

    static String firstProductId() {
        return listProducts().getProducts(0).getId();
    }

    static String secondProductId() {
        List<Product> products = listProducts().getProductsList();
        if (products.size() < 2) {
            throw new IllegalStateException("Need at least 2 catalog products for this test");
        }
        return products.get(1).getId();
    }

    static void addItem(String userId, String productId, int quantity) {
        GrpcClients.cart.addItem(AddItemRequest.newBuilder()
                .setUserId(userId)
                .setItem(CartItem.newBuilder().setProductId(productId).setQuantity(quantity))
                .build());
    }

    static oteldemo.Demo.Cart getCart(String userId) {
        return GrpcClients.cart.getCart(GetCartRequest.newBuilder().setUserId(userId).build());
    }

    static PlaceOrderRequest orderRequest(String userId, String currency) {
        return orderRequest(userId, currency, GrpcClients.TEST_CARD);
    }

    static PlaceOrderRequest orderRequest(String userId, String currency, CreditCardInfo card) {
        return PlaceOrderRequest.newBuilder()
                .setUserId(userId)
                .setUserCurrency(currency)
                .setAddress(GrpcClients.TEST_ADDRESS)
                .setEmail("be-test@example.com")
                .setCreditCard(card)
                .build();
    }

    static PlaceOrderResponse placeOrder(String userId, String currency) {
        return GrpcClients.checkout.placeOrder(orderRequest(userId, currency));
    }
}
