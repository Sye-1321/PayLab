package io.github.sye1321.paylab.run;

import java.time.Instant;
import java.util.UUID;

public record RunEvent(long eventId, TestRunId runId, RunEventType eventType, Instant occurredAt,
        String idempotencyKey, String requestFingerprint, UUID webhookEventId,
        Integer httpStatus, String outcome) {
}
