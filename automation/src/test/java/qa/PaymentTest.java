package qa;

import io.grpc.StatusRuntimeException;
import oteldemo.Demo.ChargeRequest;
import oteldemo.Demo.ChargeResponse;
import oteldemo.Demo.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Payment-service slice of CHK-03 / payment validation. */
class PaymentTest {

    @Test
    void validCardReturnsTransactionId() {
        ChargeResponse response = GrpcClients.payment.charge(ChargeRequest.newBuilder()
                .setAmount(Money.newBuilder().setCurrencyCode("USD").setUnits(10).setNanos(0))
                .setCreditCard(GrpcClients.TEST_CARD)
                .build());

        assertFalse(response.getTransactionId().isBlank());
    }

    @Test
    void invalidCardIsRejected() {
        assertThrows(StatusRuntimeException.class, () -> GrpcClients.payment.charge(ChargeRequest.newBuilder()
                .setAmount(Money.newBuilder().setCurrencyCode("USD").setUnits(10).setNanos(0))
                .setCreditCard(GrpcClients.INVALID_CARD)
                .build()));
    }

    @Test
    void expiredCardIsRejected() {
        assertThrows(StatusRuntimeException.class, () -> GrpcClients.payment.charge(ChargeRequest.newBuilder()
                .setAmount(Money.newBuilder().setCurrencyCode("USD").setUnits(10).setNanos(0))
                .setCreditCard(GrpcClients.EXPIRED_CARD)
                .build()));
    }
}
