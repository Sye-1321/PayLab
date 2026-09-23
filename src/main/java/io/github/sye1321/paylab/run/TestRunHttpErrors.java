package io.github.sye1321.paylab.run;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = TestRunController.class)
public class TestRunHttpErrors {

    @ExceptionHandler(TestRunNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(404).body(new ApiError("TEST_RUN_NOT_FOUND", "Test run not found"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> badRequest() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_RUN_REQUEST", "Invalid test run request"));
    }

    public record ApiError(String code, String message) {
    }
}
