package io.github.sye1321.paylab.provider;

import java.util.Objects;

public record Money(long minorUnits, String currency) {

    public Money {
        if (minorUnits <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        Objects.requireNonNull(currency, "currency");
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be a three-letter uppercase code");
        }
    }
}
