package io.github.sye1321.paylab.webhook;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import io.github.sye1321.paylab.provider.IdempotencyKey;
import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.MerchantReference;
import io.github.sye1321.paylab.provider.Money;
import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentCreationResult;
import io.github.sye1321.paylab.provider.PaymentIntent;
import io.github.sye1321.paylab.run.JdbcRunEventStore;
import io.github.sye1321.paylab.run.JdbcTestRunStore;
import io.github.sye1321.paylab.run.OutOfOrderWebhookScenarioExecutor;
import io.github.sye1321.paylab.run.RunEventType;
import io.github.sye1321.paylab.run.ScenarioId;
import io.github.sye1321.paylab.run.TestRunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class WebhookDeliveryTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-bookworm");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTestRunStore runs;
    @Autowired JdbcProviderPaymentStore payments;
    @Autowired SuccessfulPaymentWebhookScheduler scheduler;
    @Autowired OutOfOrderWebhookScenarioExecutor outOfOrderWebhook;
    @Autowired JdbcWebhookStore webhooks;
    @Autowired JdbcRunEventStore runEvents;
    @Autowired WebhookDeliveryWorker worker;
    @Autowired WebhookSigner signer;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE test_runs CASCADE");
    }

    @Test
    void outOfOrderScenarioDeliversNewerSucceededBeforeOlderProcessingWithValidImmutablePayloads()
            throws Exception {
        try (DuplicateReceiver receiver = new DuplicateReceiver()) {
            TestRunId runId = runs.create(ScenarioId.OUT_OF_ORDER_WEBHOOK, receiver.url()).runId();
            Payment created = payments.createOrResolve(runId,
                    new IdempotencyKey(UUID.randomUUID().toString()), intent()).payment();

            Payment succeeded = outOfOrderWebhook.execute(runId, new PaymentCreationResult(created, true));

            assertEquals("SUCCEEDED", succeeded.status().name());
            assertEquals(2, count("webhook_events"));
            assertEquals(2, count("webhook_deliveries"));
            List<WebhookEvent> events = jdbc.query("""
                    SELECT event_id, run_id, payment_id, event_type, payload, created_at
                    FROM webhook_events WHERE run_id = ? ORDER BY created_at
                    """, (rs, rowNum) -> new WebhookEvent(rs.getObject("event_id", UUID.class), runId,
                            new io.github.sye1321.paylab.provider.PaymentId(rs.getString("payment_id")),
                            WebhookEventType.valueOf(rs.getString("event_type")), rs.getBytes("payload"),
                            rs.getTimestamp("created_at").toInstant()), runId.value());
            WebhookEvent processing = events.get(0);
            WebhookEvent successful = events.get(1);
            assertEquals(WebhookEventType.PAYMENT_PROCESSING, processing.type());
            assertEquals(WebhookEventType.PAYMENT_SUCCEEDED, successful.type());
            assertNotEquals(processing.eventId(), successful.eventId());
            assertTrue(processing.createdAt().isBefore(successful.createdAt()));
            assertTrue(jdbc.queryForObject("SELECT due_at FROM webhook_deliveries WHERE event_id = ?",
                    java.sql.Timestamp.class, successful.eventId()).before(
                            jdbc.queryForObject("SELECT due_at FROM webhook_deliveries WHERE event_id = ?",
                                    java.sql.Timestamp.class, processing.eventId())));
            JsonNode processingBody = json.readTree(processing.payload());
            JsonNode successfulBody = json.readTree(successful.payload());
            assertEquals("PROCESSING", processingBody.get("data").get("status").asText());
            assertEquals("SUCCEEDED", successfulBody.get("data").get("status").asText());
            assertEquals(processing.paymentId().value(), successful.paymentId().value());
            assertEquals(processingBody.get("data").get("amountMinor").asLong(),
                    successfulBody.get("data").get("amountMinor").asLong());
            assertEquals(processingBody.get("data").get("currency").asText(),
                    successfulBody.get("data").get("currency").asText());
            assertEquals(processingBody.get("data").get("merchantReference").asText(),
                    successfulBody.get("data").get("merchantReference").asText());

            worker.deliverOneDue();
            makeDue(processing.eventId());
            worker.deliverOneDue();

            assertEquals(2, receiver.requests.size());
            ReceivedRequest first = receiver.requests.get(0);
            ReceivedRequest second = receiver.requests.get(1);
            assertEquals(successful.eventId().toString(), first.eventId());
            assertEquals(processing.eventId().toString(), second.eventId());
            assertArrayEquals(successful.payload(), first.body());
            assertArrayEquals(processing.payload(), second.body());
            assertEquals(signer.sign(Long.parseLong(first.timestamp()), first.body()), first.signature());
            assertEquals(signer.sign(Long.parseLong(second.timestamp()), second.body()), second.signature());
            assertDeliveryState(successful.eventId(), "DELIVERED", 1, 1, 0);
            assertDeliveryState(processing.eventId(), "DELIVERED", 1, 1, 0);
            assertEquals("SUCCEEDED", paymentStatus(succeeded.id().value()));
            assertEquals(1, eventCount(runEvents.findByRun(runId), RunEventType.WEBHOOK_DELIVERED,
                    successful.eventId()));
            assertEquals(1, eventCount(runEvents.findByRun(runId), RunEventType.WEBHOOK_DELIVERED,
                    processing.eventId()));
            assertEquals(1, eventCount(runEvents.findByRun(runId), RunEventType.WEBHOOK_RESPONSE_OBSERVED,
                    successful.eventId()));
            assertEquals(1, eventCount(runEvents.findByRun(runId), RunEventType.WEBHOOK_RESPONSE_OBSERVED,
                    processing.eventId()));
        }
    }

    @Test
    void schedulingPersistsOneExactImmutableEventPendingWorkAndEvidenceAndRejectsNonSucceededPayment()
            throws Exception {
        TestRunId runId = runs.create(ScenarioId.ASYNC_SUCCESS, "http://localhost:8081/webhooks/paylab").runId();
        Payment payment = payments.createOrResolve(runId, new IdempotencyKey(UUID.randomUUID().toString()),
                intent()).payment();
        long before = count("webhook_events");
        assertThrows(WebhookSchedulingException.class, () -> scheduler.schedule(runId, payment.id()));
        assertEquals(before, count("webhook_events"));

        payments.startProcessing(payment.id());
        payments.markSucceeded(payment.id());
        WebhookEvent event = scheduler.schedule(runId, payment.id());

        WebhookEvent stored = webhooks.findEvent(event.eventId()).orElseThrow();
        assertArrayEquals(event.payload(), stored.payload());
        JsonNode payload = json.readTree(stored.payload());
        assertEquals(event.eventId().toString(), payload.get("eventId").asText());
        assertEquals("PAYMENT_SUCCEEDED", payload.get("type").asText());
        assertEquals(payment.id().value(), payload.get("data").get("paymentId").asText());
        assertEquals(10000, payload.get("data").get("amountMinor").asLong());
        assertEquals("ETB", payload.get("data").get("currency").asText());
        assertEquals("order-123", payload.get("data").get("merchantReference").asText());
        assertEquals("SUCCEEDED", payload.get("data").get("status").asText());
        assertEquals("PENDING", deliveryStatus(event.eventId()));
        assertEquals(0, attemptCount(event.eventId()));
        assertEquals(0, acknowledgedDeliveryCount(event.eventId()));
        assertEquals(0, failureCount(event.eventId()));
        assertEquals(1, targetDeliveryCount(event.eventId()));
        assertEquals("VALID", signatureMode(event.eventId()));
        assertEquals(RunEventType.WEBHOOK_SCHEDULED,
                runEvents.findByRun(runId).getLast().eventType());
    }

    @Test
    void migratedRunWithoutWebhookUrlCannotScheduleDeliveryWork() {
        TestRunId runId = runs.create(ScenarioId.ASYNC_SUCCESS, "http://localhost:8081/webhooks/paylab").runId();
        jdbc.update("UPDATE test_runs SET webhook_url = NULL WHERE run_id = ?", runId.value());
        Payment payment = payments.createOrResolve(runId, new IdempotencyKey(UUID.randomUUID().toString()),
                intent()).payment();
        payments.startProcessing(payment.id());
        payments.markSucceeded(payment.id());

        assertThrows(WebhookSchedulingException.class, () -> scheduler.schedule(runId, payment.id()));

        assertEquals(0, count("webhook_events"));
        assertEquals(0, count("webhook_deliveries"));
        assertFalse(runEvents.findByRun(runId).stream()
                .anyMatch(event -> event.eventType() == RunEventType.WEBHOOK_SCHEDULED));
    }

    @Test
    void receiverGetsStoredBytesAndValidHeadersAndTwoHundredMarksDelivered() throws Exception {
        Received received = new Received();
        try (Receiver receiver = new Receiver(204, received)) {
            WebhookEvent event = scheduleSucceeded(receiver.url());

            worker.deliverOneDue();

            assertArrayEquals(event.payload(), received.body.get());
            assertEquals(event.eventId().toString(), received.eventId.get());
            assertNotNull(received.timestamp.get());
            assertEquals(signer.sign(Long.parseLong(received.timestamp.get()), event.payload()),
                    received.signature.get());
            assertEquals("DELIVERED", deliveryStatus(event.eventId()));
            assertEquals(1, attemptCount(event.eventId()));
            assertEquals(1, targetDeliveryCount(event.eventId()));
            assertEquals("DELIVERED", attemptOutcome(event.eventId()));
            var events = runEvents.findByRun(event.runId());
            assertTrue(events.stream().anyMatch(value -> value.eventType() == RunEventType.WEBHOOK_RESPONSE_OBSERVED
                    && value.httpStatus() == 204));
            assertTrue(events.stream().anyMatch(value -> value.eventType() == RunEventType.WEBHOOK_DELIVERED));
        }
    }

    @Test
    void invalidSignatureDeliveryPreservesEventAndBodyAndStopsAfterRejection() throws Exception {
        Received received = new Received();
        try (Receiver receiver = new Receiver(401, received)) {
            TestRunId runId = runs.create(ScenarioId.INVALID_SIGNATURE, receiver.url()).runId();
            Payment payment = payments.createOrResolve(runId,
                    new IdempotencyKey(UUID.randomUUID().toString()), intent()).payment();
            payments.startProcessing(payment.id());
            payments.markSucceeded(payment.id());
            WebhookEvent event = scheduler.schedule(runId, payment.id(), 1, 0, SignatureMode.INVALID);

            worker.deliverOneDue();

            assertArrayEquals(event.payload(), received.body.get());
            assertEquals(event.eventId().toString(), received.eventId.get());
            String valid = signer.sign(Long.parseLong(received.timestamp.get()), received.body.get());
            assertFalse(valid.equals(received.signature.get()));
            assertTrue(received.signature.get().matches("[0-9a-f]{64}"));
            assertEquals("FAILED", deliveryStatus(event.eventId()));
            assertEquals(1, attemptCount(event.eventId()));
            assertEquals(0, acknowledgedDeliveryCount(event.eventId()));
            assertEquals(1, failureCount(event.eventId()));
            assertEquals(0, maxFailureRetries(event.eventId()));
            assertEquals("INVALID", signatureMode(event.eventId()));
            assertEquals(1, deliveryAttemptRows(event.eventId()));
            assertEquals("NON_2XX", attemptOutcome(event.eventId()));
            assertEquals(401, lastHttpStatus(event.eventId()));
            var evidence = runEvents.findByRun(runId);
            assertTrue(evidence.stream().anyMatch(value ->
                    value.eventType() == RunEventType.WEBHOOK_RESPONSE_OBSERVED
                            && value.httpStatus() == 401 && "NON_2XX".equals(value.outcome())));
            assertFalse(evidence.stream().anyMatch(value -> value.eventType() == RunEventType.WEBHOOK_DELIVERED));

            worker.deliverOneDue();
            assertEquals(1, received.requestCount.get());
            assertEquals(1, deliveryAttemptRows(event.eventId()));
        }
    }

    @Test
    void nonTwoHundredIsRecordedWithoutLosingEvent() throws Exception {
        try (Receiver receiver = new Receiver(503, new Received())) {
            WebhookEvent event = scheduleSucceeded(receiver.url(), 2);

            worker.deliverOneDue();

            assertTrue(webhooks.findEvent(event.eventId()).isPresent());
            assertEquals("FAILED", deliveryStatus(event.eventId()));
            assertEquals(1, attemptCount(event.eventId()));
            assertEquals(0, acknowledgedDeliveryCount(event.eventId()));
            assertEquals(1, failureCount(event.eventId()));
            assertEquals(2, targetDeliveryCount(event.eventId()));
            assertEquals(503, lastHttpStatus(event.eventId()));
            assertEquals("NON_2XX", attemptOutcome(event.eventId()));
            assertFalse(runEvents.findByRun(event.runId()).stream()
                    .anyMatch(value -> value.eventType() == RunEventType.WEBHOOK_DELIVERED));
        }
    }

    @Test
    void retryableFailureRequeuesThenDeliversTheSamePersistedEvent() throws Exception {
        try (SequenceReceiver receiver = new SequenceReceiver(503, 204)) {
            WebhookEvent event = scheduleRetryableSucceeded(receiver.url());

            worker.deliverOneDue();

            assertEquals("PENDING", deliveryStatus(event.eventId()));
            assertEquals(1, attemptCount(event.eventId()));
            assertEquals(0, acknowledgedDeliveryCount(event.eventId()));
            assertEquals(1, failureCount(event.eventId()));
            assertEquals(1, deliveryAttemptRows(event.eventId()));
            assertEquals(List.of("NON_2XX"), attemptOutcomes(event.eventId()));
            assertEquals(503, lastHttpStatus(event.eventId()));
            assertTrue(claimTokenIsNull(event.eventId()));
            assertTrue(claimUntilIsNull(event.eventId()));
            assertFalse(runEvents.findByRun(event.runId()).stream()
                    .anyMatch(value -> value.eventType() == RunEventType.WEBHOOK_DELIVERED));

            makeDue(event.eventId());
            worker.deliverOneDue();

            assertEquals(2, receiver.requests.size());
            assertEquals(event.eventId().toString(), receiver.requests.get(0).eventId());
            assertEquals(receiver.requests.get(0).eventId(), receiver.requests.get(1).eventId());
            assertArrayEquals(event.payload(), receiver.requests.get(0).body());
            assertArrayEquals(receiver.requests.get(0).body(), receiver.requests.get(1).body());
            assertEquals(signer.sign(Long.parseLong(receiver.requests.get(0).timestamp()),
                    receiver.requests.get(0).body()), receiver.requests.get(0).signature());
            assertEquals(signer.sign(Long.parseLong(receiver.requests.get(1).timestamp()),
                    receiver.requests.get(1).body()), receiver.requests.get(1).signature());
            assertEquals("DELIVERED", deliveryStatus(event.eventId()));
            assertEquals(2, attemptCount(event.eventId()));
            assertEquals(1, acknowledgedDeliveryCount(event.eventId()));
            assertEquals(1, failureCount(event.eventId()));
            assertEquals(2, deliveryAttemptRows(event.eventId()));
            assertEquals(List.of("NON_2XX", "DELIVERED"), attemptOutcomes(event.eventId()));
            assertEquals(1, count("webhook_events"));
            assertEquals(1, count("webhook_deliveries"));
            assertEquals("SUCCEEDED", paymentStatus(event.paymentId().value()));
            assertEquals(1, count("provider_payments"));
            var evidence = runEvents.findByRun(event.runId());
            assertEquals(2, eventCount(evidence, RunEventType.WEBHOOK_RESPONSE_OBSERVED, event.eventId()));
            assertEquals(1, eventCount(evidence, RunEventType.WEBHOOK_DELIVERED, event.eventId()));
        }
    }

    @Test
    void retryableDeliveryStopsAfterItsSingleFailureRetryIsExhausted() throws Exception {
        try (SequenceReceiver receiver = new SequenceReceiver(503, 503)) {
            WebhookEvent event = scheduleRetryableSucceeded(receiver.url());

            worker.deliverOneDue();
            assertEquals("PENDING", deliveryStatus(event.eventId()));
            makeDue(event.eventId());
            worker.deliverOneDue();

            assertEquals("FAILED", deliveryStatus(event.eventId()));
            assertEquals(2, attemptCount(event.eventId()));
            assertEquals(0, acknowledgedDeliveryCount(event.eventId()));
            assertEquals(2, failureCount(event.eventId()));
            assertEquals(2, deliveryAttemptRows(event.eventId()));
            assertEquals(List.of("NON_2XX", "NON_2XX"), attemptOutcomes(event.eventId()));
            assertTrue(claimTokenIsNull(event.eventId()));
            assertTrue(claimUntilIsNull(event.eventId()));

            worker.deliverOneDue();
            assertEquals(2, receiver.requests.size());
        }
    }

    @Test
    void duplicateTargetRequeuesFirstAcknowledgementAndDeliversSameStoredEventTwice() throws Exception {
        try (DuplicateReceiver receiver = new DuplicateReceiver()) {
            WebhookEvent event = scheduleSucceeded(receiver.url(), 2);

            worker.deliverOneDue();

            assertEquals(1, receiver.requests.size());
            assertEquals("PENDING", deliveryStatus(event.eventId()));
            assertEquals(1, attemptCount(event.eventId()));
            assertEquals(1, acknowledgedDeliveryCount(event.eventId()));
            assertEquals(0, failureCount(event.eventId()));
            assertEquals(2, targetDeliveryCount(event.eventId()));
            assertTrue(claimTokenIsNull(event.eventId()));
            assertTrue(claimUntilIsNull(event.eventId()));

            worker.deliverOneDue();

            assertEquals(2, receiver.requests.size());
            ReceivedRequest first = receiver.requests.get(0);
            ReceivedRequest second = receiver.requests.get(1);
            assertEquals(event.eventId().toString(), first.eventId());
            assertEquals(first.eventId(), second.eventId());
            assertArrayEquals(event.payload(), first.body());
            assertArrayEquals(first.body(), second.body());
            JsonNode firstBody = json.readTree(first.body());
            JsonNode secondBody = json.readTree(second.body());
            assertEquals(first.eventId(), firstBody.get("eventId").asText());
            assertEquals(firstBody.get("eventId").asText(), secondBody.get("eventId").asText());
            assertEquals(firstBody.get("data").get("paymentId").asText(),
                    secondBody.get("data").get("paymentId").asText());
            assertEquals(signer.sign(Long.parseLong(first.timestamp()), first.body()), first.signature());
            assertEquals(signer.sign(Long.parseLong(second.timestamp()), second.body()), second.signature());
            assertEquals(1, count("webhook_events"));
            assertEquals(1, count("webhook_deliveries"));
            assertEquals(2, deliveryAttemptRows(event.eventId()));
            assertEquals("DELIVERED", deliveryStatus(event.eventId()));
            assertEquals(2, attemptCount(event.eventId()));
            assertEquals(2, acknowledgedDeliveryCount(event.eventId()));
            assertEquals(0, failureCount(event.eventId()));
            assertEquals(2, targetDeliveryCount(event.eventId()));
            var evidence = runEvents.findByRun(event.runId());
            assertEquals(1, eventCount(evidence, RunEventType.WEBHOOK_SCHEDULED, event.eventId()));
            assertEquals(2, eventCount(evidence, RunEventType.WEBHOOK_RESPONSE_OBSERVED, event.eventId()));
            assertEquals(2, eventCount(evidence, RunEventType.WEBHOOK_DELIVERED, event.eventId()));
        }
    }

    @Test
    void failureDoesNotAdvanceCombinedAcknowledgementTarget() throws Exception {
        try (SequenceReceiver receiver = new SequenceReceiver(503, 204, 204)) {
            WebhookEvent event = scheduleSucceeded(receiver.url(), 2, 1);

            worker.deliverOneDue();
            assertDeliveryState(event.eventId(), "PENDING", 1, 0, 1);

            makeDue(event.eventId());
            worker.deliverOneDue();
            assertDeliveryState(event.eventId(), "PENDING", 2, 1, 1);

            worker.deliverOneDue();
            assertDeliveryState(event.eventId(), "DELIVERED", 3, 2, 1);
            assertEquals(3, receiver.requests.size());
            assertEquals(3, deliveryAttemptRows(event.eventId()));
            assertEquals(1, count("webhook_events"));
            assertEquals(1, count("webhook_deliveries"));
        }
    }

    @Test
    void acknowledgementDoesNotConsumeCombinedFailureRetryAllowance() throws Exception {
        try (SequenceReceiver receiver = new SequenceReceiver(204, 503, 204)) {
            WebhookEvent event = scheduleSucceeded(receiver.url(), 2, 1);

            worker.deliverOneDue();
            assertDeliveryState(event.eventId(), "PENDING", 1, 1, 0);

            worker.deliverOneDue();
            assertDeliveryState(event.eventId(), "PENDING", 2, 1, 1);

            makeDue(event.eventId());
            worker.deliverOneDue();
            assertDeliveryState(event.eventId(), "DELIVERED", 3, 2, 1);
            assertEquals(3, receiver.requests.size());
            assertEquals(3, deliveryAttemptRows(event.eventId()));
        }
    }

    @Test
    void concurrentWorkersCannotClaimTheSamePendingDelivery() throws Exception {
        WebhookEvent event = scheduleSucceeded("http://localhost:8081/webhooks/paylab");
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim = () -> {
                start.await(10, TimeUnit.SECONDS);
                return webhooks.claimDue().isPresent();
            };
            Future<Boolean> first = pool.submit(claim);
            Future<Boolean> second = pool.submit(claim);
            start.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals("IN_PROGRESS", deliveryStatus(event.eventId()));
        } finally {
            pool.shutdownNow();
        }
    }

    private WebhookEvent scheduleSucceeded(String url) {
        return scheduleSucceeded(url, 1);
    }

    private WebhookEvent scheduleSucceeded(String url, int targetDeliveryCount) {
        return scheduleSucceeded(url, targetDeliveryCount, 0);
    }

    private WebhookEvent scheduleSucceeded(String url, int targetDeliveryCount, int maxFailureRetries) {
        TestRunId runId = runs.create(ScenarioId.ASYNC_SUCCESS, url).runId();
        Payment payment = payments.createOrResolve(runId, new IdempotencyKey(UUID.randomUUID().toString()), intent())
                .payment();
        payments.startProcessing(payment.id());
        payments.markSucceeded(payment.id());
        return scheduler.schedule(runId, payment.id(), targetDeliveryCount, maxFailureRetries);
    }

    private WebhookEvent scheduleRetryableSucceeded(String url) {
        TestRunId runId = runs.create(ScenarioId.WEBHOOK_RETRY, url).runId();
        Payment payment = payments.createOrResolve(runId, new IdempotencyKey(UUID.randomUUID().toString()), intent())
                .payment();
        payments.startProcessing(payment.id());
        payments.markSucceeded(payment.id());
        return scheduler.schedule(runId, payment.id(), 1, 1);
    }

    private static PaymentIntent intent() {
        return new PaymentIntent(new Money(10000, "ETB"), new MerchantReference("order-123"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String deliveryStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE event_id = ?", String.class, id);
    }

    private int attemptCount(UUID id) {
        return jdbc.queryForObject("SELECT attempt_count FROM webhook_deliveries WHERE event_id = ?", Integer.class, id);
    }

    private int targetDeliveryCount(UUID id) {
        return jdbc.queryForObject("SELECT target_delivery_count FROM webhook_deliveries WHERE event_id = ?",
                Integer.class, id);
    }

    private int maxFailureRetries(UUID id) {
        return jdbc.queryForObject("SELECT max_failure_retries FROM webhook_deliveries WHERE event_id = ?",
                Integer.class, id);
    }

    private String signatureMode(UUID id) {
        return jdbc.queryForObject("SELECT signature_mode FROM webhook_deliveries WHERE event_id = ?",
                String.class, id);
    }

    private int acknowledgedDeliveryCount(UUID id) {
        return jdbc.queryForObject(
                "SELECT acknowledged_delivery_count FROM webhook_deliveries WHERE event_id = ?",
                Integer.class, id);
    }

    private int failureCount(UUID id) {
        return jdbc.queryForObject("SELECT failure_count FROM webhook_deliveries WHERE event_id = ?",
                Integer.class, id);
    }

    private void assertDeliveryState(UUID id, String status, int attempts, int acknowledgements, int failures) {
        assertEquals(status, deliveryStatus(id));
        assertEquals(attempts, attemptCount(id));
        assertEquals(acknowledgements, acknowledgedDeliveryCount(id));
        assertEquals(failures, failureCount(id));
    }

    private int deliveryAttemptRows(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM webhook_delivery_attempts WHERE event_id = ?",
                Integer.class, id);
    }

    private boolean claimTokenIsNull(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT claim_token IS NULL FROM webhook_deliveries WHERE event_id = ?", Boolean.class, id));
    }

    private boolean claimUntilIsNull(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT claim_until IS NULL FROM webhook_deliveries WHERE event_id = ?", Boolean.class, id));
    }

    private static int eventCount(java.util.List<io.github.sye1321.paylab.run.RunEvent> events,
            RunEventType type, UUID eventId) {
        return (int) events.stream()
                .filter(event -> event.eventType() == type && eventId.equals(event.webhookEventId()))
                .count();
    }

    private int lastHttpStatus(UUID id) {
        return jdbc.queryForObject("SELECT last_http_status FROM webhook_deliveries WHERE event_id = ?", Integer.class, id);
    }

    private String attemptOutcome(UUID id) {
        return jdbc.queryForObject("SELECT outcome FROM webhook_delivery_attempts WHERE event_id = ?", String.class, id);
    }

    private List<String> attemptOutcomes(UUID id) {
        return jdbc.queryForList("""
                SELECT outcome FROM webhook_delivery_attempts
                WHERE event_id = ? ORDER BY attempted_at
                """, String.class, id);
    }

    private void makeDue(UUID id) {
        jdbc.update("UPDATE webhook_deliveries SET due_at = now() WHERE event_id = ?", id);
    }

    private String paymentStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM provider_payments WHERE payment_id = ?", String.class, id);
    }

    private static final class Received {
        final AtomicReference<byte[]> body = new AtomicReference<>();
        final AtomicReference<String> eventId = new AtomicReference<>();
        final AtomicReference<String> timestamp = new AtomicReference<>();
        final AtomicReference<String> signature = new AtomicReference<>();
        final AtomicInteger requestCount = new AtomicInteger();
    }

    private static final class Receiver implements AutoCloseable {
        private final HttpServer server;

        Receiver(int status, Received received) throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/webhooks/paylab", exchange -> {
                received.requestCount.incrementAndGet();
                received.body.set(exchange.getRequestBody().readAllBytes());
                received.eventId.set(exchange.getRequestHeaders().getFirst("PayLab-Event-Id"));
                received.timestamp.set(exchange.getRequestHeaders().getFirst("PayLab-Timestamp"));
                received.signature.set(exchange.getRequestHeaders().getFirst("PayLab-Signature"));
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort() + "/webhooks/paylab";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record ReceivedRequest(byte[] body, String eventId, String timestamp, String signature) {
    }

    private static final class DuplicateReceiver implements AutoCloseable {
        private final HttpServer server;
        private final CopyOnWriteArrayList<ReceivedRequest> requests = new CopyOnWriteArrayList<>();

        DuplicateReceiver() throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/webhooks/paylab", exchange -> {
                requests.add(new ReceivedRequest(exchange.getRequestBody().readAllBytes(),
                        exchange.getRequestHeaders().getFirst("PayLab-Event-Id"),
                        exchange.getRequestHeaders().getFirst("PayLab-Timestamp"),
                        exchange.getRequestHeaders().getFirst("PayLab-Signature")));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort() + "/webhooks/paylab";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class SequenceReceiver implements AutoCloseable {
        private final HttpServer server;
        private final int[] statuses;
        private final AtomicInteger nextStatus = new AtomicInteger();
        private final CopyOnWriteArrayList<ReceivedRequest> requests = new CopyOnWriteArrayList<>();

        SequenceReceiver(int... statuses) throws IOException {
            this.statuses = statuses;
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/webhooks/paylab", exchange -> {
                requests.add(new ReceivedRequest(exchange.getRequestBody().readAllBytes(),
                        exchange.getRequestHeaders().getFirst("PayLab-Event-Id"),
                        exchange.getRequestHeaders().getFirst("PayLab-Timestamp"),
                        exchange.getRequestHeaders().getFirst("PayLab-Signature")));
                exchange.sendResponseHeaders(statuses[nextStatus.getAndIncrement()], -1);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort() + "/webhooks/paylab";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
