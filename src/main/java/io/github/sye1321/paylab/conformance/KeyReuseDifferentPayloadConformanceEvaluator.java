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
public class KeyReuseDifferentPayloadConformanceEvaluator {

    private static final String ASSERTION_ID = "IDEMPOTENCY_KEY_SCOPE";
    private static final String INVARIANT_ID = "INV-03";

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;
    private final JdbcProviderPaymentStore payments;

    public KeyReuseDifferentPayloadConformanceEvaluator(JdbcTestRunStore runs, JdbcRunEventStore events,
            JdbcProviderPaymentStore payments) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.scenario() != ScenarioId.KEY_REUSE_DIFFERENT_PAYLOAD) {
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
                    "No successful original create-request resolution has been recorded.", List.of());
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

        RunEvent conflictingReuse = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> event.eventId() > originalResolution.eventId())
                .filter(event -> originalResolution.idempotencyKey().equals(event.idempotencyKey()))
                .filter(event -> !originalResolution.requestFingerprint().equals(event.requestFingerprint()))
                .findFirst()
                .orElse(null);
        if (conflictingReuse != null) {
            return result(run, ConformanceVerdict.FAIL,
                    "A materially different payment intent reused the original idempotency key.",
                    eventIds(originalRequest, originalResolution, originalCommit, conflictingReuse));
        }

        RunEvent distinctIntentRequest = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> event.eventId() > originalResolution.eventId())
                .filter(event -> !originalResolution.idempotencyKey().equals(event.idempotencyKey()))
                .filter(event -> !originalResolution.requestFingerprint().equals(event.requestFingerprint()))
                .findFirst()
                .orElse(null);
        if (distinctIntentRequest == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "No later request representing a distinct material payment intent has been observed.",
                    eventIds(originalRequest, originalResolution, originalCommit));
        }

        RunEvent distinctIntentResolution = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_REQUEST_RESOLVED)
                .filter(event -> event.eventId() > distinctIntentRequest.eventId())
                .filter(event -> distinctIntentRequest.idempotencyKey().equals(event.idempotencyKey()))
                .filter(event -> distinctIntentRequest.requestFingerprint().equals(event.requestFingerprint()))
                .findFirst()
                .orElse(null);
        if (distinctIntentResolution == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "A distinct-intent request was observed without later matching resolution evidence.",
                    eventIds(originalRequest, originalResolution, originalCommit, distinctIntentRequest));
        }
        if (originalResolution.paymentId().equals(distinctIntentResolution.paymentId())) {
            return result(run, ConformanceVerdict.FAIL,
                    "Different payment intents collapsed into the same logical provider payment.",
                    eventIds(originalRequest, originalResolution, originalCommit,
                            distinctIntentRequest, distinctIntentResolution));
        }

        RunEvent interveningRequest = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> event.eventId() > distinctIntentRequest.eventId())
                .filter(event -> event.eventId() < distinctIntentResolution.eventId())
                .filter(event -> distinctIntentRequest.idempotencyKey().equals(event.idempotencyKey()))
                .filter(event -> distinctIntentRequest.requestFingerprint().equals(event.requestFingerprint()))
                .findFirst()
                .orElse(null);
        if (interveningRequest != null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Multiple equivalent distinct-intent observations occurred before resolution evidence, so a "
                            + "sequential request-to-resolution pairing cannot be established.",
                    eventIds(originalRequest, originalResolution, originalCommit, distinctIntentRequest,
                            interveningRequest, distinctIntentResolution));
        }

        RunEvent contradictoryResolution = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_REQUEST_RESOLVED)
                .filter(event -> event.eventId() > distinctIntentResolution.eventId())
                .filter(event -> distinctIntentRequest.idempotencyKey().equals(event.idempotencyKey()))
                .filter(event -> distinctIntentRequest.requestFingerprint().equals(event.requestFingerprint()))
                .filter(event -> !distinctIntentResolution.paymentId().equals(event.paymentId()))
                .findFirst()
                .orElse(null);
        if (contradictoryResolution != null) {
            return result(run, ConformanceVerdict.FAIL,
                    "The selected distinct idempotency identity resolved to inconsistent provider payments.",
                    eventIds(originalRequest, originalResolution, originalCommit, distinctIntentRequest,
                            distinctIntentResolution, contradictoryResolution));
        }

        RunEvent distinctIntentCommit = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_COMMITTED)
                .filter(event -> event.eventId() > distinctIntentResolution.eventId())
                .filter(event -> distinctIntentResolution.paymentId().equals(event.paymentId()))
                .findFirst()
                .orElse(null);
        Payment distinctPayment = payments.findById(runId, distinctIntentResolution.paymentId()).orElse(null);
        if (distinctIntentCommit == null || distinctPayment == null
                || distinctPayment.status() != PaymentStatus.SUCCEEDED) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Distinct-operation commit evidence or authoritative provider success is unavailable.",
                    eventIds(originalRequest, originalResolution, originalCommit,
                            distinctIntentRequest, distinctIntentResolution, distinctIntentCommit));
        }

        return result(run, ConformanceVerdict.PASS,
                "Different payment intents used different idempotency keys and became distinct succeeded payments.",
                eventIds(originalRequest, originalResolution, originalCommit,
                        distinctIntentRequest, distinctIntentResolution, distinctIntentCommit));
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
