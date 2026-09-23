package io.github.sye1321.paylab.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookUrlValidatorTests {

    private final WebhookUrlValidator validator = new WebhookUrlValidator("localhost,merchant.test");

    @Test
    void acceptsHttpTargetsOnlyWhenTheirHostIsExplicitlyAllowed() {
        assertEquals("http://localhost:8081/webhooks/paylab",
                validator.validate("http://localhost:8081/webhooks/paylab"));
        assertEquals("https://merchant.test/callback", validator.validate("https://merchant.test/callback"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("http://user@localhost/callback"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("https://example.com/callback"));
    }
}
