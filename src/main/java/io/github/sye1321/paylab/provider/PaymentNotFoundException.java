package io.github.sye1321.paylab.provider;

public final class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(PaymentId id) {
        super("Provider payment not found: " + id.value());
    }
}
