package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.IdempotencyKey;
import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentCreationResult;
import io.github.sye1321.paylab.provider.PaymentIntent;
import org.springframework.stereotype.Service;

@Service
public class TimeoutBeforeCommitScenarioExecutor {

    private final JdbcRunEventStore events;
    private final JdbcProviderPaymentStore payments;
    private final SuccessfulPaymentProgressor progressor;

    public TimeoutBeforeCommitScenarioExecutor(JdbcRunEventStore events, JdbcProviderPaymentStore payments,
            SuccessfulPaymentProgressor progressor) {
        this.events = events;
        this.payments = payments;
        this.progressor = progressor;
    }

    public PaymentRequestResult execute(TestRun run, IdempotencyKey key, PaymentIntent intent) {
        if (events.tryAppendPreCommitTimeoutInjected(
                run.runId(), key, intent.fingerprint(), run.responseDelayMillis())) {
            throw new PreCommitFailureException(run.responseDelayMillis());
        }

        PaymentCreationResult creation = payments.createOrResolve(run.runId(), key, intent);
        Payment succeeded = progressor.progress(run.runId(), creation.payment());
        if (creation.created()) {
            events.appendPaymentCommitted(run.runId(), succeeded.id());
        }
        return new PaymentRequestResult(succeeded, null);
    }
}
