package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.IllegalPaymentTransitionException;
import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentId;
import io.github.sye1321.paylab.provider.PaymentNotFoundException;
import io.github.sye1321.paylab.provider.PaymentStatus;
import org.springframework.stereotype.Service;

@Service
public class SuccessfulPaymentProgressor {

    private static final int MAX_PROGRESSION_ATTEMPTS = 5;

    private final JdbcProviderPaymentStore payments;

    public SuccessfulPaymentProgressor(JdbcProviderPaymentStore payments) {
        this.payments = payments;
    }

    public Payment progress(TestRunId runId, Payment payment) {
        Payment current = payment;
        for (int attempt = 0; attempt < MAX_PROGRESSION_ATTEMPTS; attempt++) {
            try {
                current = switch (current.status()) {
                    case CREATED -> payments.startProcessing(current.id());
                    case PROCESSING -> payments.markSucceeded(current.id());
                    case SUCCEEDED -> current;
                    case FAILED -> throw contradictoryState(current);
                };
            } catch (IllegalPaymentTransitionException lostRace) {
                PaymentId paymentId = current.id();
                current = payments.findById(runId, paymentId)
                        .orElseThrow(() -> new PaymentNotFoundException(paymentId));
                continue;
            }

            if (current.status() == PaymentStatus.SUCCEEDED) {
                return current;
            }
        }
        throw new IllegalStateException(
                "Could not converge payment to SUCCEEDED: " + current.id().value());
    }

    private static IllegalStateException contradictoryState(Payment payment) {
        return new IllegalStateException("Successful payment scenario found FAILED payment: " + payment.id().value());
    }
}
