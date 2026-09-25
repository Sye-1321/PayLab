package io.github.sye1321.paylab.provider.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.sye1321.paylab.provider.MerchantReference;
import io.github.sye1321.paylab.provider.Money;
import io.github.sye1321.paylab.provider.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentHttpTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-bookworm");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID_BODY = """
            {"amountMinor":10000,"currency":"ETB","merchantReference":"order-123"}
            """;
    private static final String DIFFERENT_BODY = """
            {"amountMinor":12500,"currency":"ETB","merchantReference":"order-456"}
            """;

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private String runId;

    @BeforeEach
    void createRun() throws Exception {
        HttpResponse<String> response = postRun();
        assertEquals(201, response.statusCode());
        runId = JSON.readTree(response.body()).get("runId").asText();
    }

    @Test
    void runSelectionIsPersistedAndReadable() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test-runs/" + runId))
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(runId, body.get("runId").asText());
        assertEquals("ASYNC_SUCCESS", body.get("scenario").asText());
        assertEquals(1, body.get("scenarioVersion").asInt());
        assertEquals("http://localhost:8081/webhooks/paylab", body.get("webhookUrl").asText());
        assertFalse(body.has("seed"));
        JsonNode events = getEvents();
        assertEquals(1, events.size());
        assertEquals("RUN_STARTED", events.get(0).get("eventType").asText());
        assertEquals(runId, events.get(0).get("runId").asText());
        assertFalse(events.get(0).get("occurredAt").asText().isBlank());
    }

    @Test
    void asyncSuccessCompletesPaymentAndSchedulesOneDurableWebhook() throws Exception {
        String key = UUID.randomUUID().toString();
        HttpResponse<String> response = post(key, VALID_BODY);

        assertEquals(200, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertFalse(body.get("paymentId").asText().isBlank());
        assertEquals(10000, body.get("amountMinor").asLong());
        assertEquals("ETB", body.get("currency").asText());
        assertEquals("order-123", body.get("merchantReference").asText());
        assertEquals("SUCCEEDED", body.get("status").asText());
        assertFalse(body.has("requestFingerprint"));
        JsonNode events = getEvents();
        assertEquals(4, events.size());
        JsonNode observed = events.get(1);
        assertEquals("MERCHANT_REQUEST_OBSERVED", observed.get("eventType").asText());
        assertEquals(key, observed.get("idempotencyKey").asText());
        assertEquals(new PaymentIntent(new Money(10000, "ETB"),
                new MerchantReference("order-123")).fingerprint(), observed.get("requestFingerprint").asText());
        assertEquals(observed.get("requestFingerprint").asText(), jdbc.queryForObject(
                "SELECT request_fingerprint FROM provider_payments WHERE payment_id = ?", String.class,
                body.get("paymentId").asText()));
        JsonNode resolved = events.get(2);
        assertEquals("PAYMENT_REQUEST_RESOLVED", resolved.get("eventType").asText());
        assertEquals(key, resolved.get("idempotencyKey").asText());
        assertEquals(observed.get("requestFingerprint").asText(), resolved.get("requestFingerprint").asText());
        assertEquals(body.get("paymentId").asText(), resolved.get("paymentId").asText());
        assertTrue(observed.get("eventId").asLong() < resolved.get("eventId").asLong());
        assertEquals("SUCCEEDED", JSON.readTree(get(body.get("paymentId").asText()).body())
                .get("status").asText());
        assertEquals(1L, count("webhook_events"));
        assertEquals(1L, count("webhook_deliveries"));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT d.status FROM webhook_deliveries d
                JOIN webhook_events e ON e.event_id = d.event_id
                WHERE e.run_id = ? AND e.payment_id = ?
                """, String.class, UUID.fromString(runId), body.get("paymentId").asText()));
        assertEquals("WEBHOOK_SCHEDULED", events.get(3).get("eventType").asText());
    }

    @Test
    void duplicateWebhookSchedulesOneEventWithTwoDeliveryTarget() throws Exception {
        HttpResponse<String> run = postRun("""
                {"scenario":"DUPLICATE_WEBHOOK","webhookUrl":"http://localhost:8081/webhooks/paylab"}
                """);
        assertEquals(201, run.statusCode());
        runId = JSON.readTree(run.body()).get("runId").asText();

        HttpResponse<String> response = post(UUID.randomUUID().toString(), VALID_BODY);

        assertEquals(200, response.statusCode());
        assertEquals("SUCCEEDED", JSON.readTree(response.body()).get("status").asText());
        assertEquals(1L, count("provider_payments"));
        assertEquals(1L, count("webhook_events"));
        assertEquals(1L, count("webhook_deliveries"));
        assertEquals(2, jdbc.queryForObject("""
                SELECT d.target_delivery_count FROM webhook_deliveries d
                JOIN webhook_events e ON e.event_id = d.event_id WHERE e.run_id = ?
                """, Integer.class, UUID.fromString(runId)));
        assertEquals(0, jdbc.queryForObject("""
                SELECT d.attempt_count FROM webhook_deliveries d
                JOIN webhook_events e ON e.event_id = d.event_id WHERE e.run_id = ?
                """, Integer.class, UUID.fromString(runId)));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT d.status FROM webhook_deliveries d
                JOIN webhook_events e ON e.event_id = d.event_id WHERE e.run_id = ?
                """, String.class, UUID.fromString(runId)));
        assertEquals(1, runEventCount("PAYMENT_COMMITTED"));
        assertEquals(1, runEventCount("WEBHOOK_SCHEDULED"));
    }

    @Test
    void webhookRetryCreatesOneSucceededPaymentAndPersistsOneFailureRetryAllowance() throws Exception {
        HttpResponse<String> run = postRun("""
                {"scenario":"WEBHOOK_RETRY","webhookUrl":"http://localhost:8081/webhooks/paylab"}
                """);
        assertEquals(201, run.statusCode());
        runId = JSON.readTree(run.body()).get("runId").asText();

        HttpResponse<String> response = post(UUID.randomUUID().toString(), VALID_BODY);

        assertEquals(200, response.statusCode());
        assertEquals("SUCCEEDED", JSON.readTree(response.body()).get("status").asText());
        assertEquals(1L, count("provider_payments"));
        assertEquals(1L, count("webhook_events"));
        assertEquals(1L, count("webhook_deliveries"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT d.max_failure_retries FROM webhook_deliveries d
                JOIN webhook_events e ON e.event_id = d.event_id WHERE e.run_id = ?
                """, Integer.class, UUID.fromString(runId)));
        assertEquals(1, jdbc.queryForObject("""
                SELECT d.target_delivery_count FROM webhook_deliveries d
                JOIN webhook_events e ON e.event_id = d.event_id WHERE e.run_id = ?
                """, Integer.class, UUID.fromString(runId)));
        assertEquals(1, runEventCount("PAYMENT_COMMITTED"));
        assertEquals(1, runEventCount("WEBHOOK_SCHEDULED"));
    }

    @Test
    void invalidSignatureScenarioPersistsOneImmutableInvalidDeliveryAcrossReplay() throws Exception {
        HttpResponse<String> run = postRun("""
                {"scenario":"INVALID_SIGNATURE","webhookUrl":"http://localhost:8081/webhooks/paylab"}
                """);
        assertEquals(201, run.statusCode());
        runId = JSON.readTree(run.body()).get("runId").asText();
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = post(key, VALID_BODY);
        HttpResponse<String> replay = post(key, VALID_BODY);

        assertEquals(200, first.statusCode());
        assertEquals(200, replay.statusCode());
        assertEquals(JSON.readTree(first.body()).get("paymentId").asText(),
                JSON.readTree(replay.body()).get("paymentId").asText());
        assertEquals("SUCCEEDED", JSON.readTree(replay.body()).get("status").asText());
        assertEquals(1L, count("provider_payments"));
        assertEquals(1L, count("webhook_events"));
        assertEquals(1L, count("webhook_deliveries"));
        assertEquals(1, runEventCount("PAYMENT_COMMITTED"));
        assertEquals(1, runEventCount("WEBHOOK_SCHEDULED"));
        var policy = jdbc.queryForMap("""
                SELECT d.signature_mode, d.target_delivery_count, d.max_failure_retries,
                       d.attempt_count, d.acknowledged_delivery_count, d.failure_count, d.status
                FROM webhook_deliveries d
                JOIN webhook_events e ON e.event_id = d.event_id WHERE e.run_id = ?
                """, UUID.fromString(runId));
        assertEquals("INVALID", policy.get("signature_mode"));
        assertEquals(1, policy.get("target_delivery_count"));
        assertEquals(0, policy.get("max_failure_retries"));
        assertEquals(0, policy.get("attempt_count"));
        assertEquals(0, policy.get("acknowledged_delivery_count"));
        assertEquals(0, policy.get("failure_count"));
        assertEquals("PENDING", policy.get("status"));
    }

    @Test
    void outOfOrderWebhookReplayKeepsOnePaymentAndOneImmutableEventPair() throws Exception {
        HttpResponse<String> run = postRun("""
                {"scenario":"OUT_OF_ORDER_WEBHOOK","webhookUrl":"http://localhost:8081/webhooks/paylab"}
                """);
        assertEquals(201, run.statusCode());
        runId = JSON.readTree(run.body()).get("runId").asText();
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = post(key, VALID_BODY);
        var beforeReplay = jdbc.queryForList("""
                SELECT e.event_id, e.event_type, encode(e.payload, 'hex') AS payload,
                       d.status, d.due_at::text AS due_at
                FROM webhook_events e JOIN webhook_deliveries d ON d.event_id = e.event_id
                WHERE e.run_id = ? ORDER BY e.event_type
                """, UUID.fromString(runId));
        HttpResponse<String> replay = post(key, VALID_BODY);
        var afterReplay = jdbc.queryForList("""
                SELECT e.event_id, e.event_type, encode(e.payload, 'hex') AS payload,
                       d.status, d.due_at::text AS due_at
                FROM webhook_events e JOIN webhook_deliveries d ON d.event_id = e.event_id
                WHERE e.run_id = ? ORDER BY e.event_type
                """, UUID.fromString(runId));

        assertEquals(200, first.statusCode());
        assertEquals(200, replay.statusCode());
        assertEquals(JSON.readTree(first.body()).get("paymentId").asText(),
                JSON.readTree(replay.body()).get("paymentId").asText());
        assertEquals(1L, count("provider_payments"));
        assertEquals(2L, count("webhook_events"));
        assertEquals(2L, count("webhook_deliveries"));
        assertEquals(1, runEventCount("PAYMENT_COMMITTED"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM webhook_events WHERE run_id = ? AND event_type = 'PAYMENT_PROCESSING'
                """, Integer.class, UUID.fromString(runId)));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM webhook_events WHERE run_id = ? AND event_type = 'PAYMENT_SUCCEEDED'
                """, Integer.class, UUID.fromString(runId)));
        assertEquals(beforeReplay, afterReplay);
    }

    @Test
    void equivalentReplayIsScenarioIdempotent() throws Exception {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = post(key, VALID_BODY);
        HttpResponse<String> replay = post(key, VALID_BODY);

        assertEquals(200, first.statusCode());
        assertEquals(200, replay.statusCode());
        assertEquals(JSON.readTree(first.body()).get("paymentId").asText(),
                JSON.readTree(replay.body()).get("paymentId").asText());
        assertEquals("SUCCEEDED", JSON.readTree(replay.body()).get("status").asText());
        JsonNode events = getEvents();
        assertEquals(6, events.size());
        assertEquals("RUN_STARTED", events.get(0).get("eventType").asText());
        assertEquals("MERCHANT_REQUEST_OBSERVED", events.get(1).get("eventType").asText());
        assertEquals("PAYMENT_REQUEST_RESOLVED", events.get(2).get("eventType").asText());
        assertEquals("WEBHOOK_SCHEDULED", events.get(3).get("eventType").asText());
        assertEquals("MERCHANT_REQUEST_OBSERVED", events.get(4).get("eventType").asText());
        assertEquals("PAYMENT_REQUEST_RESOLVED", events.get(5).get("eventType").asText());
        assertEquals(key, events.get(1).get("idempotencyKey").asText());
        assertEquals(key, events.get(2).get("idempotencyKey").asText());
        assertEquals(key, events.get(4).get("idempotencyKey").asText());
        assertEquals(key, events.get(5).get("idempotencyKey").asText());
        assertEquals(events.get(1).get("requestFingerprint").asText(),
                events.get(5).get("requestFingerprint").asText());
        assertEquals(events.get(2).get("paymentId").asText(), events.get(5).get("paymentId").asText());
        assertFalse(events.get(2).get("eventId").asLong() == events.get(5).get("eventId").asLong());
        assertEquals(1L, count("provider_payments"));
        assertEquals(1L, count("webhook_events"));
        assertEquals(1L, count("webhook_deliveries"));
        assertEquals(1, eventCount(events, "WEBHOOK_SCHEDULED"));
        assertEquals(2, eventCount(events, "MERCHANT_REQUEST_OBSERVED"));
        assertEquals(2, eventCount(events, "PAYMENT_REQUEST_RESOLVED"));
    }

    @Test
    void sameKeyRetryResolvesOnePaymentAndPassesConformance() throws Exception {
        runId = createSameKeyRetryRun();
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = post(key, VALID_BODY);
        HttpResponse<String> retry = post(key, VALID_BODY);

        assertEquals(200, first.statusCode());
        assertEquals(200, retry.statusCode());
        JsonNode firstBody = JSON.readTree(first.body());
        JsonNode retryBody = JSON.readTree(retry.body());
        assertEquals("SUCCEEDED", firstBody.get("status").asText());
        assertEquals("SUCCEEDED", retryBody.get("status").asText());
        assertEquals(firstBody.get("paymentId").asText(), retryBody.get("paymentId").asText());
        assertEquals(1L, count("provider_payments"));
        assertEquals(1, runEventCount("PAYMENT_COMMITTED"));
        assertEquals(2, runEventCount("PAYMENT_REQUEST_RESOLVED"));
        assertEquals(0L, count("webhook_events"));
        assertEquals(0L, count("webhook_deliveries"));

        JsonNode events = getEvents();
        assertEquals(6, events.size());
        assertEquals("RUN_STARTED", events.get(0).get("eventType").asText());
        assertEquals("MERCHANT_REQUEST_OBSERVED", events.get(1).get("eventType").asText());
        assertEquals("PAYMENT_REQUEST_RESOLVED", events.get(2).get("eventType").asText());
        assertEquals("PAYMENT_COMMITTED", events.get(3).get("eventType").asText());
        assertEquals("MERCHANT_REQUEST_OBSERVED", events.get(4).get("eventType").asText());
        assertEquals("PAYMENT_REQUEST_RESOLVED", events.get(5).get("eventType").asText());
        assertEquals(key, events.get(2).get("idempotencyKey").asText());
        assertEquals(key, events.get(5).get("idempotencyKey").asText());
        assertEquals(events.get(2).get("requestFingerprint").asText(),
                events.get(5).get("requestFingerprint").asText());
        assertEquals(firstBody.get("paymentId").asText(), events.get(2).get("paymentId").asText());
        assertEquals(events.get(2).get("paymentId").asText(), events.get(5).get("paymentId").asText());
        assertFalse(events.get(2).get("eventId").asLong() == events.get(5).get("eventId").asLong());

        int beforeEvaluation = events.size();
        JsonNode evaluation = conformance();
        assertEquals("SAME_KEY_RETRY", evaluation.get("scenario").asText());
        assertEquals("PASS", evaluation.get("verdict").asText());
        JsonNode assertion = evaluation.get("assertions").get(0);
        assertEquals("IDEMPOTENT_REPLAY", assertion.get("assertionId").asText());
        assertEquals("INV-02", assertion.get("invariantId").asText());
        assertEquals("PASS", assertion.get("verdict").asText());
        assertEvidence(assertion.get("evidenceEventIds"),
                events.get(1).get("eventId").asLong(), events.get(2).get("eventId").asLong(),
                events.get(3).get("eventId").asLong(), events.get(4).get("eventId").asLong(),
                events.get(5).get("eventId").asLong());
        assertEquals(beforeEvaluation, getEvents().size());
    }

    @Test
    void finalizedConformanceIsImmutableAndRejectsNewMerchantOperations() throws Exception {
        runId = createSameKeyRetryRun();
        String key = UUID.randomUUID().toString();
        HttpResponse<String> originalPayment = post(key, VALID_BODY);
        assertEquals(200, originalPayment.statusCode());
        JsonNode payment = JSON.readTree(originalPayment.body());
        assertEquals(200, post(key, VALID_BODY).statusCode());

        JsonNode live = conformance();
        assertEquals("PASS", live.get("verdict").asText());

        HttpResponse<String> finalizedResponse = finalizeRun();
        assertEquals(200, finalizedResponse.statusCode());
        JsonNode finalized = JSON.readTree(finalizedResponse.body());
        assertEquals(live, finalized);

        HttpRequest runRequest = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test-runs/" + runId)).GET().build();
        HttpResponse<String> runResponse = HTTP.send(runRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, runResponse.statusCode());
        JsonNode run = JSON.readTree(runResponse.body());
        assertFalse(run.get("finalizedAt").isNull());

        HttpResponse<String> repeatedFinalization = finalizeRun();
        assertEquals(200, repeatedFinalization.statusCode());
        assertEquals(finalized, JSON.readTree(repeatedFinalization.body()));
        int observedBeforeRejection = runEventCount("MERCHANT_REQUEST_OBSERVED");
        int queriesBeforeRejection = runEventCount("MERCHANT_STATUS_QUERY_OBSERVED");

        HttpResponse<String> rejectedCreate = post("late-key", VALID_BODY);
        HttpResponse<String> rejectedLookup = get(payment.get("paymentId").asText());

        assertEquals(409, rejectedCreate.statusCode());
        assertEquals("TEST_RUN_FINALIZED", JSON.readTree(rejectedCreate.body()).get("code").asText());
        assertEquals(409, rejectedLookup.statusCode());
        assertEquals("TEST_RUN_FINALIZED", JSON.readTree(rejectedLookup.body()).get("code").asText());
        assertEquals(observedBeforeRejection, runEventCount("MERCHANT_REQUEST_OBSERVED"));
        assertEquals(queriesBeforeRejection, runEventCount("MERCHANT_STATUS_QUERY_OBSERVED"));

        String fingerprint = event(getEvents(), "MERCHANT_REQUEST_OBSERVED", 0)
                .get("requestFingerprint").asText();
        appendObserved("straggler-key", fingerprint);

        assertEquals(finalized, conformance());
    }

    @Test
    void sameKeyRetryEquivalentNewKeyTakesFailurePrecedence() throws Exception {
        runId = createSameKeyRetryRun();
        assertEquals(200, post("original-key", VALID_BODY).statusCode());
        assertEquals(200, post("original-key", VALID_BODY).statusCode());
        assertEquals(200, post("unsafe-key", VALID_BODY).statusCode());

        JsonNode events = getEvents();
        JsonNode originalObserved = event(events, "MERCHANT_REQUEST_OBSERVED", 0);
        JsonNode originalResolved = event(events, "PAYMENT_REQUEST_RESOLVED", 0);
        JsonNode committed = event(events, "PAYMENT_COMMITTED", 0);
        JsonNode unsafeObserved = event(events, "MERCHANT_REQUEST_OBSERVED", 2);
        JsonNode evaluation = conformance();
        JsonNode assertion = evaluation.get("assertions").get(0);

        assertEquals("FAIL", evaluation.get("verdict").asText());
        assertEquals("IDEMPOTENT_REPLAY", assertion.get("assertionId").asText());
        assertEquals("INV-02", assertion.get("invariantId").asText());
        assertEquals("FAIL", assertion.get("verdict").asText());
        assertEquals("unsafe-key", unsafeObserved.get("idempotencyKey").asText());
        assertEvidence(assertion.get("evidenceEventIds"),
                originalObserved.get("eventId").asLong(), originalResolved.get("eventId").asLong(),
                committed.get("eventId").asLong(), unsafeObserved.get("eventId").asLong());
    }

    @Test
    void sameKeyRetryOverlappingEquivalentObservationsAreInconclusive() throws Exception {
        runId = createSameKeyRetryRun();
        String key = "ambiguous-key";
        JsonNode original = JSON.readTree(post(key, VALID_BODY).body());
        JsonNode events = getEvents();
        String fingerprint = event(events, "PAYMENT_REQUEST_RESOLVED", 0).get("requestFingerprint").asText();

        appendObserved(key, fingerprint);
        appendObserved(key, fingerprint);
        appendResolved(key, fingerprint, original.get("paymentId").asText());

        JsonNode evidence = getEvents();
        JsonNode assertion = conformance().get("assertions").get(0);
        assertEquals("INCONCLUSIVE", assertion.get("verdict").asText());
        assertTrue(assertion.get("explanation").asText().contains("Multiple equivalent retry observations"));
        assertEvidence(assertion.get("evidenceEventIds"),
                evidence.get(1).get("eventId").asLong(), evidence.get(2).get("eventId").asLong(),
                evidence.get(3).get("eventId").asLong(), evidence.get(4).get("eventId").asLong(),
                evidence.get(5).get("eventId").asLong(), evidence.get(6).get("eventId").asLong());
    }

    @Test
    void sameKeyRetryLaterContradictoryResolutionOverridesSafeReplay() throws Exception {
        runId = createSameKeyRetryRun();
        String key = "contradictory-key";
        JsonNode original = JSON.readTree(post(key, VALID_BODY).body());
        assertEquals(200, post(key, VALID_BODY).statusCode());
        JsonNode events = getEvents();
        String fingerprint = event(events, "PAYMENT_REQUEST_RESOLVED", 0).get("requestFingerprint").asText();
        String contradictoryPaymentId = UUID.randomUUID().toString();

        appendObserved(key, fingerprint);
        jdbc.update("""
                INSERT INTO provider_payments
                    (payment_id, run_id, idempotency_key, request_fingerprint, amount_minor_units,
                     currency, merchant_reference, status)
                VALUES (?, ?, ?, ?, 10000, 'ETB', 'contradictory-fixture', 'SUCCEEDED')
                """, contradictoryPaymentId, UUID.fromString(runId), "contradictory-fixture-key", fingerprint);
        appendResolved(key, fingerprint, contradictoryPaymentId);

        JsonNode evidence = getEvents();
        JsonNode evaluation = conformance();
        JsonNode assertion = evaluation.get("assertions").get(0);
        assertEquals("FAIL", evaluation.get("verdict").asText());
        assertEquals("IDEMPOTENT_REPLAY", assertion.get("assertionId").asText());
        assertEquals("INV-02", assertion.get("invariantId").asText());
        assertEquals("FAIL", assertion.get("verdict").asText());
        assertTrue(assertion.get("explanation").asText().contains("different logical provider payments"));
        assertEvidence(assertion.get("evidenceEventIds"),
                evidence.get(1).get("eventId").asLong(), evidence.get(2).get("eventId").asLong(),
                evidence.get(3).get("eventId").asLong(), evidence.get(7).get("eventId").asLong());
        assertEquals(original.get("paymentId").asText(), evidence.get(2).get("paymentId").asText());
    }

    @Test
    void sameKeyRetryWithoutRetryIsInconclusive() throws Exception {
        runId = createSameKeyRetryRun();
        assertEquals(200, post("only-key", VALID_BODY).statusCode());

        JsonNode events = getEvents();
        JsonNode evaluation = conformance();
        JsonNode assertion = evaluation.get("assertions").get(0);

        assertEquals("INCONCLUSIVE", evaluation.get("verdict").asText());
        assertEquals("INCONCLUSIVE", assertion.get("verdict").asText());
        assertEvidence(assertion.get("evidenceEventIds"),
                events.get(1).get("eventId").asLong(), events.get(2).get("eventId").asLong(),
                events.get(3).get("eventId").asLong());
    }

    @Test
    void sameKeyRetryDifferentPayloadIsNotEquivalentReplay() throws Exception {
        runId = createSameKeyRetryRun();
        String key = "reused-key";
        assertEquals(200, post(key, VALID_BODY).statusCode());
        assertEquals(409, post(key, """
                {"amountMinor":10001,"currency":"ETB","merchantReference":"order-123"}
                """).statusCode());

        JsonNode evaluation = conformance();
        assertEquals("INCONCLUSIVE", evaluation.get("verdict").asText());
        assertEquals("INCONCLUSIVE", evaluation.get("assertions").get(0).get("verdict").asText());
        assertEquals(1, runEventCount("PAYMENT_REQUEST_RESOLVED"));
    }

    @Test
    void keyReuseDifferentPayloadDistinctKeyCreatesDistinctPaymentAndPasses() throws Exception {
        runId = createKeyReuseDifferentPayloadRun();

        JsonNode first = JSON.readTree(post("intent-key-1", VALID_BODY).body());
        JsonNode second = JSON.readTree(post("intent-key-2", DIFFERENT_BODY).body());

        assertEquals("SUCCEEDED", first.get("status").asText());
        assertEquals("SUCCEEDED", second.get("status").asText());
        assertFalse(first.get("paymentId").asText().equals(second.get("paymentId").asText()));
        assertEquals(2L, count("provider_payments"));
        assertEquals(2, runEventCount("PAYMENT_COMMITTED"));
        assertEquals(2, runEventCount("PAYMENT_REQUEST_RESOLVED"));
        assertEquals(0L, count("webhook_events"));
        assertEquals(0L, count("webhook_deliveries"));

        JsonNode events = getEvents();
        int beforeEvaluation = events.size();
        JsonNode evaluation = conformance();
        JsonNode assertion = evaluation.get("assertions").get(0);
        assertEquals("KEY_REUSE_DIFFERENT_PAYLOAD", evaluation.get("scenario").asText());
        assertEquals("PASS", evaluation.get("verdict").asText());
        assertEquals("IDEMPOTENCY_KEY_SCOPE", assertion.get("assertionId").asText());
        assertEquals("INV-03", assertion.get("invariantId").asText());
        assertEquals("PASS", assertion.get("verdict").asText());
        assertEvidence(assertion.get("evidenceEventIds"),
                events.get(1).get("eventId").asLong(), events.get(2).get("eventId").asLong(),
                events.get(3).get("eventId").asLong(), events.get(4).get("eventId").asLong(),
                events.get(5).get("eventId").asLong(), events.get(6).get("eventId").asLong());
        assertEquals(beforeEvaluation, getEvents().size());
    }

    @Test
    void keyReuseDifferentPayloadSameKeyIsRejectedAndFailsConformance() throws Exception {
        runId = createKeyReuseDifferentPayloadRun();
        JsonNode original = JSON.readTree(post("reused-intent-key", VALID_BODY).body());

        HttpResponse<String> conflict = post("reused-intent-key", DIFFERENT_BODY);

        assertEquals(409, conflict.statusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", JSON.readTree(conflict.body()).get("code").asText());
        assertEquals(1L, count("provider_payments"));
        assertEquals(1, runEventCount("PAYMENT_COMMITTED"));
        assertEquals(1, runEventCount("PAYMENT_REQUEST_RESOLVED"));
        JsonNode persisted = JSON.readTree(get(original.get("paymentId").asText()).body());
        assertEquals("SUCCEEDED", persisted.get("status").asText());
        assertEquals(10000, persisted.get("amountMinor").asLong());
        assertEquals("order-123", persisted.get("merchantReference").asText());

        JsonNode events = getEvents();
        JsonNode evaluation = conformance();
        JsonNode assertion = evaluation.get("assertions").get(0);
        assertEquals("FAIL", evaluation.get("verdict").asText());
        assertEquals("IDEMPOTENCY_KEY_SCOPE", assertion.get("assertionId").asText());
        assertEquals("INV-03", assertion.get("invariantId").asText());
        assertEquals("FAIL", assertion.get("verdict").asText());
        assertEvidence(assertion.get("evidenceEventIds"),
                events.get(1).get("eventId").asLong(), events.get(2).get("eventId").asLong(),
                events.get(3).get("eventId").asLong(), events.get(4).get("eventId").asLong());
    }

    @Test
    void keyReuseDifferentPayloadUnsafeReuseTakesPrecedenceOverSafeIntent() throws Exception {
        runId = createKeyReuseDifferentPayloadRun();
        assertEquals(200, post("original-key", VALID_BODY).statusCode());
        assertEquals(200, post("safe-key", DIFFERENT_BODY).statusCode());
        assertEquals(409, post("original-key", """
                {"amountMinor":15000,"currency":"ETB","merchantReference":"order-789"}
                """).statusCode());

        JsonNode events = getEvents();
        JsonNode assertion = conformance().get("assertions").get(0);
        assertEquals("FAIL", assertion.get("verdict").asText());
        assertEvidence(assertion.get("evidenceEventIds"),
                events.get(1).get("eventId").asLong(), events.get(2).get("eventId").asLong(),
                events.get(3).get("eventId").asLong(), events.get(7).get("eventId").asLong());
    }

    @Test
    void keyReuseDifferentPayloadWithoutSecondIntentIsInconclusive() throws Exception {
        runId = createKeyReuseDifferentPayloadRun();
        assertEquals(200, post("only-intent-key", VALID_BODY).statusCode());

        JsonNode events = getEvents();
        JsonNode assertion = conformance().get("assertions").get(0);
        assertEquals("INCONCLUSIVE", assertion.get("verdict").asText());
        assertEvidence(assertion.get("evidenceEventIds"),
                events.get(1).get("eventId").asLong(), events.get(2).get("eventId").asLong(),
                events.get(3).get("eventId").asLong());
    }

    @Test
    void keyReuseDifferentPayloadUnresolvedSecondIntentIsInconclusive() throws Exception {
        runId = createKeyReuseDifferentPayloadRun();
        assertEquals(200, post("original-key", VALID_BODY).statusCode());
        String fingerprint = new PaymentIntent(new Money(12500, "ETB"),
                new MerchantReference("order-456")).fingerprint();
        appendObserved("distinct-key", fingerprint);

        JsonNode events = getEvents();
        JsonNode assertion = conformance().get("assertions").get(0);
        assertEquals("INCONCLUSIVE", assertion.get("verdict").asText());
        assertEvidence(assertion.get("evidenceEventIds"),
                events.get(1).get("eventId").asLong(), events.get(2).get("eventId").asLong(),
                events.get(3).get("eventId").asLong(), events.get(4).get("eventId").asLong());
    }

    @Test
    void keyReuseDifferentPayloadOverlappingObservationsAreInconclusive() throws Exception {
        runId = createKeyReuseDifferentPayloadRun();
        assertEquals(200, post("original-key", VALID_BODY).statusCode());
        String fingerprint = new PaymentIntent(new Money(12500, "ETB"),
                new MerchantReference("order-456")).fingerprint();
        String fixturePaymentId = insertSucceededPayment("fixture-key", fingerprint, 12500, "order-456");
        appendObserved("distinct-key", fingerprint);
        appendObserved("distinct-key", fingerprint);
        appendResolved("distinct-key", fingerprint, fixturePaymentId);

        JsonNode events = getEvents();
        JsonNode assertion = conformance().get("assertions").get(0);
        assertEquals("INCONCLUSIVE", assertion.get("verdict").asText());
        assertTrue(assertion.get("explanation").asText().contains("Multiple equivalent distinct-intent"));
        assertEvidence(assertion.get("evidenceEventIds"),
                events.get(1).get("eventId").asLong(), events.get(2).get("eventId").asLong(),
                events.get(3).get("eventId").asLong(), events.get(4).get("eventId").asLong(),
                events.get(5).get("eventId").asLong(), events.get(6).get("eventId").asLong());
    }

    @Test
    void keyReuseDifferentPayloadDistinctIntentCannotResolveOriginalPayment() throws Exception {
        runId = createKeyReuseDifferentPayloadRun();
        JsonNode original = JSON.readTree(post("original-key", VALID_BODY).body());
        String fingerprint = new PaymentIntent(new Money(12500, "ETB"),
                new MerchantReference("order-456")).fingerprint();
        appendObserved("distinct-key", fingerprint);
        appendResolved("distinct-key", fingerprint, original.get("paymentId").asText());

        JsonNode events = getEvents();
        JsonNode assertion = conformance().get("assertions").get(0);
        assertEquals("FAIL", assertion.get("verdict").asText());
        assertTrue(assertion.get("explanation").asText().contains("collapsed"));
        assertEvidence(assertion.get("evidenceEventIds"),
                events.get(1).get("eventId").asLong(), events.get(2).get("eventId").asLong(),
                events.get(3).get("eventId").asLong(), events.get(4).get("eventId").asLong(),
                events.get(5).get("eventId").asLong());
    }

    @Test
    void keyReuseDifferentPayloadLaterContradictoryResolutionOverridesSafeIntent() throws Exception {
        runId = createKeyReuseDifferentPayloadRun();
        JsonNode first = JSON.readTree(post("original-key", VALID_BODY).body());
        JsonNode second = JSON.readTree(post("distinct-key", DIFFERENT_BODY).body());
        assertEquals("SUCCEEDED", first.get("status").asText());
        assertEquals("SUCCEEDED", second.get("status").asText());
        assertFalse(first.get("paymentId").asText().equals(second.get("paymentId").asText()));

        JsonNode safeEvents = getEvents();
        JsonNode safeResolution = event(safeEvents, "PAYMENT_REQUEST_RESOLVED", 1);
        assertEquals(second.get("paymentId").asText(), safeResolution.get("paymentId").asText());
        appendResolved("distinct-key", safeResolution.get("requestFingerprint").asText(),
                first.get("paymentId").asText());

        JsonNode evidence = getEvents();
        JsonNode contradictoryResolution = event(evidence, "PAYMENT_REQUEST_RESOLVED", 2);
        JsonNode evaluation = conformance();
        JsonNode assertion = evaluation.get("assertions").get(0);
        assertEquals("KEY_REUSE_DIFFERENT_PAYLOAD", evaluation.get("scenario").asText());
        assertEquals("FAIL", evaluation.get("verdict").asText());
        assertEquals("IDEMPOTENCY_KEY_SCOPE", assertion.get("assertionId").asText());
        assertEquals("INV-03", assertion.get("invariantId").asText());
        assertEquals("FAIL", assertion.get("verdict").asText());
        assertEquals(first.get("paymentId").asText(), contradictoryResolution.get("paymentId").asText());
        assertEvidence(assertion.get("evidenceEventIds"),
                evidence.get(1).get("eventId").asLong(), evidence.get(2).get("eventId").asLong(),
                evidence.get(3).get("eventId").asLong(), evidence.get(4).get("eventId").asLong(),
                evidence.get(5).get("eventId").asLong(), evidence.get(7).get("eventId").asLong());
    }

    @Test
    void conflictingReplayReturns409AndKeepsOriginal() throws Exception {
        String key = UUID.randomUUID().toString();
        HttpResponse<String> first = post(key, VALID_BODY);
        String id = JSON.readTree(first.body()).get("paymentId").asText();

        HttpResponse<String> conflict = post(key,
                """
                {"amountMinor":10001,"currency":"ETB","merchantReference":"order-123"}
                """);

        assertEquals(409, conflict.statusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", JSON.readTree(conflict.body()).get("code").asText());
        JsonNode events = getEvents();
        assertEquals(5, events.size());
        assertEquals(1, eventCount(events, "PAYMENT_REQUEST_RESOLVED"));
        HttpResponse<String> original = get(id);
        assertEquals(10000, JSON.readTree(original.body()).get("amountMinor").asLong());
    }

    @Test
    void lookupReturnsCurrentProviderState() throws Exception {
        HttpResponse<String> created = post(UUID.randomUUID().toString(), VALID_BODY);
        String id = JSON.readTree(created.body()).get("paymentId").asText();

        HttpResponse<String> response = get(id);

        assertEquals(200, response.statusCode());
        assertEquals(id, JSON.readTree(response.body()).get("paymentId").asText());
        assertEquals("SUCCEEDED", JSON.readTree(response.body()).get("status").asText());
    }

    @Test
    void concurrentDuplicateCreateForcesPostgresRaceAndLaterReplayDoesNotWaitForAnotherPeer() throws Exception {
        HttpResponse<String> run = postRun("""
                {"scenario":"CONCURRENT_DUPLICATE_CREATE"}
                """);
        assertEquals(201, run.statusCode());
        runId = JSON.readTree(run.body()).get("runId").asText();
        String key = UUID.randomUUID().toString();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<HttpResponse<String>> request = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent start timed out");
                }
                return post(key, VALID_BODY);
            };
            Future<HttpResponse<String>> first = executor.submit(request);
            Future<HttpResponse<String>> second = executor.submit(request);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            HttpResponse<String> firstResponse = first.get(30, TimeUnit.SECONDS);
            HttpResponse<String> secondResponse = second.get(30, TimeUnit.SECONDS);
            assertEquals(200, firstResponse.statusCode());
            assertEquals(200, secondResponse.statusCode());
            JsonNode firstBody = JSON.readTree(firstResponse.body());
            JsonNode secondBody = JSON.readTree(secondResponse.body());
            assertEquals(firstBody.get("paymentId").asText(), secondBody.get("paymentId").asText());
            assertEquals("SUCCEEDED", firstBody.get("status").asText());
            assertEquals("SUCCEEDED", secondBody.get("status").asText());
            assertEquals(1L, count("provider_payments"));
            JsonNode events = getEvents();
            assertEquals(2, eventCount(events, "MERCHANT_REQUEST_OBSERVED"));
            assertEquals(2, eventCount(events, "PAYMENT_REQUEST_RESOLVED"));
            assertEquals(1, eventCount(events, "PAYMENT_COMMITTED"));
            JsonNode firstResolved = event(events, "PAYMENT_REQUEST_RESOLVED", 0);
            JsonNode secondResolved = event(events, "PAYMENT_REQUEST_RESOLVED", 1);
            assertEquals(key, firstResolved.get("idempotencyKey").asText());
            assertEquals(key, secondResolved.get("idempotencyKey").asText());
            assertEquals(firstResolved.get("requestFingerprint").asText(),
                    secondResolved.get("requestFingerprint").asText());
            assertEquals(firstBody.get("paymentId").asText(), firstResolved.get("paymentId").asText());
            assertEquals(firstResolved.get("paymentId").asText(), secondResolved.get("paymentId").asText());

            HttpResponse<String> replay = post(key, VALID_BODY, Duration.ofSeconds(2));
            assertEquals(200, replay.statusCode());
            assertEquals(firstBody.get("paymentId").asText(),
                    JSON.readTree(replay.body()).get("paymentId").asText());
            assertEquals("SUCCEEDED", JSON.readTree(replay.body()).get("status").asText());
            assertEquals(1L, count("provider_payments"));
            assertEquals(1, runEventCount("PAYMENT_COMMITTED"));
            assertEquals(3, runEventCount("MERCHANT_REQUEST_OBSERVED"));
            assertEquals(3, runEventCount("PAYMENT_REQUEST_RESOLVED"));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutAfterCommitTimesOutOnceAndSameKeyReplaySafelyResolvesPayment() throws Exception {
        int responseDelayMillis = 5_000;
        runId = createTimeoutRun(responseDelayMillis);
        String key = UUID.randomUUID().toString();
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> firstRequest = executor.submit(
                    () -> post(key, VALID_BODY, Duration.ofMillis(2_000)));

            awaitResponseDelayEvidence(firstRequest);

            assertEquals(1L, count("provider_payments"));
            String paymentId = jdbc.queryForObject("""
                    SELECT payment_id FROM provider_payments WHERE run_id = ?
                    """, String.class, UUID.fromString(runId));
            assertEquals("SUCCEEDED", jdbc.queryForObject("""
                    SELECT status FROM provider_payments WHERE run_id = ? AND payment_id = ?
                    """, String.class, UUID.fromString(runId), paymentId));
            assertEquals(1, runEventCount("PAYMENT_COMMITTED"));
            assertEquals(1, runEventCount("RESPONSE_DELAY_INJECTED"));
            long committedEventId = runEventId("PAYMENT_COMMITTED");
            long delayEventId = runEventId("RESPONSE_DELAY_INJECTED");
            assertTrue(committedEventId < delayEventId);
            assertEquals(responseDelayMillis, jdbc.queryForObject("""
                    SELECT response_delay_millis FROM run_events
                    WHERE run_id = ? AND event_type = 'RESPONSE_DELAY_INJECTED'
                    """, Integer.class, UUID.fromString(runId)));

            ExecutionException timeout = assertThrows(ExecutionException.class,
                    () -> firstRequest.get(5, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, timeout.getCause());

            HttpResponse<String> replay = post(key, VALID_BODY);
            assertEquals(200, replay.statusCode());
            JsonNode payment = JSON.readTree(replay.body());
            assertEquals(paymentId, payment.get("paymentId").asText());
            assertEquals("SUCCEEDED", payment.get("status").asText());
            assertEquals(1L, count("provider_payments"));
            assertEquals(0L, count("webhook_events"));
            assertEquals(0L, count("webhook_deliveries"));
            assertEquals(1, runEventCount("PAYMENT_COMMITTED"));
            assertEquals(1, runEventCount("RESPONSE_DELAY_INJECTED"));
            assertEquals(2, runEventCount("PAYMENT_REQUEST_RESOLVED"));
            assertEquals(2, runEventCount("MERCHANT_REQUEST_OBSERVED"));

            JsonNode events = getEvents();
            assertEquals(7, events.size());
            assertEquals("RUN_STARTED", events.get(0).get("eventType").asText());
            assertEquals("MERCHANT_REQUEST_OBSERVED", events.get(1).get("eventType").asText());
            assertEquals("PAYMENT_REQUEST_RESOLVED", events.get(2).get("eventType").asText());
            assertEquals("PAYMENT_COMMITTED", events.get(3).get("eventType").asText());
            assertEquals(paymentId, events.get(3).get("paymentId").asText());
            assertEquals("RESPONSE_DELAY_INJECTED", events.get(4).get("eventType").asText());
            assertEquals(responseDelayMillis, events.get(4).get("responseDelayMillis").asInt());
            assertEquals("MERCHANT_REQUEST_OBSERVED", events.get(5).get("eventType").asText());
            assertEquals("PAYMENT_REQUEST_RESOLVED", events.get(6).get("eventType").asText());
            assertEquals(key, events.get(2).get("idempotencyKey").asText());
            assertEquals(key, events.get(6).get("idempotencyKey").asText());
            assertEquals(events.get(2).get("requestFingerprint").asText(),
                    events.get(6).get("requestFingerprint").asText());
            assertEquals(paymentId, events.get(2).get("paymentId").asText());
            assertEquals(paymentId, events.get(6).get("paymentId").asText());
            assertFalse(events.get(2).get("eventId").asLong() == events.get(6).get("eventId").asLong());

            int eventCountBeforeEvaluation = events.size();
            JsonNode evaluation = conformance();
            assertEquals("TIMEOUT_AFTER_COMMIT", evaluation.get("scenario").asText());
            assertEquals(1, evaluation.get("scenarioVersion").asInt());
            assertEquals("PASS", evaluation.get("verdict").asText());
            JsonNode assertion = evaluation.get("assertions").get(0);
            assertEquals("AMBIGUOUS_OUTCOME_RECOVERY", assertion.get("assertionId").asText());
            assertEquals("INV-01", assertion.get("invariantId").asText());
            assertEquals("PASS", assertion.get("verdict").asText());
            assertEvidence(assertion.get("evidenceEventIds"), events.get(1).get("eventId").asLong(),
                    committedEventId, delayEventId, events.get(5).get("eventId").asLong());
            assertEquals(eventCountBeforeEvaluation, getEvents().size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutBeforeCommitTimesOutWithoutPaymentAndSameKeyRetryCreatesOneSucceededPayment() throws Exception {
        int responseDelayMillis = 5_000;
        runId = createTimeoutBeforeCommitRun(responseDelayMillis);
        String key = UUID.randomUUID().toString();
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> firstRequest = executor.submit(
                    () -> post(key, VALID_BODY, Duration.ofMillis(2_000)));

            awaitRunEventEvidence(firstRequest, "PRE_COMMIT_TIMEOUT_INJECTED");
            assertEquals(0L, count("provider_payments"));
            assertEquals(0, runEventCount("PAYMENT_COMMITTED"));
            assertEquals(0, runEventCount("PAYMENT_REQUEST_RESOLVED"));

            ExecutionException timeout = assertThrows(ExecutionException.class,
                    () -> firstRequest.get(5, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, timeout.getCause());

            HttpResponse<String> retry = post(key, VALID_BODY);
            assertEquals(200, retry.statusCode());
            JsonNode payment = JSON.readTree(retry.body());
            assertEquals("SUCCEEDED", payment.get("status").asText());
            assertEquals(1L, count("provider_payments"));
            assertEquals(1, runEventCount("PRE_COMMIT_TIMEOUT_INJECTED"));
            assertEquals(1, runEventCount("PAYMENT_COMMITTED"));
            assertEquals(1, runEventCount("PAYMENT_REQUEST_RESOLVED"));
            assertEquals(0L, count("webhook_events"));
            assertEquals(0L, count("webhook_deliveries"));

            String fingerprint = new PaymentIntent(new Money(10000, "ETB"),
                    new MerchantReference("order-123")).fingerprint();
            assertEquals(key, jdbc.queryForObject("""
                    SELECT idempotency_key FROM provider_payments WHERE run_id = ?
                    """, String.class, UUID.fromString(runId)));
            assertEquals(fingerprint, jdbc.queryForObject("""
                    SELECT request_fingerprint FROM provider_payments WHERE run_id = ?
                    """, String.class, UUID.fromString(runId)));

            JsonNode events = getEvents();
            assertEquals(6, events.size());
            assertEquals("RUN_STARTED", events.get(0).get("eventType").asText());
            assertEquals("MERCHANT_REQUEST_OBSERVED", events.get(1).get("eventType").asText());
            assertEquals("PRE_COMMIT_TIMEOUT_INJECTED", events.get(2).get("eventType").asText());
            assertEquals(key, events.get(2).get("idempotencyKey").asText());
            assertEquals(fingerprint, events.get(2).get("requestFingerprint").asText());
            assertEquals(responseDelayMillis, events.get(2).get("responseDelayMillis").asInt());
            assertTrue(events.get(2).get("paymentId").isNull());
            assertEquals("MERCHANT_REQUEST_OBSERVED", events.get(3).get("eventType").asText());
            assertEquals("PAYMENT_REQUEST_RESOLVED", events.get(4).get("eventType").asText());
            assertEquals(key, events.get(4).get("idempotencyKey").asText());
            assertEquals(fingerprint, events.get(4).get("requestFingerprint").asText());
            assertEquals(payment.get("paymentId").asText(), events.get(4).get("paymentId").asText());
            assertEquals("PAYMENT_COMMITTED", events.get(5).get("eventType").asText());
            long previous = Long.MIN_VALUE;
            for (JsonNode event : events) {
                assertTrue(event.get("eventId").asLong() > previous);
                previous = event.get("eventId").asLong();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutBeforeCommitReturnsExplicitFailureWhenClientWaitsForDelay() throws Exception {
        runId = createTimeoutBeforeCommitRun(20);

        HttpResponse<String> response = post(UUID.randomUUID().toString(), VALID_BODY);

        assertEquals(503, response.statusCode());
        assertEquals("PRE_COMMIT_FAILURE", JSON.readTree(response.body()).get("code").asText());
        assertEquals(0L, count("provider_payments"));
        assertEquals(1, runEventCount("PRE_COMMIT_TIMEOUT_INJECTED"));
    }

    @Test
    void timeoutAfterCommitDifferentKeyPreservesDuplicateRiskEvidence() throws Exception {
        runId = createTimeoutRun(2_000);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> firstRequest = executor.submit(
                    () -> post("first-key", VALID_BODY, Duration.ofMillis(500)));
            awaitResponseDelayEvidence(firstRequest);
            ExecutionException timeout = assertThrows(ExecutionException.class,
                    () -> firstRequest.get(5, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, timeout.getCause());

            HttpResponse<String> second = post("second-key", VALID_BODY);
            assertEquals(200, second.statusCode());
            assertEquals(2L, count("provider_payments"));
            JsonNode events = getEvents();
            assertEquals(2, eventCount(events, "PAYMENT_COMMITTED"));
            JsonNode firstObserved = event(events, "MERCHANT_REQUEST_OBSERVED", 0);
            JsonNode secondObserved = event(events, "MERCHANT_REQUEST_OBSERVED", 1);
            assertEquals("first-key", firstObserved.get("idempotencyKey").asText());
            assertEquals("second-key", secondObserved.get("idempotencyKey").asText());
            assertEquals(firstObserved.get("requestFingerprint").asText(),
                    secondObserved.get("requestFingerprint").asText());

            JsonNode evaluation = conformance();
            assertEquals("FAIL", evaluation.get("verdict").asText());
            JsonNode assertion = evaluation.get("assertions").get(0);
            assertEquals("AMBIGUOUS_OUTCOME_RECOVERY", assertion.get("assertionId").asText());
            assertEquals("INV-01", assertion.get("invariantId").asText());
            assertEquals("FAIL", assertion.get("verdict").asText());
            assertTrue(containsEvidence(assertion.get("evidenceEventIds"), secondObserved.get("eventId").asLong()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutAfterCommitCanBecomePassThroughStatusLookup() throws Exception {
        runId = createTimeoutRun(2_000);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> firstRequest = executor.submit(
                    () -> post("status-key", VALID_BODY, Duration.ofMillis(500)));
            awaitResponseDelayEvidence(firstRequest);
            ExecutionException timeout = assertThrows(ExecutionException.class,
                    () -> firstRequest.get(5, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, timeout.getCause());

            String paymentId = jdbc.queryForObject(
                    "SELECT payment_id FROM provider_payments WHERE run_id = ?", String.class,
                    UUID.fromString(runId));
            JsonNode beforeLookup = conformance();
            assertEquals("INCONCLUSIVE", beforeLookup.get("verdict").asText());

            HttpResponse<String> lookup = get(paymentId);
            assertEquals(200, lookup.statusCode());
            JsonNode events = getEvents();
            JsonNode statusQuery = event(events, "MERCHANT_STATUS_QUERY_OBSERVED", 0);
            assertEquals(paymentId, statusQuery.get("paymentId").asText());

            JsonNode afterLookup = conformance();
            assertEquals("PASS", afterLookup.get("verdict").asText());
            JsonNode assertion = afterLookup.get("assertions").get(0);
            assertEquals("PASS", assertion.get("verdict").asText());
            assertTrue(containsEvidence(assertion.get("evidenceEventIds"), statusQuery.get("eventId").asLong()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void runConfigurationRejectsContradictoryScenarioParameters() throws Exception {
        HttpResponse<String> sameKey = postRun("""
                {"scenario":"SAME_KEY_RETRY"}
                """);
        assertEquals(201, sameKey.statusCode());
        assertEquals(400, postRun("""
                {"scenario":"ASYNC_SUCCESS"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"ASYNC_SUCCESS","webhookUrl":"http://localhost:8081/webhooks/paylab",
                 "responseDelayMillis":10}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"TIMEOUT_AFTER_COMMIT"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"TIMEOUT_AFTER_COMMIT","responseDelayMillis":30001}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"TIMEOUT_AFTER_COMMIT","webhookUrl":"http://localhost/webhook",
                 "responseDelayMillis":10}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"TIMEOUT_BEFORE_COMMIT"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"TIMEOUT_BEFORE_COMMIT","webhookUrl":"http://localhost/webhook",
                 "responseDelayMillis":10}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"DUPLICATE_WEBHOOK"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"DUPLICATE_WEBHOOK","webhookUrl":"http://localhost/webhook",
                 "responseDelayMillis":10}
                """).statusCode());
        assertEquals(201, postRun("""
                {"scenario":"WEBHOOK_RETRY","webhookUrl":"http://localhost:8081/webhooks/paylab"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"WEBHOOK_RETRY"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"WEBHOOK_RETRY","webhookUrl":"http://localhost/webhook",
                 "responseDelayMillis":10}
                """).statusCode());
        assertEquals(201, postRun("""
                {"scenario":"INVALID_SIGNATURE","webhookUrl":"http://localhost:8081/webhooks/paylab"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"INVALID_SIGNATURE"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"INVALID_SIGNATURE","webhookUrl":"http://localhost/webhook",
                 "responseDelayMillis":10}
                """).statusCode());
        assertEquals(201, postRun("""
                {"scenario":"OUT_OF_ORDER_WEBHOOK","webhookUrl":"http://localhost:8081/webhooks/paylab"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"OUT_OF_ORDER_WEBHOOK"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"OUT_OF_ORDER_WEBHOOK","webhookUrl":"http://localhost/webhook",
                 "responseDelayMillis":10}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"SAME_KEY_RETRY","webhookUrl":"http://localhost/webhook"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"SAME_KEY_RETRY","responseDelayMillis":10}
                """).statusCode());
        assertEquals(201, postRun("""
                {"scenario":"KEY_REUSE_DIFFERENT_PAYLOAD"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"KEY_REUSE_DIFFERENT_PAYLOAD","webhookUrl":"http://localhost/webhook"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"KEY_REUSE_DIFFERENT_PAYLOAD","responseDelayMillis":10}
                """).statusCode());
        assertEquals(201, postRun("""
                {"scenario":"CONCURRENT_DUPLICATE_CREATE"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"CONCURRENT_DUPLICATE_CREATE","webhookUrl":"http://localhost/webhook"}
                """).statusCode());
        assertEquals(400, postRun("""
                {"scenario":"CONCURRENT_DUPLICATE_CREATE","responseDelayMillis":10}
                """).statusCode());
    }

    @Test
    void unsupportedConformanceScenarioStillReturns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test-runs/" + runId + "/conformance")).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertEquals("UNSUPPORTED_CONFORMANCE_SCENARIO", JSON.readTree(response.body()).get("code").asText());

        HttpResponse<String> finalizeResponse = finalizeRun();
        assertEquals(400, finalizeResponse.statusCode());
        assertEquals("UNSUPPORTED_CONFORMANCE_SCENARIO",
                JSON.readTree(finalizeResponse.body()).get("code").asText());

        HttpRequest runRequest = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test-runs/" + runId)).GET().build();
        JsonNode run = JSON.readTree(HTTP.send(runRequest, HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(run.get("finalizedAt").isNull());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM finalized_conformance_results WHERE run_id = ?",
                Integer.class, UUID.fromString(runId)));
    }

    @Test
    void missingPaymentReturns404() throws Exception {
        int before = runEventCount("MERCHANT_STATUS_QUERY_OBSERVED");
        HttpResponse<String> response = get(UUID.randomUUID().toString());

        assertEquals(404, response.statusCode());
        assertEquals("PAYMENT_NOT_FOUND", JSON.readTree(response.body()).get("code").asText());
        assertEquals(before, runEventCount("MERCHANT_STATUS_QUERY_OBSERVED"));
    }

    @Test
    void sameKeyIsScopedToRunAndLookupCannotCrossRuns() throws Exception {
        String key = UUID.randomUUID().toString();
        String firstId = JSON.readTree(post(key, VALID_BODY).body()).get("paymentId").asText();
        String firstRun = runId;
        runId = JSON.readTree(postRun().body()).get("runId").asText();

        HttpResponse<String> second = post(key, VALID_BODY);

        assertEquals(200, second.statusCode());
        assertFalse(firstId.equals(JSON.readTree(second.body()).get("paymentId").asText()));
        assertEquals(404, get(firstId).statusCode());
        runId = firstRun;
        assertEquals(200, get(firstId).statusCode());
    }

    @Test
    void paymentRequestsRequireAnExistingRunId() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM provider_payments", Long.class);
        HttpRequest missing = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(VALID_BODY)).build();
        HttpResponse<String> missingResponse = HTTP.send(missing, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, missingResponse.statusCode());
        assertEquals("INVALID_REQUEST", JSON.readTree(missingResponse.body()).get("code").asText());

        String currentRun = runId;
        runId = "not-a-uuid";
        HttpResponse<String> malformed = post(UUID.randomUUID().toString(), VALID_BODY);
        assertEquals(400, malformed.statusCode());

        runId = UUID.randomUUID().toString();
        HttpResponse<String> unknown = post(UUID.randomUUID().toString(), VALID_BODY);
        assertEquals(404, unknown.statusCode());
        assertEquals("TEST_RUN_NOT_FOUND", JSON.readTree(unknown.body()).get("code").asText());
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM provider_payments", Long.class));
        runId = currentRun;
    }

    @Test
    void unknownScenarioIsRejectedWhenCreatingRun() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test-runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"scenario":"SAME_KEY_RETRY","webhookUrl":"http://localhost:8081/webhooks/paylab"}
                        """))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertEquals("INVALID_RUN_REQUEST", JSON.readTree(response.body()).get("code").asText());
    }

    @Test
    void malformedInputReturns400() throws Exception {
        assertBadRequest(post(UUID.randomUUID().toString(),
                """
                {"amountMinor":0,"currency":"ETB","merchantReference":"order-123"}
                """));
        assertBadRequest(post(UUID.randomUUID().toString(),
                """
                {"amountMinor":10000,"currency":"ETB"}
                """));
        assertBadRequest(post(UUID.randomUUID().toString(),
                """
                {"amountMinor":10000,"currency":"etb","merchantReference":"order-123"}
                """));
        assertBadRequest(post(" ", VALID_BODY));
        assertBadRequest(post(null, VALID_BODY));
        assertBadRequest(post(UUID.randomUUID().toString(), "{"));
    }

    private void assertBadRequest(HttpResponse<String> response) {
        assertEquals(400, response.statusCode());
        assertEquals("INVALID_REQUEST", JSON.readTree(response.body()).get("code").asText());
    }

    private HttpResponse<String> post(String key, String body) throws IOException, InterruptedException {
        return post(key, body, null);
    }

    private HttpResponse<String> post(String key, String body, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments"))
                .header("Content-Type", "application/json")
                .header("PayLab-Run-Id", runId)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (timeout != null) {
            request.timeout(timeout);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String id) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments/" + id))
                .header("PayLab-Run-Id", runId)
                .GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postRun() throws IOException, InterruptedException {
        return postRun("""
                {"scenario":"ASYNC_SUCCESS","webhookUrl":"http://localhost:8081/webhooks/paylab"}
                """);
    }

    private HttpResponse<String> postRun(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test-runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String createTimeoutRun(int delayMillis) throws Exception {
        HttpResponse<String> response = postRun("""
                {"scenario":"TIMEOUT_AFTER_COMMIT","responseDelayMillis":%d}
                """.formatted(delayMillis));
        assertEquals(201, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(delayMillis, body.get("responseDelayMillis").asInt());
        assertTrue(body.get("webhookUrl").isNull());
        return body.get("runId").asText();
    }

    private String createTimeoutBeforeCommitRun(int delayMillis) throws Exception {
        HttpResponse<String> response = postRun("""
                {"scenario":"TIMEOUT_BEFORE_COMMIT","responseDelayMillis":%d}
                """.formatted(delayMillis));
        assertEquals(201, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("TIMEOUT_BEFORE_COMMIT", body.get("scenario").asText());
        assertEquals(delayMillis, body.get("responseDelayMillis").asInt());
        assertTrue(body.get("webhookUrl").isNull());
        return body.get("runId").asText();
    }

    private String createSameKeyRetryRun() throws Exception {
        HttpResponse<String> response = postRun("""
                {"scenario":"SAME_KEY_RETRY"}
                """);
        assertEquals(201, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.get("webhookUrl").isNull());
        assertTrue(body.get("responseDelayMillis").isNull());
        return body.get("runId").asText();
    }

    private String createKeyReuseDifferentPayloadRun() throws Exception {
        HttpResponse<String> response = postRun("""
                {"scenario":"KEY_REUSE_DIFFERENT_PAYLOAD"}
                """);
        assertEquals(201, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.get("webhookUrl").isNull());
        assertTrue(body.get("responseDelayMillis").isNull());
        return body.get("runId").asText();
    }

    private JsonNode getEvents() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test-runs/" + runId + "/events")).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body());
    }

    private JsonNode conformance() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test-runs/" + runId + "/conformance")).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> finalizeRun() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test-runs/" + runId + "/finalize"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void appendObserved(String key, String fingerprint) {
        jdbc.update("""
                INSERT INTO run_events (run_id, event_type, idempotency_key, request_fingerprint)
                VALUES (?, 'MERCHANT_REQUEST_OBSERVED', ?, ?)
                """, UUID.fromString(runId), key, fingerprint);
    }

    private void appendResolved(String key, String fingerprint, String paymentId) {
        jdbc.update("""
                INSERT INTO run_events
                    (run_id, event_type, idempotency_key, request_fingerprint, payment_id)
                VALUES (?, 'PAYMENT_REQUEST_RESOLVED', ?, ?, ?)
                """, UUID.fromString(runId), key, fingerprint, paymentId);
    }

    private String insertSucceededPayment(String key, String fingerprint, long amountMinor,
            String merchantReference) {
        String paymentId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO provider_payments
                    (payment_id, run_id, idempotency_key, request_fingerprint, amount_minor_units,
                     currency, merchant_reference, status)
                VALUES (?, ?, ?, ?, ?, 'ETB', ?, 'SUCCEEDED')
                """, paymentId, UUID.fromString(runId), key, fingerprint, amountMinor, merchantReference);
        return paymentId;
    }

    private long count(String table) {
        String sql = switch (table) {
            case "provider_payments", "webhook_events" ->
                    "SELECT count(*) FROM " + table + " WHERE run_id = ?";
            case "webhook_deliveries" -> """
                    SELECT count(*) FROM webhook_deliveries d
                    JOIN webhook_events e ON e.event_id = d.event_id
                    WHERE e.run_id = ?
                    """;
            default -> throw new IllegalArgumentException("Unsupported table: " + table);
        };
        return jdbc.queryForObject(sql, Long.class, UUID.fromString(runId));
    }

    private static int eventCount(JsonNode events, String type) {
        int count = 0;
        for (JsonNode event : events) {
            if (type.equals(event.get("eventType").asText())) {
                count++;
            }
        }
        return count;
    }

    private void awaitResponseDelayEvidence(Future<?> firstRequest) throws Exception {
        awaitRunEventEvidence(firstRequest, "RESPONSE_DELAY_INJECTED");
    }

    private void awaitRunEventEvidence(Future<?> firstRequest, String eventType) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (runEventCount(eventType) == 1) {
                return;
            }
            if (firstRequest.isDone()) {
                throw new AssertionError("First request completed before " + eventType + " evidence became visible");
            }
            Thread.sleep(20);
        }
        throw new AssertionError(eventType + " evidence did not become visible within 5 seconds");
    }

    private int runEventCount(String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM run_events WHERE run_id = ? AND event_type = ?
                """, Integer.class, UUID.fromString(runId), eventType);
    }

    private long runEventId(String eventType) {
        return jdbc.queryForObject("""
                SELECT event_id FROM run_events WHERE run_id = ? AND event_type = ?
                """, Long.class, UUID.fromString(runId), eventType);
    }

    private static JsonNode event(JsonNode events, String type, int occurrence) {
        int seen = 0;
        for (JsonNode event : events) {
            if (type.equals(event.get("eventType").asText()) && seen++ == occurrence) {
                return event;
            }
        }
        throw new AssertionError("Missing event " + type + " occurrence " + occurrence);
    }

    private static void assertEvidence(JsonNode evidenceIds, long... expectedIds) {
        assertEquals(expectedIds.length, evidenceIds.size());
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < expectedIds.length; i++) {
            long actual = evidenceIds.get(i).asLong();
            assertEquals(expectedIds[i], actual);
            assertTrue(actual > previous);
            previous = actual;
        }
    }

    private static boolean containsEvidence(JsonNode evidenceIds, long expectedId) {
        for (JsonNode evidenceId : evidenceIds) {
            if (evidenceId.asLong() == expectedId) {
                return true;
            }
        }
        return false;
    }
}
