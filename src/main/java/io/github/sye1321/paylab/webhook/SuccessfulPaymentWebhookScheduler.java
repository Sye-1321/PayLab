package io.github.sye1321.paylab.webhook;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentId;
import io.github.sye1321.paylab.provider.PaymentNotFoundException;
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
public class SuccessfulPaymentWebhookScheduler {

    private final JdbcTestRunStore runs;
    private final JdbcProviderPaymentStore payments;
    private final JdbcWebhookStore webhooks;
    private final JdbcRunEventStore runEvents;
    private final ObjectMapper json;

    public SuccessfulPaymentWebhookScheduler(JdbcTestRunStore runs, JdbcProviderPaymentStore payments,
            JdbcWebhookStore webhooks, JdbcRunEventStore runEvents, ObjectMapper json) {
        this.runs = runs;
        this.payments = payments;
        this.webhooks = webhooks;
        this.runEvents = runEvents;
        this.json = json;
    }

    @Transactional
    public WebhookEvent schedule(TestRunId runId, PaymentId paymentId) {
        TestRun run = runs.require(runId);
        if (run.webhookUrl() == null || run.webhookUrl().isBlank()) {
            throw new WebhookSchedulingException("Test run has no webhook URL");
        }
        Payment payment = payments.findById(runId, paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.status() != PaymentStatus.SUCCEEDED) {
            throw new WebhookSchedulingException("Only a SUCCEEDED payment can produce PAYMENT_SUCCEEDED");
        }

        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WebhookPayload payload = new WebhookPayload(eventId, WebhookEventType.PAYMENT_SUCCEEDED, createdAt,
                new PaymentData(payment.id().value(), payment.intent().amount().minorUnits(),
                        payment.intent().amount().currency(), payment.intent().merchantReference().value(),
                        payment.status()));
        byte[] rawPayload;
        try {
            rawPayload = json.writeValueAsBytes(payload);
        } catch (JacksonException serializationFailure) {
            throw new IllegalStateException("Could not serialize webhook payload", serializationFailure);
        }
        WebhookEvent event = new WebhookEvent(eventId, runId, paymentId,
                WebhookEventType.PAYMENT_SUCCEEDED, rawPayload, createdAt);
        if (webhooks.insertEventAndDelivery(event)) {
            runEvents.appendWebhookScheduled(runId, eventId);
            return event;
        }
        return webhooks.findEvent(runId, paymentId, WebhookEventType.PAYMENT_SUCCEEDED)
                .orElseThrow(() -> new IllegalStateException(
                        "Successful webhook winner not found for payment: " + paymentId.value()));
    }

    public record WebhookPayload(UUID eventId, WebhookEventType type, Instant createdAt, PaymentData data) {
    }

    public record PaymentData(String paymentId, long amountMinor, String currency,
            String merchantReference, PaymentStatus status) {
    }
}
