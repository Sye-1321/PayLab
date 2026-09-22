package io.github.sye1321.paylab.provider.http;

import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentStatus;

public record PaymentResponse(
        String paymentId,
        long amountMinor,
        String currency,
        String merchantReference,
        PaymentStatus status) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.id().value(),
                payment.intent().amount().minorUnits(),
                payment.intent().amount().currency(),
                payment.intent().merchantReference().value(),
                payment.status());
    }
}
