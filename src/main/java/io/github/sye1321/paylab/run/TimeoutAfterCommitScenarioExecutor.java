package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentCreationResult;
import org.springframework.stereotype.Service;

@Service
public class TimeoutAfterCommitScenarioExecutor {

    private final SuccessfulPaymentProgressor progressor;
    private final JdbcRunEventStore events;

    public TimeoutAfterCommitScenarioExecutor(SuccessfulPaymentProgressor progressor, JdbcRunEventStore events) {
        this.progressor = progressor;
        this.events = events;
    }

    public PaymentRequestResult execute(TestRun run, PaymentCreationResult creation) {
        Payment succeeded = progressor.progress(run.runId(), creation.payment());
        if (!creation.created()) {
            return new PaymentRequestResult(succeeded, null);
        }
        events.appendPaymentCommitted(run.runId(), succeeded.id());
        return new PaymentRequestResult(succeeded, run.responseDelayMillis());
    }
}
