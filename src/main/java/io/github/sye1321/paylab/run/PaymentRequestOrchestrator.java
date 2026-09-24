package io.github.sye1321.paylab.run;

import java.util.Optional;

import io.github.sye1321.paylab.provider.IdempotencyKey;
import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentCreationResult;
import io.github.sye1321.paylab.provider.PaymentId;
import io.github.sye1321.paylab.provider.PaymentIntent;
import org.springframework.stereotype.Service;

@Service
public class PaymentRequestOrchestrator {

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;
    private final JdbcProviderPaymentStore payments;
    private final AsyncSuccessScenarioExecutor asyncSuccess;
    private final DuplicateWebhookScenarioExecutor duplicateWebhook;
    private final SameKeyRetryScenarioExecutor sameKeyRetry;
    private final TimeoutBeforeCommitScenarioExecutor timeoutBeforeCommit;
    private final TimeoutAfterCommitScenarioExecutor timeoutAfterCommit;

    public PaymentRequestOrchestrator(JdbcTestRunStore runs, JdbcRunEventStore events,
            JdbcProviderPaymentStore payments, AsyncSuccessScenarioExecutor asyncSuccess,
            DuplicateWebhookScenarioExecutor duplicateWebhook,
            SameKeyRetryScenarioExecutor sameKeyRetry,
            TimeoutBeforeCommitScenarioExecutor timeoutBeforeCommit,
            TimeoutAfterCommitScenarioExecutor timeoutAfterCommit) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
        this.asyncSuccess = asyncSuccess;
        this.duplicateWebhook = duplicateWebhook;
        this.sameKeyRetry = sameKeyRetry;
        this.timeoutBeforeCommit = timeoutBeforeCommit;
        this.timeoutAfterCommit = timeoutAfterCommit;
    }

    public PaymentRequestResult create(TestRunId runId, IdempotencyKey key, PaymentIntent intent) {
        TestRun run = runs.require(runId);
        events.appendMerchantRequestObserved(runId, key, intent.fingerprint());
        if (run.scenario() == ScenarioId.TIMEOUT_BEFORE_COMMIT) {
            return timeoutBeforeCommit.execute(run, key, intent);
        }
        PaymentCreationResult creation = payments.createOrResolve(runId, key, intent);
        events.appendPaymentRequestResolved(runId, key, intent.fingerprint(), creation.payment().id());
        return switch (run.scenario()) {
            case ASYNC_SUCCESS -> new PaymentRequestResult(asyncSuccess.execute(runId, creation.payment()), null);
            case DUPLICATE_WEBHOOK ->
                    new PaymentRequestResult(duplicateWebhook.execute(runId, creation), null);
            case SAME_KEY_RETRY -> new PaymentRequestResult(sameKeyRetry.execute(runId, creation), null);
            case TIMEOUT_AFTER_COMMIT -> timeoutAfterCommit.execute(run, creation);
            case TIMEOUT_BEFORE_COMMIT -> throw new IllegalStateException("Scenario was already handled");
        };
    }

    public Optional<Payment> findById(TestRunId runId, PaymentId id) {
        runs.require(runId);
        Optional<Payment> payment = payments.findById(runId, id);
        payment.ifPresent(found -> events.appendMerchantStatusQueryObserved(runId, found.id()));
        return payment;
    }
}
