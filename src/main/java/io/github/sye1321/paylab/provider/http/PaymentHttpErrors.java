package io.github.sye1321.paylab.provider.http;

import io.github.sye1321.paylab.provider.IdempotencyConflictException;
import io.github.sye1321.paylab.provider.PaymentNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class PaymentHttpErrors {

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

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> badRequest() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Invalid payment request"));
    }

    public record ApiError(String code, String message) {
    }
}
