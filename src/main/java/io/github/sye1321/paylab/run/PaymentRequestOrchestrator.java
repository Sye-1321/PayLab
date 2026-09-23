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
    private final AsyncSuccessScenarioExecutor asyncSuccess;

    public PaymentRequestOrchestrator(JdbcTestRunStore runs, JdbcRunEventStore events,
            JdbcProviderPaymentStore payments, AsyncSuccessScenarioExecutor asyncSuccess) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
        this.asyncSuccess = asyncSuccess;
    }

    public Payment create(TestRunId runId, IdempotencyKey key, PaymentIntent intent) {
        TestRun run = runs.require(runId);
        events.appendMerchantRequestObserved(runId, key, intent.fingerprint());
        Payment payment = payments.createOrResolve(runId, key, intent);
        return switch (run.scenario()) {
            case ASYNC_SUCCESS -> asyncSuccess.execute(runId, payment);
        };
    }

    public Optional<Payment> findById(TestRunId runId, PaymentId id) {
        runs.require(runId);
        return payments.findById(runId, id);
    }
}
