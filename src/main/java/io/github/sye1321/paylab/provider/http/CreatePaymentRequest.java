package io.github.sye1321.paylab.provider.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreatePaymentRequest(
        @NotNull @Positive Long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank String merchantReference) {
}
