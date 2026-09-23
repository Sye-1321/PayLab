package io.github.sye1321.paylab.conformance;

import io.github.sye1321.paylab.run.ScenarioId;

public class UnsupportedConformanceScenarioException extends RuntimeException {

    public UnsupportedConformanceScenarioException(ScenarioId scenario) {
        super("Conformance evaluation is not supported for scenario: " + scenario);
    }
}
