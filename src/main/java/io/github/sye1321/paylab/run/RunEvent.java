package io.github.sye1321.paylab.run;

import java.time.Instant;

public record RunEvent(long eventId, TestRunId runId, RunEventType eventType, Instant occurredAt,
        String idempotencyKey, String requestFingerprint) {
}
