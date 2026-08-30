package qa;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import oteldemo.CartServiceGrpc;
import oteldemo.CheckoutServiceGrpc;
import oteldemo.CurrencyServiceGrpc;
import oteldemo.Demo.Address;
import oteldemo.Demo.CreditCardInfo;
import oteldemo.PaymentServiceGrpc;
import oteldemo.ProductCatalogServiceGrpc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * opentelemetry-demo's compose.yaml publishes backend gRPC ports with the single-value
 * `ports: - "${PORT}"` form, which Docker maps to a random host port (only
 * frontend-proxy gets a fixed 8080:8080 mapping). So the real host port has to be
 * resolved from the running container, not assumed from .env. `<SERVICE>_HOST_PORT` env
 * vars are the escape hatch for setups where the docker CLI isn't available.
 */
final class GrpcClients {

    private static final String HOST = System.getenv().getOrDefault("OTEL_DEMO_HOST", "localhost");
    private static final Pattern PORT_PATTERN = Pattern.compile(":(\\d+)");

    static final CartServiceGrpc.CartServiceBlockingStub cart =
            CartServiceGrpc.newBlockingStub(channel("cart", 7070, "CART_HOST_PORT"));
    static final CheckoutServiceGrpc.CheckoutServiceBlockingStub checkout =
            CheckoutServiceGrpc.newBlockingStub(channel("checkout", 5050, "CHECKOUT_HOST_PORT"));
    static final CurrencyServiceGrpc.CurrencyServiceBlockingStub currency =
            CurrencyServiceGrpc.newBlockingStub(channel("currency", 7001, "CURRENCY_HOST_PORT"));
    static final ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub productCatalog =
            ProductCatalogServiceGrpc.newBlockingStub(channel("product-catalog", 3550, "PRODUCT_CATALOG_HOST_PORT"));
    static final PaymentServiceGrpc.PaymentServiceBlockingStub payment =
            PaymentServiceGrpc.newBlockingStub(channel("payment", 50051, "PAYMENT_HOST_PORT"));

    static final CreditCardInfo TEST_CARD = CreditCardInfo.newBuilder()
            .setCreditCardNumber("4432-8015-6152-0454")
            .setCreditCardCvv(672)
            .setCreditCardExpirationYear(2030)
            .setCreditCardExpirationMonth(1)
            .build();

    static final CreditCardInfo INVALID_CARD = CreditCardInfo.newBuilder()
            .setCreditCardNumber("1234-5678-9012-3456")
            .setCreditCardCvv(672)
            .setCreditCardExpirationYear(2030)
            .setCreditCardExpirationMonth(1)
            .build();

    static final CreditCardInfo EXPIRED_CARD = CreditCardInfo.newBuilder()
            .setCreditCardNumber("4432-8015-6152-0454")
            .setCreditCardCvv(672)
            .setCreditCardExpirationYear(2020)
            .setCreditCardExpirationMonth(1)
            .build();

    static final Address TEST_ADDRESS = Address.newBuilder()
            .setStreetAddress("1600 Amphitheatre Parkway")
            .setCity("Mountain View")
            .setState("CA")
            .setCountry("US")
            .setZipCode("94043")
            .build();

    private GrpcClients() {
    }

    private static ManagedChannel channel(String container, int containerPort, String envVar) {
        return ManagedChannelBuilder.forAddress(HOST, resolvePort(container, containerPort, envVar))
                .usePlaintext()
                .build();
    }

    private static int resolvePort(String container, int containerPort, String envVar) {
        String override = System.getenv(envVar);
        if (override != null && !override.isBlank()) {
            return Integer.parseInt(override.trim());
        }

        try {
            Process process = new ProcessBuilder("docker", "port", container, String.valueOf(containerPort))
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().reduce("", (a, b) -> a + b + "\n");
            }
            process.waitFor();

            Matcher matcher = PORT_PATTERN.matcher(output);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (IOException | InterruptedException e) {
            // fall through — container not found / docker CLI unavailable
        }

        throw new IllegalStateException(
                "Could not resolve host port for " + container + ":" + containerPort
                        + ". Is the stack running (\"docker compose up\")? Or set " + envVar + " explicitly.");
    }
}
