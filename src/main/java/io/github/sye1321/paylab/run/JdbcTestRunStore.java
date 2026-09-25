package io.github.sye1321.paylab.run;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTestRunStore {

    private final JdbcTemplate jdbc;
    private final JdbcRunEventStore events;

    public JdbcTestRunStore(JdbcTemplate jdbc, JdbcRunEventStore events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public TestRun create(ScenarioId scenario, String webhookUrl) {
        return create(scenario, webhookUrl, null);
    }

    @Transactional
    public TestRun create(ScenarioId scenario, String webhookUrl, Integer responseDelayMillis) {
        TestRunId id = new TestRunId(UUID.randomUUID());
        TestRun run = jdbc.queryForObject("""
                INSERT INTO test_runs (run_id, scenario_id, scenario_version, webhook_url, response_delay_millis)
                VALUES (?, ?, 1, ?, ?)
                RETURNING run_id, scenario_id, scenario_version, created_at, webhook_url, response_delay_millis,
                          finalized_at
                """, JdbcTestRunStore::readRun, id.value(), scenario.name(), webhookUrl, responseDelayMillis);
        events.appendRunStarted(id);
        return run;
    }

    public Optional<TestRun> findById(TestRunId id) {
        return jdbc.query("""
                SELECT run_id, scenario_id, scenario_version, created_at, webhook_url, response_delay_millis,
                       finalized_at
                FROM test_runs WHERE run_id = ?
                """, JdbcTestRunStore::readRun, id.value()).stream().findFirst();
    }

    public TestRun require(TestRunId id) {
        return findById(id).orElseThrow(() -> new TestRunNotFoundException(id));
    }

    public TestRun requireForUpdate(TestRunId id) {
        return jdbc.query("""
                SELECT run_id, scenario_id, scenario_version, created_at, webhook_url, response_delay_millis,
                       finalized_at
                FROM test_runs WHERE run_id = ?
                FOR UPDATE
                """, JdbcTestRunStore::readRun, id.value()).stream().findFirst()
                .orElseThrow(() -> new TestRunNotFoundException(id));
    }

    public TestRun requireOpen(TestRunId id) {
        TestRun run = require(id);
        if (run.finalizedAt() != null) {
            throw new TestRunFinalizedException(id);
        }
        return run;
    }

    public void markFinalized(TestRunId id) {
        jdbc.update("UPDATE test_runs SET finalized_at = now() WHERE run_id = ?", id.value());
    }

    private static TestRun readRun(ResultSet rs, int rowNum) throws SQLException {
        Timestamp finalizedAt = rs.getTimestamp("finalized_at");
        return new TestRun(new TestRunId(rs.getObject("run_id", UUID.class)),
                ScenarioId.valueOf(rs.getString("scenario_id")),
                rs.getInt("scenario_version"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("webhook_url"),
                rs.getObject("response_delay_millis", Integer.class),
                finalizedAt == null ? null : finalizedAt.toInstant());
    }
}
