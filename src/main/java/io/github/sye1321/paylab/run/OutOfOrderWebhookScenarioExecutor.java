package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentCreationResult;
import io.github.sye1321.paylab.webhook.OutOfOrderWebhookScheduler;
import org.springframework.stereotype.Service;

@Service
public class OutOfOrderWebhookScenarioExecutor {

    private final SuccessfulPaymentProgressor progressor;
    private final OutOfOrderWebhookScheduler webhooks;
    private final JdbcRunEventStore events;

    public OutOfOrderWebhookScenarioExecutor(SuccessfulPaymentProgressor progressor,
            OutOfOrderWebhookScheduler webhooks, JdbcRunEventStore events) {
        this.progressor = progressor;
        this.webhooks = webhooks;
        this.events = events;
    }

    public Payment execute(TestRunId runId, PaymentCreationResult creation) {
        Payment succeeded = progressor.progress(runId, creation.payment());
        if (creation.created()) {
            events.appendPaymentCommitted(runId, succeeded.id());
        }
        webhooks.schedule(runId, succeeded);
        return succeeded;
    }
}
