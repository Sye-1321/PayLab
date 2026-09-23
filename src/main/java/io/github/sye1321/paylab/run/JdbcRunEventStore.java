package io.github.sye1321.paylab.run;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import io.github.sye1321.paylab.provider.IdempotencyKey;
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

    public List<RunEvent> findByRun(TestRunId runId) {
        return jdbc.query("""
                SELECT event_id, run_id, event_type, occurred_at, idempotency_key, request_fingerprint
                FROM run_events WHERE run_id = ? ORDER BY event_id
                """, JdbcRunEventStore::readEvent, runId.value());
    }

    private static RunEvent readEvent(ResultSet rs, int rowNum) throws SQLException {
        return new RunEvent(rs.getLong("event_id"), new TestRunId(rs.getObject("run_id", UUID.class)),
                RunEventType.valueOf(rs.getString("event_type")),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"));
    }
}
