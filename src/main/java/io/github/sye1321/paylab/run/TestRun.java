package io.github.sye1321.paylab.run;

import java.time.Instant;

public record TestRun(TestRunId runId, ScenarioId scenario, int scenarioVersion, Instant createdAt,
        String webhookUrl, Integer responseDelayMillis) {
}
