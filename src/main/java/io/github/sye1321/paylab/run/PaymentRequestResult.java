package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.Payment;

public record PaymentRequestResult(Payment payment, Integer responseDelayMillis) {
}
