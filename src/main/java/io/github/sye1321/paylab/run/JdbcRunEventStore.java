package io.github.sye1321.paylab.run;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import io.github.sye1321.paylab.provider.IdempotencyKey;
import io.github.sye1321.paylab.provider.PaymentId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRunEventStore {

    private final JdbcTemplate jdbc;

    public JdbcRunEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void appendRunStarted(TestRunId runId) {
        jdbc.update("INSERT INTO run_events (run_id, event_type) VALUES (?, ?)",
                runId.value(), RunEventType.RUN_STARTED.name());
    }

    public void appendMerchantRequestObserved(TestRunId runId, IdempotencyKey key, String fingerprint) {
        jdbc.update("""
                INSERT INTO run_events (run_id, event_type, idempotency_key, request_fingerprint)
                VALUES (?, ?, ?, ?)
                """, runId.value(), RunEventType.MERCHANT_REQUEST_OBSERVED.name(), key.value(), fingerprint);
    }

    public void appendPaymentCommitted(TestRunId runId, PaymentId paymentId) {
        jdbc.update("INSERT INTO run_events (run_id, event_type, payment_id) VALUES (?, ?, ?)",
                runId.value(), RunEventType.PAYMENT_COMMITTED.name(), paymentId.value());
    }

    public void appendResponseDelayInjected(TestRunId runId, PaymentId paymentId, int responseDelayMillis) {
        jdbc.update("""
                INSERT INTO run_events (run_id, event_type, payment_id, response_delay_millis)
                VALUES (?, ?, ?, ?)
                """, runId.value(), RunEventType.RESPONSE_DELAY_INJECTED.name(), paymentId.value(),
                responseDelayMillis);
    }

    public void appendWebhookScheduled(TestRunId runId, UUID webhookEventId) {
        appendWebhookEvent(runId, RunEventType.WEBHOOK_SCHEDULED, webhookEventId, null, null);
    }

    public void appendWebhookResponseObserved(TestRunId runId, UUID webhookEventId, int httpStatus,
            String outcome) {
        appendWebhookEvent(runId, RunEventType.WEBHOOK_RESPONSE_OBSERVED, webhookEventId, httpStatus, outcome);
    }

    public void appendWebhookDelivered(TestRunId runId, UUID webhookEventId, int httpStatus) {
        appendWebhookEvent(runId, RunEventType.WEBHOOK_DELIVERED, webhookEventId, httpStatus, "ACKNOWLEDGED");
    }

    private void appendWebhookEvent(TestRunId runId, RunEventType type, UUID webhookEventId,
            Integer httpStatus, String outcome) {
        jdbc.update("""
                INSERT INTO run_events (run_id, event_type, webhook_event_id, http_status, outcome)
                VALUES (?, ?, ?, ?, ?)
                """, runId.value(), type.name(), webhookEventId, httpStatus, outcome);
    }

    public List<RunEvent> findByRun(TestRunId runId) {
        return jdbc.query("""
                SELECT event_id, run_id, event_type, occurred_at, idempotency_key, request_fingerprint,
                       webhook_event_id, http_status, outcome, payment_id, response_delay_millis
                FROM run_events WHERE run_id = ? ORDER BY event_id
                """, JdbcRunEventStore::readEvent, runId.value());
    }

    private static RunEvent readEvent(ResultSet rs, int rowNum) throws SQLException {
        return new RunEvent(rs.getLong("event_id"), new TestRunId(rs.getObject("run_id", UUID.class)),
                RunEventType.valueOf(rs.getString("event_type")),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getObject("webhook_event_id", UUID.class),
                rs.getObject("http_status", Integer.class), rs.getString("outcome"),
                paymentId(rs), rs.getObject("response_delay_millis", Integer.class));
    }

    private static PaymentId paymentId(ResultSet rs) throws SQLException {
        String value = rs.getString("payment_id");
        return value == null ? null : new PaymentId(value);
    }
}
