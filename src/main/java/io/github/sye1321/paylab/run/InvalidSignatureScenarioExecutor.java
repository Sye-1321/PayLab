package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentCreationResult;
import io.github.sye1321.paylab.webhook.SignatureMode;
import io.github.sye1321.paylab.webhook.SuccessfulPaymentWebhookScheduler;
import org.springframework.stereotype.Service;

@Service
public class InvalidSignatureScenarioExecutor {

    private final SuccessfulPaymentProgressor progressor;
    private final SuccessfulPaymentWebhookScheduler webhooks;
    private final JdbcRunEventStore events;

    public InvalidSignatureScenarioExecutor(SuccessfulPaymentProgressor progressor,
            SuccessfulPaymentWebhookScheduler webhooks, JdbcRunEventStore events) {
        this.progressor = progressor;
        this.webhooks = webhooks;
        this.events = events;
    }

    public Payment execute(TestRunId runId, PaymentCreationResult creation) {
        Payment succeeded = progressor.progress(runId, creation.payment());
        if (creation.created()) {
            events.appendPaymentCommitted(runId, succeeded.id());
        }
        webhooks.schedule(runId, succeeded.id(), 1, 0, SignatureMode.INVALID);
        return succeeded;
    }
}
