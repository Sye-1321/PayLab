package io.github.sye1321.paylab.conformance;

import io.github.sye1321.paylab.run.JdbcTestRunStore;
import io.github.sye1321.paylab.run.TestRun;
import io.github.sye1321.paylab.run.TestRunId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConformanceService {

    private final JdbcTestRunStore runs;
    private final JdbcFinalizedConformanceStore finalizedResults;
    private final TimeoutAfterCommitConformanceEvaluator timeoutAfterCommit;
    private final SameKeyRetryConformanceEvaluator sameKeyRetry;
    private final KeyReuseDifferentPayloadConformanceEvaluator keyReuseDifferentPayload;

    public ConformanceService(JdbcTestRunStore runs, JdbcFinalizedConformanceStore finalizedResults,
            TimeoutAfterCommitConformanceEvaluator timeoutAfterCommit,
            SameKeyRetryConformanceEvaluator sameKeyRetry,
            KeyReuseDifferentPayloadConformanceEvaluator keyReuseDifferentPayload) {
        this.runs = runs;
        this.finalizedResults = finalizedResults;
        this.timeoutAfterCommit = timeoutAfterCommit;
        this.sameKeyRetry = sameKeyRetry;
        this.keyReuseDifferentPayload = keyReuseDifferentPayload;
    }

    public ConformanceEvaluation evaluate(TestRunId runId) {
        TestRun run = runs.require(runId);
        if (run.finalizedAt() != null) {
            return requireFinalizedResult(run);
        }
        return evaluateLive(run);
    }

    @Transactional
    public ConformanceEvaluation finalizeRun(TestRunId runId) {
        TestRun run = runs.requireForUpdate(runId);
        if (run.finalizedAt() != null) {
            return requireFinalizedResult(run);
        }
        ConformanceEvaluation evaluation = evaluateLive(run);
        finalizedResults.save(evaluation);
        runs.markFinalized(runId);
        return evaluation;
    }

    private ConformanceEvaluation evaluateLive(TestRun run) {
        return switch (run.scenario()) {
            case TIMEOUT_AFTER_COMMIT -> timeoutAfterCommit.evaluate(run.runId());
            case SAME_KEY_RETRY -> sameKeyRetry.evaluate(run.runId());
            case KEY_REUSE_DIFFERENT_PAYLOAD -> keyReuseDifferentPayload.evaluate(run.runId());
            default -> throw new UnsupportedConformanceScenarioException(run.scenario());
        };
    }

    private ConformanceEvaluation requireFinalizedResult(TestRun run) {
        return finalizedResults.find(run)
                .orElseThrow(() -> new IllegalStateException("Finalized conformance result is missing"));
    }
}
