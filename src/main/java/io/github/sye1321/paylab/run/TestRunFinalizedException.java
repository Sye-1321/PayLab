package io.github.sye1321.paylab.run;

public class TestRunFinalizedException extends RuntimeException {

    public TestRunFinalizedException(TestRunId runId) {
        super("Test run is finalized: " + runId.value());
    }
}
