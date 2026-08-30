package qa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thin HTTP smoke through frontend-proxy (:8080) — the path the browser uses.
 * Complements gRPC service tests; skips cleanly if proxy is down.
 */
class FrontendHttpSmokeTest {

    private static final String BASE = System.getenv().getOrDefault("FRONTEND_BASE_URL", "http://localhost:8080");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void productsListIsReachable() throws Exception {
        HttpResponse<String> response = get("/api/products?currencyCode=USD");
        Assumptions.assumeTrue(response.statusCode() == 200, "frontend-proxy not reachable at " + BASE);

        JsonArray products = JsonParser.parseString(response.body()).getAsJsonArray();
        assertTrue(products.size() >= 2);
    }

    @Test
    void cartAddGetEmptyThroughApi() throws Exception {
        Assumptions.assumeTrue(get("/api/products?currencyCode=USD").statusCode() == 200);

        String userId = "http-" + UUID.randomUUID();
        String productId = JsonParser.parseString(get("/api/products?currencyCode=USD").body())
                .getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString();

        JsonObject addBody = new JsonObject();
        addBody.addProperty("userId", userId);
        JsonObject item = new JsonObject();
        item.addProperty("productId", productId);
        item.addProperty("quantity", 1);
        addBody.add("item", item);

        HttpResponse<String> add = postJson("/api/cart?currencyCode=USD", addBody.toString());
        assertEquals(200, add.statusCode());

        HttpResponse<String> cart = get("/api/cart?sessionId=" + userId + "&currencyCode=USD");
        assertEquals(200, cart.statusCode());
        assertTrue(JsonParser.parseString(cart.body()).getAsJsonObject().getAsJsonArray("items").size() >= 1);

        JsonObject emptyBody = new JsonObject();
        emptyBody.addProperty("userId", userId);
        HttpResponse<String> empty = deleteJson("/api/cart", emptyBody.toString());
        assertTrue(empty.statusCode() == 204 || empty.statusCode() == 200);

        HttpResponse<String> after = get("/api/cart?sessionId=" + userId + "&currencyCode=USD");
        assertEquals(0, JsonParser.parseString(after.body()).getAsJsonObject().getAsJsonArray("items").size());
    }

    @Test
    void checkoutHappyPathThroughApi() throws Exception {
        Assumptions.assumeTrue(get("/api/products?currencyCode=USD").statusCode() == 200);

        String userId = "http-chk-" + UUID.randomUUID();
        JsonArray products = JsonParser.parseString(get("/api/products?currencyCode=USD").body()).getAsJsonArray();
        String productId = products.get(0).getAsJsonObject().get("id").getAsString();

        JsonObject addBody = new JsonObject();
        addBody.addProperty("userId", userId);
        JsonObject item = new JsonObject();
        item.addProperty("productId", productId);
        item.addProperty("quantity", 1);
        addBody.add("item", item);
        assertEquals(200, postJson("/api/cart?currencyCode=USD", addBody.toString()).statusCode());

        JsonObject order = new JsonObject();
        order.addProperty("userId", userId);
        order.addProperty("email", "http-smoke@example.com");
        order.addProperty("userCurrency", "USD");

        JsonObject address = new JsonObject();
        address.addProperty("streetAddress", "1600 Amphitheatre Parkway");
        address.addProperty("city", "Mountain View");
        address.addProperty("state", "CA");
        address.addProperty("country", "US");
        address.addProperty("zipCode", "94043");
        order.add("address", address);

        JsonObject card = new JsonObject();
        card.addProperty("creditCardNumber", "4432-8015-6152-0454");
        card.addProperty("creditCardCvv", 672);
        card.addProperty("creditCardExpirationYear", 2030);
        card.addProperty("creditCardExpirationMonth", 1);
        order.add("creditCard", card);

        HttpResponse<String> checkout = postJson("/api/checkout?currencyCode=USD", order.toString());
        assertEquals(200, checkout.statusCode(), checkout.body());
        JsonObject body = JsonParser.parseString(checkout.body()).getAsJsonObject();
        assertFalse(body.get("orderId").getAsString().isBlank());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> deleteJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
