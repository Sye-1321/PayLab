package io.github.sye1321.paylab.conformance;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import io.github.sye1321.paylab.run.TestRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFinalizedConformanceStore {

    private final JdbcTemplate jdbc;

    public JdbcFinalizedConformanceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(ConformanceEvaluation evaluation) {
        jdbc.update("INSERT INTO finalized_conformance_results (run_id, verdict) VALUES (?, ?)",
                evaluation.runId(), evaluation.verdict().name());
        for (int ordinal = 0; ordinal < evaluation.assertions().size(); ordinal++) {
            AssertionEvaluation assertion = evaluation.assertions().get(ordinal);
            int assertionOrdinal = ordinal;
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO finalized_conformance_assertions
                            (run_id, ordinal, assertion_id, invariant_id, verdict, explanation,
                             evidence_event_ids)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """);
                statement.setObject(1, evaluation.runId());
                statement.setInt(2, assertionOrdinal);
                statement.setString(3, assertion.assertionId());
                statement.setString(4, assertion.invariantId());
                statement.setString(5, assertion.verdict().name());
                statement.setString(6, assertion.explanation());
                statement.setArray(7, connection.createArrayOf("bigint", assertion.evidenceEventIds().toArray()));
                return statement;
            });
        }
    }

    public Optional<ConformanceEvaluation> find(TestRun run) {
        return jdbc.query("""
                SELECT verdict FROM finalized_conformance_results WHERE run_id = ?
                """, (rs, rowNum) -> ConformanceVerdict.valueOf(rs.getString("verdict")),
                run.runId().value()).stream().findFirst().map(verdict -> new ConformanceEvaluation(
                        run.runId().value(), run.scenario(), run.scenarioVersion(), verdict,
                        findAssertions(run)));
    }

    private List<AssertionEvaluation> findAssertions(TestRun run) {
        return jdbc.query("""
                SELECT assertion_id, invariant_id, verdict, explanation, evidence_event_ids
                FROM finalized_conformance_assertions
                WHERE run_id = ?
                ORDER BY ordinal
                """, (rs, rowNum) -> new AssertionEvaluation(
                        rs.getString("assertion_id"),
                        rs.getString("invariant_id"),
                        ConformanceVerdict.valueOf(rs.getString("verdict")),
                        rs.getString("explanation"),
                        evidenceIds(rs.getArray("evidence_event_ids"))),
                run.runId().value());
    }

    private static List<Long> evidenceIds(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(value -> ((Number) value).longValue()).toList();
    }
}
