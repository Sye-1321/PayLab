package io.github.sye1321.paylab.webhook;

import java.time.Instant;
import java.util.UUID;

import io.github.sye1321.paylab.provider.PaymentId;
import io.github.sye1321.paylab.run.TestRunId;

public record WebhookEvent(UUID eventId, TestRunId runId, PaymentId paymentId,
        WebhookEventType type, byte[] payload, Instant createdAt) {
}
