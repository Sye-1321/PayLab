package io.github.sye1321.paylab.provider;

public final class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(IdempotencyKey key) {
        super("Idempotency key already belongs to a different payment intent: " + key.value());
    }
}
