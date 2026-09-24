package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentCreationResult;
import org.springframework.stereotype.Service;

@Service
public class SameKeyRetryScenarioExecutor {

    private final SuccessfulPaymentProgressor progressor;
    private final JdbcRunEventStore events;

    public SameKeyRetryScenarioExecutor(SuccessfulPaymentProgressor progressor, JdbcRunEventStore events) {
        this.progressor = progressor;
        this.events = events;
    }

    public Payment execute(TestRunId runId, PaymentCreationResult creation) {
        Payment succeeded = progressor.progress(runId, creation.payment());
        if (creation.created()) {
            events.appendPaymentCommitted(runId, succeeded.id());
        }
        return succeeded;
    }
}
