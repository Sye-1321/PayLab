package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.webhook.SuccessfulPaymentWebhookScheduler;
import org.springframework.stereotype.Service;

@Service
public class AsyncSuccessScenarioExecutor {

    private final SuccessfulPaymentProgressor progressor;
    private final SuccessfulPaymentWebhookScheduler webhooks;

    public AsyncSuccessScenarioExecutor(SuccessfulPaymentProgressor progressor,
            SuccessfulPaymentWebhookScheduler webhooks) {
        this.progressor = progressor;
        this.webhooks = webhooks;
    }

    public Payment execute(TestRunId runId, Payment payment) {
        Payment succeeded = progressor.progress(runId, payment);
        webhooks.schedule(runId, succeeded.id());
        return succeeded;
    }
}
