package io.github.sye1321.paylab.provider;

import java.util.Objects;

public final class Payment {

    private final PaymentId id;
    private final PaymentIntent intent;
    private final IdempotencyKey idempotencyKey;
    private PaymentStatus status = PaymentStatus.CREATED;

    public Payment(PaymentId id, PaymentIntent intent, IdempotencyKey idempotencyKey) {
        this.id = Objects.requireNonNull(id, "id");
        this.intent = Objects.requireNonNull(intent, "intent");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    public PaymentId id() {
        return id;
    }

    public PaymentIntent intent() {
        return intent;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }

    public PaymentStatus status() {
        return status;
    }

    public void startProcessing() {
        transition(PaymentStatus.CREATED, PaymentStatus.PROCESSING);
    }

    public void succeed() {
        transition(PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED);
    }

    public void fail() {
        transition(PaymentStatus.PROCESSING, PaymentStatus.FAILED);
    }

    private void transition(PaymentStatus expected, PaymentStatus next) {
        if (status != expected) {
            throw new IllegalPaymentTransitionException(status, next);
        }
        status = next;
    }
}
