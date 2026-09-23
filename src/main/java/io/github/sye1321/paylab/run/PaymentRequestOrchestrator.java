package io.github.sye1321.paylab.run;

import java.util.Optional;

import io.github.sye1321.paylab.provider.IdempotencyKey;
import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentId;
import io.github.sye1321.paylab.provider.PaymentIntent;
import org.springframework.stereotype.Service;

@Service
public class PaymentRequestOrchestrator {

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;
    private final JdbcProviderPaymentStore payments;

    public PaymentRequestOrchestrator(JdbcTestRunStore runs, JdbcRunEventStore events,
            JdbcProviderPaymentStore payments) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
    }

    public Payment create(TestRunId runId, IdempotencyKey key, PaymentIntent intent) {
        runs.require(runId);
        events.appendMerchantRequestObserved(runId, key, intent.fingerprint());
        return payments.createOrResolve(runId, key, intent);
    }

    public Optional<Payment> findById(TestRunId runId, PaymentId id) {
        runs.require(runId);
        return payments.findById(runId, id);
    }
}
