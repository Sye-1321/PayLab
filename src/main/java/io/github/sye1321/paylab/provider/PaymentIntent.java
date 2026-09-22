package io.github.sye1321.paylab.provider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record PaymentIntent(Money amount, MerchantReference merchantReference) {

    private static final String OPERATION = "CREATE_PAYMENT";

    public PaymentIntent {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(merchantReference, "merchantReference");
    }

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, OPERATION);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(amount.minorUnits()).array());
            append(digest, amount.currency());
            append(digest, merchantReference.value());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void append(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
