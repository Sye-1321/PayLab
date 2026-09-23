package io.github.sye1321.paylab.run;

public final class TestRunNotFoundException extends RuntimeException {

    public TestRunNotFoundException(TestRunId runId) {
        super("Test run not found: " + runId);
    }
}
