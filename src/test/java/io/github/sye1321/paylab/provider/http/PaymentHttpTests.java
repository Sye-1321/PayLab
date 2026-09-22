package io.github.sye1321.paylab.provider.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.PaymentId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    private JdbcProviderPaymentStore store;

    @Test
    void createReturnsProviderPaymentRepresentation() throws Exception {
        HttpResponse<String> response = post(UUID.randomUUID().toString(), VALID_BODY);

        assertEquals(200, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertFalse(body.get("paymentId").asText().isBlank());
        assertEquals(10000, body.get("amountMinor").asLong());
        assertEquals("ETB", body.get("currency").asText());
        assertEquals("order-123", body.get("merchantReference").asText());
        assertEquals("CREATED", body.get("status").asText());
        assertFalse(body.has("requestFingerprint"));
    }

    @Test
    void equivalentReplayReturnsSamePaymentId() throws Exception {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = post(key, VALID_BODY);
        HttpResponse<String> replay = post(key, VALID_BODY);

        assertEquals(200, first.statusCode());
        assertEquals(200, replay.statusCode());
        assertEquals(JSON.readTree(first.body()).get("paymentId").asText(),
                JSON.readTree(replay.body()).get("paymentId").asText());
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
        HttpResponse<String> original = get(id);
        assertEquals(10000, JSON.readTree(original.body()).get("amountMinor").asLong());
    }

    @Test
    void lookupReturnsCurrentProviderState() throws Exception {
        HttpResponse<String> created = post(UUID.randomUUID().toString(), VALID_BODY);
        String id = JSON.readTree(created.body()).get("paymentId").asText();
        store.startProcessing(new PaymentId(id));
        store.markSucceeded(new PaymentId(id));

        HttpResponse<String> response = get(id);

        assertEquals(200, response.statusCode());
        assertEquals(id, JSON.readTree(response.body()).get("paymentId").asText());
        assertEquals("SUCCEEDED", JSON.readTree(response.body()).get("status").asText());
    }

    @Test
    void missingPaymentReturns404() throws Exception {
        HttpResponse<String> response = get(UUID.randomUUID().toString());

        assertEquals(404, response.statusCode());
        assertEquals("PAYMENT_NOT_FOUND", JSON.readTree(response.body()).get("code").asText());
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
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String id) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments/" + id))
                .GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
