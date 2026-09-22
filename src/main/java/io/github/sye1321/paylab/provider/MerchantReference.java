package io.github.sye1321.paylab.provider;

public record MerchantReference(String value) {

    public MerchantReference {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Merchant reference must not be blank");
        }
    }
}
