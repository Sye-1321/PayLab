package io.github.sye1321.paylab.run;

public final class TestRunNotFinalizedException extends RuntimeException {

    public TestRunNotFinalizedException(TestRunId runId) {
        super("Test run is not finalized: " + runId.value());
    }
}
