package io.github.sye1321.paylab.webhook;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentStatus;
import io.github.sye1321.paylab.run.JdbcRunEventStore;
import io.github.sye1321.paylab.run.JdbcTestRunStore;
import io.github.sye1321.paylab.run.TestRun;
import io.github.sye1321.paylab.run.TestRunId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutOfOrderWebhookScheduler {

    private static final long STALE_DELIVERY_DELAY_MILLIS = 100;

    private final JdbcTestRunStore runs;
    private final JdbcWebhookStore webhooks;
    private final JdbcRunEventStore runEvents;
    private final ObjectMapper json;

    public OutOfOrderWebhookScheduler(JdbcTestRunStore runs, JdbcWebhookStore webhooks,
            JdbcRunEventStore runEvents, ObjectMapper json) {
        this.runs = runs;
        this.webhooks = webhooks;
        this.runEvents = runEvents;
        this.json = json;
    }

    @Transactional
    public void schedule(TestRunId runId, Payment succeeded) {
        TestRun run = runs.require(runId);
        if (run.webhookUrl() == null || run.webhookUrl().isBlank()) {
            throw new WebhookSchedulingException("Test run has no webhook URL");
        }
        if (succeeded.status() != PaymentStatus.SUCCEEDED) {
            throw new WebhookSchedulingException("Out-of-order webhooks require a SUCCEEDED payment");
        }

        Instant processingCreatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant succeededCreatedAt = processingCreatedAt.plus(1, ChronoUnit.MICROS);
        Instant processingDueAt = processingCreatedAt.plusMillis(STALE_DELIVERY_DELAY_MILLIS);

        ensureEvent(runId, succeeded, WebhookEventType.PAYMENT_PROCESSING,
                PaymentStatus.PROCESSING, processingCreatedAt, processingDueAt);
        ensureEvent(runId, succeeded, WebhookEventType.PAYMENT_SUCCEEDED,
                PaymentStatus.SUCCEEDED, succeededCreatedAt, processingCreatedAt);
    }

    private void ensureEvent(TestRunId runId, Payment payment, WebhookEventType type,
            PaymentStatus snapshotStatus, Instant createdAt, Instant dueAt) {
        UUID eventId = UUID.randomUUID();
        SuccessfulPaymentWebhookScheduler.WebhookPayload payload =
                new SuccessfulPaymentWebhookScheduler.WebhookPayload(eventId, type, createdAt,
                        new SuccessfulPaymentWebhookScheduler.PaymentData(payment.id().value(),
                                payment.intent().amount().minorUnits(), payment.intent().amount().currency(),
                                payment.intent().merchantReference().value(), snapshotStatus));
        byte[] rawPayload;
        try {
            rawPayload = json.writeValueAsBytes(payload);
        } catch (JacksonException serializationFailure) {
            throw new IllegalStateException("Could not serialize webhook payload", serializationFailure);
        }
        WebhookEvent event = new WebhookEvent(eventId, runId, payment.id(), type, rawPayload, createdAt);
        if (webhooks.insertEventAndDelivery(event, 1, 0, SignatureMode.VALID, dueAt)) {
            runEvents.appendWebhookScheduled(runId, eventId);
        }
    }
}
