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
public class TimeoutAfterCommitConformanceEvaluator {

    private static final String ASSERTION_ID = "AMBIGUOUS_OUTCOME_RECOVERY";
    private static final String INVARIANT_ID = "INV-01";

    private final JdbcTestRunStore runs;
    private final JdbcRunEventStore events;
    private final JdbcProviderPaymentStore payments;

    public TimeoutAfterCommitConformanceEvaluator(JdbcTestRunStore runs, JdbcRunEventStore events,
            JdbcProviderPaymentStore payments) {
        this.runs = runs;
        this.events = events;
        this.payments = payments;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.scenario() != ScenarioId.TIMEOUT_AFTER_COMMIT) {
            throw new UnsupportedConformanceScenarioException(run.scenario());
        }

        List<RunEvent> evidence = events.findByRun(runId);
        RunEvent delay = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.RESPONSE_DELAY_INJECTED)
                .filter(event -> event.paymentId() != null)
                .findFirst()
                .orElse(null);
        if (delay == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Required post-commit ambiguity evidence is incomplete: no response delay was observed.",
                    List.of());
        }

        RunEvent commit = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.PAYMENT_COMMITTED)
                .filter(event -> event.eventId() < delay.eventId())
                .filter(event -> delay.paymentId().equals(event.paymentId()))
                .findFirst()
                .orElse(null);
        if (commit == null) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Required post-commit ambiguity evidence is incomplete: no preceding payment commit was found.",
                    eventIds(delay));
        }

        Payment original = payments.findById(runId, delay.paymentId()).orElse(null);
        if (original == null || original.status() != PaymentStatus.SUCCEEDED) {
            return result(run, ConformanceVerdict.INCONCLUSIVE,
                    "Required post-commit ambiguity evidence is incomplete: successful provider truth is unavailable.",
                    eventIds(commit, delay));
        }

        String originalKey = original.idempotencyKey().value();
        String originalFingerprint = original.intent().fingerprint();
        RunEvent originalRequest = evidence.stream()
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> event.eventId() < delay.eventId())
                .filter(event -> originalKey.equals(event.idempotencyKey()))
                .filter(event -> originalFingerprint.equals(event.requestFingerprint()))
                .findFirst()
                .orElse(null);

        RunEvent unsafeRetry = evidence.stream()
                .filter(event -> event.eventId() > delay.eventId())
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> originalFingerprint.equals(event.requestFingerprint()))
                .filter(event -> !originalKey.equals(event.idempotencyKey()))
                .findFirst()
                .orElse(null);
        if (unsafeRetry != null) {
            return result(run, ConformanceVerdict.FAIL,
                    "An equivalent request was initiated under a new idempotency key, creating duplicate-execution risk.",
                    eventIds(originalRequest, commit, delay, unsafeRetry));
        }

        RunEvent safeReplay = evidence.stream()
                .filter(event -> event.eventId() > delay.eventId())
                .filter(event -> event.eventType() == RunEventType.MERCHANT_REQUEST_OBSERVED)
                .filter(event -> originalFingerprint.equals(event.requestFingerprint()))
                .filter(event -> originalKey.equals(event.idempotencyKey()))
                .findFirst()
                .orElse(null);
        if (safeReplay != null) {
            return result(run, ConformanceVerdict.PASS,
                    "Equivalent same-key replay observed after the post-commit response delay.",
                    eventIds(originalRequest, commit, delay, safeReplay));
        }

        RunEvent statusQuery = evidence.stream()
                .filter(event -> event.eventId() > delay.eventId())
                .filter(event -> event.eventType() == RunEventType.MERCHANT_STATUS_QUERY_OBSERVED)
                .filter(event -> original.id().equals(event.paymentId()))
                .findFirst()
                .orElse(null);
        if (statusQuery != null) {
            return result(run, ConformanceVerdict.PASS,
                    "The original succeeded payment was resolved through a successful status lookup.",
                    eventIds(originalRequest, commit, delay, statusQuery));
        }

        return result(run, ConformanceVerdict.INCONCLUSIVE,
                "Post-commit ambiguity was injected, but no safe recovery or unsafe equivalent new-key action has been observed.",
                eventIds(originalRequest, commit, delay));
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
