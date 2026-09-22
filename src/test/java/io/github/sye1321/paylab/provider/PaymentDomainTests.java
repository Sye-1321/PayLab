package io.github.sye1321.paylab.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentDomainTests {

    @Test
    void paymentCanSucceedThroughProcessing() {
        Payment payment = payment();

        payment.startProcessing();
        assertEquals(PaymentStatus.PROCESSING, payment.status());
        payment.succeed();
        assertEquals(PaymentStatus.SUCCEEDED, payment.status());
    }

    @Test
    void paymentCanFailThroughProcessing() {
        Payment payment = payment();

        payment.startProcessing();
        payment.fail();

        assertEquals(PaymentStatus.FAILED, payment.status());
    }

    @Test
    void cannotSkipOrRepeatProcessing() {
        Payment payment = payment();

        assertThrows(IllegalPaymentTransitionException.class, payment::succeed);
        assertThrows(IllegalPaymentTransitionException.class, payment::fail);
        assertEquals(PaymentStatus.CREATED, payment.status());

        payment.startProcessing();
        assertThrows(IllegalPaymentTransitionException.class, payment::startProcessing);
        assertEquals(PaymentStatus.PROCESSING, payment.status());
    }

    @Test
    void terminalStatesCannotChange() {
        Payment succeeded = payment();
        succeeded.startProcessing();
        succeeded.succeed();
        assertThrows(IllegalPaymentTransitionException.class, succeeded::fail);
        assertThrows(IllegalPaymentTransitionException.class, succeeded::succeed);
        assertThrows(IllegalPaymentTransitionException.class, succeeded::startProcessing);
        assertEquals(PaymentStatus.SUCCEEDED, succeeded.status());

        Payment failed = payment();
        failed.startProcessing();
        failed.fail();
        assertThrows(IllegalPaymentTransitionException.class, failed::succeed);
        assertThrows(IllegalPaymentTransitionException.class, failed::fail);
        assertEquals(PaymentStatus.FAILED, failed.status());
    }

    @Test
    void valueObjectsRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new Money(0, "USD"));
        assertThrows(IllegalArgumentException.class, () -> new Money(-1, "USD"));
        assertThrows(IllegalArgumentException.class, () -> new Money(100, "usd"));
        assertThrows(IllegalArgumentException.class, () -> new PaymentId(" "));
        assertThrows(IllegalArgumentException.class, () -> new MerchantReference(""));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey(" "));
    }

    @Test
    void equivalentIntentHasStableFingerprint() {
        PaymentIntent first = intent(100, "USD", "order-1");
        PaymentIntent equivalent = intent(100, "USD", "order-1");

        assertEquals(first.fingerprint(), equivalent.fingerprint());
    }

    @Test
    void eachMaterialFieldChangesFingerprint() {
        String baseline = intent(100, "USD", "order-1").fingerprint();

        assertNotEquals(baseline, intent(101, "USD", "order-1").fingerprint());
        assertNotEquals(baseline, intent(100, "EUR", "order-1").fingerprint());
        assertNotEquals(baseline, intent(100, "USD", "order-2").fingerprint());
    }

    private static Payment payment() {
        return new Payment(new PaymentId("pay-1"),
                intent(100, "USD", "order-1"),
                new IdempotencyKey("request-1"));
    }

    private static PaymentIntent intent(long minorUnits, String currency, String reference) {
        return new PaymentIntent(new Money(minorUnits, currency), new MerchantReference(reference));
    }
}
