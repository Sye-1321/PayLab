package io.github.sye1321.paylab.provider;

public record PaymentId(String value) {

    public PaymentId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Payment ID must not be blank");
        }
    }
}
