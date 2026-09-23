package io.github.sye1321.paylab.run;

public class PreCommitFailureException extends RuntimeException {

    private final int responseDelayMillis;

    public PreCommitFailureException(int responseDelayMillis) {
        super("Payment creation failed before provider commit");
        this.responseDelayMillis = responseDelayMillis;
    }

    public int responseDelayMillis() {
        return responseDelayMillis;
    }
}
