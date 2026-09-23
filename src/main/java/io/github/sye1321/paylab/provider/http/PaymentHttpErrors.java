package io.github.sye1321.paylab.provider.http;

import io.github.sye1321.paylab.provider.IdempotencyConflictException;
import io.github.sye1321.paylab.provider.PaymentNotFoundException;
import io.github.sye1321.paylab.run.PreCommitFailureException;
import io.github.sye1321.paylab.run.ResponseDelayApplier;
import io.github.sye1321.paylab.run.TestRunNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = PaymentController.class)
public class PaymentHttpErrors {

    private final ResponseDelayApplier responseDelays;

    public PaymentHttpErrors(ResponseDelayApplier responseDelays) {
        this.responseDelays = responseDelays;
    }

    @ExceptionHandler(PreCommitFailureException.class)
    ResponseEntity<ApiError> preCommitFailure(PreCommitFailureException failure) {
        responseDelays.delay(failure.responseDelayMillis());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("PRE_COMMIT_FAILURE", "Payment creation failed before provider commit"));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("IDEMPOTENCY_CONFLICT", "Idempotency key belongs to a different payment intent"));
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("PAYMENT_NOT_FOUND", "Payment not found"));
    }

    @ExceptionHandler(TestRunNotFoundException.class)
    ResponseEntity<ApiError> runNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("TEST_RUN_NOT_FOUND", "Test run not found"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> badRequest() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Invalid payment request"));
    }

    public record ApiError(String code, String message) {
    }
}
