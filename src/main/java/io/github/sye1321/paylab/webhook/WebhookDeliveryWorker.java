package io.github.sye1321.paylab.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import io.github.sye1321.paylab.webhook.JdbcWebhookStore.ClaimedDelivery;
import io.github.sye1321.paylab.webhook.JdbcWebhookStore.DeliveryOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WebhookDeliveryWorker {

    private final JdbcWebhookStore store;
    private final WebhookSigner signer;
    private final HttpClient http;
    private final Duration requestTimeout;

    public WebhookDeliveryWorker(JdbcWebhookStore store, WebhookSigner signer,
            @Value("${paylab.webhook.connect-timeout:2s}") Duration connectTimeout,
            @Value("${paylab.webhook.request-timeout:5s}") Duration requestTimeout) {
        this.store = store;
        this.signer = signer;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.requestTimeout = requestTimeout;
    }

    @Scheduled(fixedDelayString = "${paylab.webhook.worker-delay:1s}",
            initialDelayString = "${paylab.webhook.worker-delay:1s}")
    public void deliverOneDue() {
        store.claimDue().ifPresent(this::deliver);
    }

    private void deliver(ClaimedDelivery claim) {
        Instant attemptedAt = Instant.now();
        long timestamp = attemptedAt.getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(claim.webhookUrl()))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("PayLab-Event-Id", claim.eventId().toString())
                .header("PayLab-Timestamp", Long.toString(timestamp))
                .header("PayLab-Signature", signature(timestamp, claim))
                .POST(HttpRequest.BodyPublishers.ofByteArray(claim.payload()))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            DeliveryOutcome outcome = response.statusCode() >= 200 && response.statusCode() < 300
                    ? DeliveryOutcome.DELIVERED : DeliveryOutcome.NON_2XX;
            store.recordResult(claim, attemptedAt, response.statusCode(), outcome);
        } catch (IOException networkFailure) {
            store.recordResult(claim, attemptedAt, null, DeliveryOutcome.NETWORK_ERROR);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            store.recordResult(claim, attemptedAt, null, DeliveryOutcome.NETWORK_ERROR);
        }
    }

    private String signature(long timestamp, ClaimedDelivery claim) {
        String valid = signer.sign(timestamp, claim.payload());
        if (claim.signatureMode() == SignatureMode.VALID) {
            return valid;
        }
        char replacement = valid.charAt(0) == '0' ? '1' : '0';
        return replacement + valid.substring(1);
    }
}
