package io.github.sye1321.paylab.webhook;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WebhookSignerTests {

    @Test
    void signsTimestampDotExactRawBodyWithHmacSha256() {
        WebhookSigner signer = new WebhookSigner("secret");
        byte[] body = "{\"status\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);

        assertEquals("ae88a10158147ce8cc507166fd376eec58543868b50a55576038348a5aae4978",
                signer.sign(1700000000L, body));
        assertNotEquals(signer.sign(1700000000L, body),
                signer.sign(1700000000L, "{\"status\": \"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8)));
    }
}
