package io.github.sye1321.paylab.conformance;

import java.util.ArrayList;
import java.util.List;

import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentStatus;
import io.github.sye1321.paylab.run.JdbcRunEventStore;
import io.github.sye1321.paylab.run.JdbcTestRunStore;
import io.github.sye1321.paylab.run.RunEvent;
import io.github.sye1321.paylab.run.RunEventType;
import io.github.sye1321.paylab.run.ScenarioId;
import io.github.sye1321.paylab.run.TestRun;
import io.github.sye1321.paylab.run.TestRunId;
import org.springframework.stereotype.Service;

@Service
public class SameKeyRetryConformanceEvaluator {

    private static final String ASSERTION_ID = "IDEMPOTENT_REPLAY";
    private static final String INVARIANT_ID = "INV-02";

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;
    private final JdbcProviderPaymentStore payments;

    public SameKeyRetryConformanceEvaluator(JdbcTestRunStore runs, JdbcRunEventStore events,
            JdbcProviderPaymentStore payments) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.scenario() != ScenarioId.SAME_KEY_RETRY) {
            throw new UnsupportedConformanceScenarioException(run.scenario());
        }

        List<RunEvent> evidence = events.findByRun(runId);
        RunEvent originalResolution = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_REQUEST_RESOLVED)
                .filter(event -> event.idempotencyKey() != null && event.requestFingerprint() != null)
                .filter(event -> event.paymentId() != null)
                .findFirst()
                .orElse(null);
        if (originalResolution == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "No successful create-request resolution has been recorded.", List.of());
        }

        RunEvent originalRequest = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> event.eventId() < originalResolution.eventId())
                .filter(event -> originalResolution.idempotencyKey().equals(event.idempotencyKey()))
                .filter(event -> originalResolution.requestFingerprint().equals(event.requestFingerprint()))
                .reduce((first, second) -> second)
                .orElse(null);
        RunEvent originalCommit = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_COMMITTED)
                .filter(event -> event.eventId() > originalResolution.eventId())
                .filter(event -> originalResolution.paymentId().equals(event.paymentId()))
                .findFirst()
                .orElse(null);
        Payment originalPayment = payments.findById(runId, originalResolution.paymentId()).orElse(null);
        if (originalRequest == null || originalCommit == null
                || originalPayment == null || originalPayment.status() != PaymentStatus.SUCCEEDED) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Original operation evidence is incomplete or authoritative provider success is unavailable.",
                    eventIds(originalRequest, originalResolution, originalCommit));
        }

        RunEvent unsafeRetry = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> event.eventId() > originalResolution.eventId())
                .filter(event -> originalResolution.requestFingerprint().equals(event.requestFingerprint()))
                .filter(event -> !originalResolution.idempotencyKey().equals(event.idempotencyKey()))
                .findFirst()
                .orElse(null);
        if (unsafeRetry != null) {
            return result(run, ConformanceVerdict.FAIL,
                    "An equivalent retry used a different idempotency key, creating duplicate-execution risk.",
                    eventIds(originalRequest, originalResolution, originalCommit, unsafeRetry));
        }

        RunEvent contradictoryResolution = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_REQUEST_RESOLVED)
                .filter(event -> event.eventId() > originalResolution.eventId())
                .filter(event -> originalResolution.idempotencyKey().equals(event.idempotencyKey()))
                .filter(event -> originalResolution.requestFingerprint().equals(event.requestFingerprint()))
                .filter(event -> event.paymentId() != null)
                .filter(event -> !originalResolution.paymentId().equals(event.paymentId()))
                .findFirst()
                .orElse(null);
        if (contradictoryResolution != null) {
            return result(run, ConformanceVerdict.FAIL,
                    "Equivalent same-key requests resolved different logical provider payments.",
                    eventIds(originalRequest, originalResolution, originalCommit, contradictoryResolution));
        }

        RunEvent retryRequest = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> event.eventId() > originalResolution.eventId())
                .filter(event -> originalResolution.idempotencyKey().equals(event.idempotencyKey()))
                .filter(event -> originalResolution.requestFingerprint().equals(event.requestFingerprint()))
                .findFirst()
                .orElse(null);
        if (retryRequest == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "The original operation succeeded, but no equivalent same-key retry has been observed.",
                    eventIds(originalRequest, originalResolution, originalCommit));
        }

        RunEvent retryResolution = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_REQUEST_RESOLVED)
                .filter(event -> event.eventId() > retryRequest.eventId())
                .filter(event -> originalResolution.idempotencyKey().equals(event.idempotencyKey()))
                .filter(event -> originalResolution.requestFingerprint().equals(event.requestFingerprint()))
                .findFirst()
                .orElse(null);
        if (retryResolution == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "An equivalent same-key retry was observed without later resolution evidence.",
                    eventIds(originalRequest, originalResolution, originalCommit, retryRequest));
        }

        RunEvent interveningRetry = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> event.eventId() > retryRequest.eventId())
                .filter(event -> event.eventId() < retryResolution.eventId())
                .filter(event -> originalResolution.idempotencyKey().equals(event.idempotencyKey()))
                .filter(event -> originalResolution.requestFingerprint().equals(event.requestFingerprint()))
                .findFirst()
                .orElse(null);
        if (interveningRetry != null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Multiple equivalent retry observations occurred before resolution evidence, so a sequential "
                            + "request-to-resolution pairing cannot be established.",
                    eventIds(originalRequest, originalResolution, originalCommit, retryRequest,
                            interveningRetry, retryResolution));
        }
        return result(run, ConformanceVerdict.PASS,
                "The equivalent same-key retry resolved the original logical provider payment.",
                eventIds(originalRequest, originalResolution, originalCommit, retryRequest, retryResolution));
    }

    private static ConformanceEvaluation result(TestRun run, ConformanceVerdict verdict, String explanation,
            List<Long> evidenceEventIds) {
        AssertionEvaluation assertion = new AssertionEvaluation(
                ASSERTION_ID, INVARIANT_ID, verdict, explanation, evidenceEventIds);
        return new ConformanceEvaluation(run.runId().value(), run.scenario(), run.scenarioVersion(), verdict,
                List.of(assertion));
    }

    private static List<Long> eventIds(RunEvent... events) {
        List<Long> ids = new ArrayList<>();
        for (RunEvent event : events) {
            if (event != null && !ids.contains(event.eventId())) {
                ids.add(event.eventId());
            }
        }
        ids.sort(Long::compareTo);
        return List.copyOf(ids);
    }
}
