package io.github.sye1321.paylab.provider.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentHttpTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-bookworm");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID_BODY = """
            {"amountMinor":10000,"currency":"ETB","merchantReference":"order-123"}
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
        assertEquals(3, events.size());
        JsonNode observed = events.get(1);
        assertEquals("MERCHANT_REQUEST_OBSERVED", observed.get("eventType").asText());
        assertEquals(key, observed.get("idempotencyKey").asText());
        assertEquals(new PaymentIntent(new Money(10000, "ETB"),
                new MerchantReference("order-123")).fingerprint(), observed.get("requestFingerprint").asText());
        assertEquals(observed.get("requestFingerprint").asText(), jdbc.queryForObject(
                "SELECT request_fingerprint FROM provider_payments WHERE payment_id = ?", String.class,
                body.get("paymentId").asText()));
        assertEquals("SUCCEEDED", JSON.readTree(get(body.get("paymentId").asText()).body())
                .get("status").asText());
        assertEquals(1L, count("webhook_events"));
        assertEquals(1L, count("webhook_deliveries"));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT d.status FROM webhook_deliveries d
                JOIN webhook_events e ON e.event_id = d.event_id
                WHERE e.run_id = ? AND e.payment_id = ?
                """, String.class, UUID.fromString(runId), body.get("paymentId").asText()));
        assertEquals("WEBHOOK_SCHEDULED", events.get(2).get("eventType").asText());
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
        assertEquals(4, events.size());
        assertEquals("RUN_STARTED", events.get(0).get("eventType").asText());
        assertEquals("MERCHANT_REQUEST_OBSERVED", events.get(1).get("eventType").asText());
        assertEquals("WEBHOOK_SCHEDULED", events.get(2).get("eventType").asText());
        assertEquals("MERCHANT_REQUEST_OBSERVED", events.get(3).get("eventType").asText());
        assertEquals(key, events.get(1).get("idempotencyKey").asText());
        assertEquals(key, events.get(3).get("idempotencyKey").asText());
        assertEquals(events.get(1).get("requestFingerprint").asText(),
                events.get(3).get("requestFingerprint").asText());
        assertEquals(1L, count("provider_payments"));
        assertEquals(1L, count("webhook_events"));
        assertEquals(1L, count("webhook_deliveries"));
        assertEquals(1, eventCount(events, "WEBHOOK_SCHEDULED"));
        assertEquals(2, eventCount(events, "MERCHANT_REQUEST_OBSERVED"));
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
        assertEquals(4, getEvents().size());
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
    void concurrentEquivalentRequestsConvergeOnOneSucceededPaymentAndWebhook() throws Exception {
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
            assertEquals(1L, count("webhook_events"));
            assertEquals(1L, count("webhook_deliveries"));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void missingPaymentReturns404() throws Exception {
        HttpResponse<String> response = get(UUID.randomUUID().toString());

        assertEquals(404, response.statusCode());
        assertEquals("PAYMENT_NOT_FOUND", JSON.readTree(response.body()).get("code").asText());
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
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments"))
                .header("Content-Type", "application/json")
                .header("PayLab-Run-Id", runId)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
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
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test-runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"scenario":"ASYNC_SUCCESS","webhookUrl":"http://localhost:8081/webhooks/paylab"}
                        """))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getEvents() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test-runs/" + runId + "/events")).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body());
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
}
