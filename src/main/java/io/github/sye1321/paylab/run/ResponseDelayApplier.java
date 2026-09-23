package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.PaymentId;
import org.springframework.stereotype.Service;

@Service
public class ResponseDelayApplier {

    private final JdbcRunEventStore events;

    public ResponseDelayApplier(JdbcRunEventStore events) {
        this.events = events;
    }

    public void apply(TestRunId runId, PaymentId paymentId, Integer responseDelayMillis) {
        if (responseDelayMillis == null) {
            return;
        }
        events.appendResponseDelayInjected(runId, paymentId, responseDelayMillis);
        try {
            Thread.sleep(responseDelayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Response delay interrupted", interrupted);
        }
    }
}
