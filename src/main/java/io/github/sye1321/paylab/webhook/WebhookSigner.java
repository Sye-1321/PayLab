package io.github.sye1321.paylab.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookSigner {

    private final byte[] secret;

    public WebhookSigner(@Value("${paylab.webhook.signing-secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Webhook signing secret must be configured");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(long timestampEpochSeconds, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(Long.toString(timestampEpochSeconds).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", impossible);
        }
    }
}
