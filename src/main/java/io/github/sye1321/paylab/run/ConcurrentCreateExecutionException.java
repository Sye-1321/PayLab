package io.github.sye1321.paylab.run;

public class ConcurrentCreateExecutionException extends RuntimeException {

    public ConcurrentCreateExecutionException(String message) {
        super(message);
    }

    public ConcurrentCreateExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
