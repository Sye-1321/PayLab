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
    private final WebhookRetryScenarioExecutor webhookRetry;
    private final InvalidSignatureScenarioExecutor invalidSignature;
    private final OutOfOrderWebhookScenarioExecutor outOfOrderWebhook;
    private final ConcurrentDuplicateCreateScenarioExecutor concurrentDuplicateCreate;
    private final SameKeyRetryScenarioExecutor sameKeyRetry;
    private final KeyReuseDifferentPayloadScenarioExecutor keyReuseDifferentPayload;
    private final TimeoutBeforeCommitScenarioExecutor timeoutBeforeCommit;
    private final TimeoutAfterCommitScenarioExecutor timeoutAfterCommit;

    public PaymentRequestOrchestrator(JdbcTestRunStore runs, JdbcRunEventStore events,
            JdbcProviderPaymentStore payments, AsyncSuccessScenarioExecutor asyncSuccess,
            DuplicateWebhookScenarioExecutor duplicateWebhook,
            WebhookRetryScenarioExecutor webhookRetry,
            InvalidSignatureScenarioExecutor invalidSignature,
            OutOfOrderWebhookScenarioExecutor outOfOrderWebhook,
            ConcurrentDuplicateCreateScenarioExecutor concurrentDuplicateCreate,
            SameKeyRetryScenarioExecutor sameKeyRetry,
            KeyReuseDifferentPayloadScenarioExecutor keyReuseDifferentPayload,
            TimeoutBeforeCommitScenarioExecutor timeoutBeforeCommit,
            TimeoutAfterCommitScenarioExecutor timeoutAfterCommit) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
        this.asyncSuccess = asyncSuccess;
        this.duplicateWebhook = duplicateWebhook;
        this.webhookRetry = webhookRetry;
        this.invalidSignature = invalidSignature;
        this.outOfOrderWebhook = outOfOrderWebhook;
        this.concurrentDuplicateCreate = concurrentDuplicateCreate;
        this.sameKeyRetry = sameKeyRetry;
        this.keyReuseDifferentPayload = keyReuseDifferentPayload;
        this.timeoutBeforeCommit = timeoutBeforeCommit;
        this.timeoutAfterCommit = timeoutAfterCommit;
    }

    public PaymentRequestResult create(TestRunId runId, IdempotencyKey key, PaymentIntent intent) {
        TestRun run = runs.requireOpen(runId);
        events.appendMerchantRequestObserved(runId, key, intent.fingerprint());
        if (run.scenario() == ScenarioId.TIMEOUT_BEFORE_COMMIT) {
            return timeoutBeforeCommit.execute(run, key, intent);
        }
        if (run.scenario() == ScenarioId.CONCURRENT_DUPLICATE_CREATE) {
            return new PaymentRequestResult(concurrentDuplicateCreate.execute(runId, key, intent), null);
        }
        PaymentCreationResult creation = payments.createOrResolve(runId, key, intent);
        events.appendPaymentRequestResolved(runId, key, intent.fingerprint(), creation.payment().id());
        return switch (run.scenario()) {
            case ASYNC_SUCCESS -> new PaymentRequestResult(asyncSuccess.execute(runId, creation.payment()), null);
            case DUPLICATE_WEBHOOK ->
                    new PaymentRequestResult(duplicateWebhook.execute(runId, creation), null);
            case WEBHOOK_RETRY -> new PaymentRequestResult(webhookRetry.execute(runId, creation), null);
            case INVALID_SIGNATURE ->
                    new PaymentRequestResult(invalidSignature.execute(runId, creation), null);
            case OUT_OF_ORDER_WEBHOOK ->
                    new PaymentRequestResult(outOfOrderWebhook.execute(runId, creation), null);
            case SAME_KEY_RETRY -> new PaymentRequestResult(sameKeyRetry.execute(runId, creation), null);
            case KEY_REUSE_DIFFERENT_PAYLOAD ->
                    new PaymentRequestResult(keyReuseDifferentPayload.execute(runId, creation), null);
            case TIMEOUT_AFTER_COMMIT -> timeoutAfterCommit.execute(run, creation);
            case TIMEOUT_BEFORE_COMMIT -> throw new IllegalStateException("Scenario was already handled");
            case CONCURRENT_DUPLICATE_CREATE -> throw new IllegalStateException("Scenario was already handled");
        };
    }

    public Optional<Payment> findById(TestRunId runId, PaymentId id) {
        runs.requireOpen(runId);
        Optional<Payment> payment = payments.findById(runId, id);
        payment.ifPresent(found -> events.appendMerchantStatusQueryObserved(runId, found.id()));
        return payment;
    }
}
