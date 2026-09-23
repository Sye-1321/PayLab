package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.IllegalPaymentTransitionException;
import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentId;
import io.github.sye1321.paylab.provider.PaymentNotFoundException;
import io.github.sye1321.paylab.provider.PaymentStatus;
import io.github.sye1321.paylab.webhook.SuccessfulPaymentWebhookScheduler;
import org.springframework.stereotype.Service;

@Service
public class AsyncSuccessScenarioExecutor {

    private static final int MAX_PROGRESSION_ATTEMPTS = 5;

    private final JdbcProviderPaymentStore payments;
    private final SuccessfulPaymentWebhookScheduler webhooks;

    public AsyncSuccessScenarioExecutor(JdbcProviderPaymentStore payments,
            SuccessfulPaymentWebhookScheduler webhooks) {
        this.payments = payments;
        this.webhooks = webhooks;
    }

    public Payment execute(TestRunId runId, Payment payment) {
        Payment current = payment;
        for (int attempt = 0; attempt < MAX_PROGRESSION_ATTEMPTS; attempt++) {
            try {
                current = switch (current.status()) {
                    case CREATED -> payments.startProcessing(current.id());
                    case PROCESSING -> payments.markSucceeded(current.id());
                    case SUCCEEDED -> current;
                    case FAILED -> throw new IllegalStateException(
                            "ASYNC_SUCCESS payment is already FAILED: " + current.id().value());
                };
            } catch (IllegalPaymentTransitionException lostRace) {
                PaymentId paymentId = current.id();
                current = payments.findById(runId, paymentId)
                        .orElseThrow(() -> new PaymentNotFoundException(paymentId));
                continue;
            }

            if (current.status() == PaymentStatus.SUCCEEDED) {
                webhooks.schedule(runId, current.id());
                return current;
            }
        }
        throw new IllegalStateException(
                "Could not converge ASYNC_SUCCESS payment to SUCCEEDED: " + current.id().value());
    }
}
