package io.github.sye1321.paylab.conformance;

import java.util.List;
import java.util.UUID;

import io.github.sye1321.paylab.run.ScenarioId;

public record ConformanceEvaluation(UUID runId, ScenarioId scenario, int scenarioVersion,
        ConformanceVerdict verdict, List<AssertionEvaluation> assertions) {
}
