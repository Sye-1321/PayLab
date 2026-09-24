package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentCreationResult;
import org.springframework.stereotype.Service;

@Service
public class KeyReuseDifferentPayloadScenarioExecutor {

    private final SuccessfulPaymentProgressor progressor;
    private final JdbcRunEventStore events;

    public KeyReuseDifferentPayloadScenarioExecutor(SuccessfulPaymentProgressor progressor,
            JdbcRunEventStore events) {
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
