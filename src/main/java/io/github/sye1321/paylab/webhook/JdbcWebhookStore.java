package io.github.sye1321.paylab.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.sye1321.paylab.provider.PaymentId;
import io.github.sye1321.paylab.run.JdbcRunEventStore;
import io.github.sye1321.paylab.run.TestRunId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcWebhookStore {

    private static final int FAILURE_RETRY_DELAY_MILLIS = 100;

    private final JdbcTemplate jdbc;
    private final JdbcRunEventStore runEvents;
    private final TransactionTemplate transaction;
    private final int claimSeconds;

    public JdbcWebhookStore(JdbcTemplate jdbc, JdbcRunEventStore runEvents,
            PlatformTransactionManager transactionManager,
            @Value("${paylab.webhook.claim-seconds:30}") int claimSeconds) {
        this.jdbc = jdbc;
        this.runEvents = runEvents;
        this.transaction = new TransactionTemplate(transactionManager);
        this.claimSeconds = claimSeconds;
    }

    public boolean insertEventAndDelivery(WebhookEvent event) {
        return insertEventAndDelivery(event, 1);
    }

    public boolean insertEventAndDelivery(WebhookEvent event, int targetDeliveryCount) {
        return insertEventAndDelivery(event, targetDeliveryCount, 0);
    }

    public boolean insertEventAndDelivery(WebhookEvent event, int targetDeliveryCount, int maxFailureRetries) {
        return insertEventAndDelivery(event, targetDeliveryCount, maxFailureRetries, SignatureMode.VALID);
    }

    public boolean insertEventAndDelivery(WebhookEvent event, int targetDeliveryCount, int maxFailureRetries,
            SignatureMode signatureMode) {
        return insertEventAndDelivery(event, targetDeliveryCount, maxFailureRetries, signatureMode,
                event.createdAt());
    }

    public boolean insertEventAndDelivery(WebhookEvent event, int targetDeliveryCount, int maxFailureRetries,
            SignatureMode signatureMode, Instant dueAt) {
        int inserted = jdbc.update("""
                INSERT INTO webhook_events (event_id, run_id, payment_id, event_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, payment_id, event_type) DO NOTHING
                """, event.eventId(), event.runId().value(), event.paymentId().value(), event.type().name(),
                event.payload(), Timestamp.from(event.createdAt()));
        if (inserted == 0) {
            return false;
        }
        jdbc.update("""
                INSERT INTO webhook_deliveries
                    (event_id, status, due_at, target_delivery_count, max_failure_retries, signature_mode)
                VALUES (?, 'PENDING', ?, ?, ?, ?)
                """, event.eventId(), Timestamp.from(dueAt), targetDeliveryCount, maxFailureRetries,
                signatureMode.name());
        return true;
    }

    public Optional<ClaimedDelivery> claimDue() {
        return transaction.execute(status -> jdbc.query("""
                WITH candidate AS (
                    SELECT d.event_id
                    FROM webhook_deliveries d
                    WHERE (d.status = 'PENDING' OR (d.status = 'IN_PROGRESS' AND d.claim_until <= now()))
                      AND d.due_at <= now()
                    ORDER BY d.due_at, d.event_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                ), claimed AS (
                    UPDATE webhook_deliveries d
                    SET status = 'IN_PROGRESS', claim_token = gen_random_uuid(),
                        claim_until = now() + (? * interval '1 second')
                    FROM candidate c WHERE d.event_id = c.event_id
                    RETURNING d.event_id, d.claim_token
                )
                SELECT e.event_id, e.run_id, e.payload, t.webhook_url, c.claim_token, d.signature_mode
                FROM claimed c
                JOIN webhook_deliveries d ON d.event_id = c.event_id
                JOIN webhook_events e ON e.event_id = c.event_id
                JOIN test_runs t ON t.run_id = e.run_id
                """, JdbcWebhookStore::readClaim, claimSeconds).stream().findFirst());
    }

    public void recordResult(ClaimedDelivery claim, Instant attemptedAt, Integer httpStatus,
            DeliveryOutcome outcome) {
        transaction.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    UPDATE webhook_deliveries
                    SET status = CASE
                            WHEN ? = 'DELIVERED'
                                AND acknowledged_delivery_count + 1 < target_delivery_count THEN 'PENDING'
                            WHEN ? = 'DELIVERED' THEN 'DELIVERED'
                            WHEN failure_count < max_failure_retries THEN 'PENDING'
                            ELSE 'FAILED'
                        END,
                        attempt_count = attempt_count + 1,
                        acknowledged_delivery_count = acknowledged_delivery_count
                            + CASE WHEN ? = 'DELIVERED' THEN 1 ELSE 0 END,
                        failure_count = failure_count
                            + CASE WHEN ? <> 'DELIVERED' THEN 1 ELSE 0 END,
                        due_at = CASE
                            WHEN ? = 'DELIVERED'
                                AND acknowledged_delivery_count + 1 < target_delivery_count THEN now()
                            WHEN ? <> 'DELIVERED' AND failure_count < max_failure_retries
                                THEN now() + (? * interval '1 millisecond')
                            ELSE due_at
                        END,
                        last_http_status = ?,
                        claim_token = NULL, claim_until = NULL
                    WHERE event_id = ? AND status = 'IN_PROGRESS' AND claim_token = ?
                    """, outcome.name(), outcome.name(), outcome.name(), outcome.name(), outcome.name(),
                    outcome.name(), FAILURE_RETRY_DELAY_MILLIS, httpStatus,
                    claim.eventId(), claim.claimToken());
            if (updated != 1) {
                throw new IllegalStateException("Webhook delivery claim is no longer owned");
            }
            jdbc.update("""
                    INSERT INTO webhook_delivery_attempts
                        (attempt_id, event_id, attempted_at, http_status, outcome)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), claim.eventId(), Timestamp.from(attemptedAt), httpStatus, outcome.name());
            if (httpStatus != null) {
                runEvents.appendWebhookResponseObserved(claim.runId(), claim.eventId(), httpStatus,
                        outcome == DeliveryOutcome.DELIVERED ? "ACKNOWLEDGED" : "NON_2XX");
                if (outcome == DeliveryOutcome.DELIVERED) {
                    runEvents.appendWebhookDelivered(claim.runId(), claim.eventId(), httpStatus);
                }
            }
        });
    }

    public Optional<WebhookEvent> findEvent(UUID eventId) {
        return jdbc.query("""
                SELECT event_id, run_id, payment_id, event_type, payload, created_at
                FROM webhook_events WHERE event_id = ?
                """, JdbcWebhookStore::readEvent, eventId).stream().findFirst();
    }

    public Optional<WebhookEvent> findEvent(TestRunId runId, PaymentId paymentId, WebhookEventType type) {
        return jdbc.query("""
                SELECT event_id, run_id, payment_id, event_type, payload, created_at
                FROM webhook_events WHERE run_id = ? AND payment_id = ? AND event_type = ?
                """, JdbcWebhookStore::readEvent, runId.value(), paymentId.value(), type.name()).stream().findFirst();
    }

    private static ClaimedDelivery readClaim(ResultSet rs, int rowNum) throws SQLException {
        return new ClaimedDelivery(rs.getObject("event_id", UUID.class),
                new TestRunId(rs.getObject("run_id", UUID.class)), rs.getBytes("payload"),
                rs.getString("webhook_url"), rs.getObject("claim_token", UUID.class),
                SignatureMode.valueOf(rs.getString("signature_mode")));
    }

    private static WebhookEvent readEvent(ResultSet rs, int rowNum) throws SQLException {
        return new WebhookEvent(rs.getObject("event_id", UUID.class),
                new TestRunId(rs.getObject("run_id", UUID.class)),
                new PaymentId(rs.getString("payment_id")),
                WebhookEventType.valueOf(rs.getString("event_type")), rs.getBytes("payload"),
                rs.getTimestamp("created_at").toInstant());
    }

    public record ClaimedDelivery(UUID eventId, TestRunId runId, byte[] payload,
            String webhookUrl, UUID claimToken, SignatureMode signatureMode) {
    }

    public enum DeliveryOutcome {
        DELIVERED, NON_2XX, NETWORK_ERROR
    }
}
