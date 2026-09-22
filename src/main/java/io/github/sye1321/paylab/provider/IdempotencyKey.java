package io.github.sye1321.paylab.provider;

public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
    }
}
