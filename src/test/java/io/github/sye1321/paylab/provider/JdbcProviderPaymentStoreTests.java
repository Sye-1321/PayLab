package io.github.sye1321.paylab.provider;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.sye1321.paylab.run.JdbcTestRunStore;
import io.github.sye1321.paylab.run.ScenarioId;
import io.github.sye1321.paylab.run.TestRunId;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class JdbcProviderPaymentStoreTests {

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

    @Autowired
    private JdbcProviderPaymentStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcTestRunStore runs;

    private TestRunId runId;

    @BeforeEach
    void createRun() {
        runId = runs.create(ScenarioId.ASYNC_SUCCESS, "http://localhost/webhooks/paylab").runId();
    }

    @Test
    void newKeyPersistsPaymentAndStoredStatusCanBeRehydrated() {
        IdempotencyKey key = key();
        PaymentIntent intent = intent(100, "USD", "order-1");

        Payment created = store.createOrResolve(runId, key, intent);
        assertEquals(PaymentStatus.CREATED, created.status());
        assertEquals(created.id(), store.findById(created.id()).orElseThrow().id());
        assertEquals(intent, store.findById(created.id()).orElseThrow().intent());
        assertEquals(key, store.findById(created.id()).orElseThrow().idempotencyKey());
        assertEquals(intent.fingerprint(), fingerprint(key));

        jdbc.update("UPDATE provider_payments SET status = 'SUCCEEDED' WHERE payment_id = ?", created.id().value());
        Payment restored = store.findById(created.id()).orElseThrow();
        assertEquals(PaymentStatus.SUCCEEDED, restored.status());
        assertThrows(IllegalPaymentTransitionException.class, restored::startProcessing);
    }

    @Test
    void equivalentRetryReturnsPersistedPaymentWithoutAnotherRow() {
        IdempotencyKey key = key();
        Payment first = store.createOrResolve(runId, key, intent(100, "USD", "order-1"));

        Payment replay = store.createOrResolve(runId, key, intent(100, "USD", "order-1"));

        assertEquals(first.id(), replay.id());
        assertEquals(1, count(key));
    }

    @Test
    void differentIntentConflictsWithoutChangingOriginal() {
        IdempotencyKey key = key();
        PaymentIntent originalIntent = intent(100, "USD", "order-1");
        Payment original = store.createOrResolve(runId, key, originalIntent);

        assertThrows(IdempotencyConflictException.class,
                () -> store.createOrResolve(runId, key, intent(101, "USD", "order-1")));

        assertEquals(1, count(key));
        assertEquals(original.id(), store.findById(original.id()).orElseThrow().id());
        assertEquals(originalIntent, store.findById(original.id()).orElseThrow().intent());
        assertEquals(originalIntent.fingerprint(), fingerprint(key));
    }

    @Test
    void concurrentEquivalentRequestsResolveToOnePayment() throws Exception {
        IdempotencyKey key = key();
        PaymentIntent intent = intent(100, "USD", "order-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Payment> request = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent start timed out");
                }
                return store.createOrResolve(runId, key, intent);
            };
            Future<Payment> first = executor.submit(request);
            Future<Payment> second = executor.submit(request);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            Payment firstResult = first.get(30, TimeUnit.SECONDS);
            Payment secondResult = second.get(30, TimeUnit.SECONDS);
            assertEquals(firstResult.id(), secondResult.id());
            assertEquals(1, count(key));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void processingAndSuccessTransitionsPersist() {
        Payment created = store.createOrResolve(runId, key(), intent(100, "USD", "order-1"));

        Payment processing = store.startProcessing(created.id());
        assertEquals(PaymentStatus.PROCESSING, processing.status());
        assertEquals(PaymentStatus.PROCESSING, store.findById(created.id()).orElseThrow().status());

        Payment succeeded = store.markSucceeded(created.id());
        assertEquals(PaymentStatus.SUCCEEDED, succeeded.status());
        assertEquals(PaymentStatus.SUCCEEDED, store.findById(created.id()).orElseThrow().status());
    }

    @Test
    void failureTransitionPersists() {
        Payment created = store.createOrResolve(runId, key(), intent(100, "USD", "order-1"));
        store.startProcessing(created.id());

        Payment failed = store.markFailed(created.id());

        assertEquals(PaymentStatus.FAILED, failed.status());
        assertEquals(PaymentStatus.FAILED, store.findById(created.id()).orElseThrow().status());
    }

    @Test
    void illegalTransitionDoesNotChangeStoredStatusAndMissingPaymentIsDistinct() {
        Payment created = store.createOrResolve(runId, key(), intent(100, "USD", "order-1"));

        assertThrows(IllegalPaymentTransitionException.class, () -> store.markSucceeded(created.id()));
        assertEquals(PaymentStatus.CREATED, store.findById(created.id()).orElseThrow().status());

        store.startProcessing(created.id());
        assertThrows(IllegalPaymentTransitionException.class, () -> store.startProcessing(created.id()));
        assertEquals(PaymentStatus.PROCESSING, store.findById(created.id()).orElseThrow().status());

        assertThrows(PaymentNotFoundException.class,
                () -> store.startProcessing(new PaymentId(UUID.randomUUID().toString())));
    }

    @Test
    void concurrentTerminalTransitionsCannotOverwriteEachOther() throws Exception {
        Payment created = store.createOrResolve(runId, key(), intent(100, "USD", "order-1"));
        store.startProcessing(created.id());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> success = () -> terminalAttempt(ready, start, () -> store.markSucceeded(created.id()));
            Callable<Object> failure = () -> terminalAttempt(ready, start, () -> store.markFailed(created.id()));
            Future<Object> first = executor.submit(success);
            Future<Object> second = executor.submit(failure);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            Object firstResult = first.get(30, TimeUnit.SECONDS);
            Object secondResult = second.get(30, TimeUnit.SECONDS);
            assertTrue((firstResult instanceof Payment && secondResult instanceof IllegalPaymentTransitionException)
                    || (secondResult instanceof Payment && firstResult instanceof IllegalPaymentTransitionException));

            Payment winner = assertInstanceOf(Payment.class,
                    firstResult instanceof Payment ? firstResult : secondResult);
            assertEquals(winner.status(), store.findById(created.id()).orElseThrow().status());
            assertTrue(winner.status() == PaymentStatus.SUCCEEDED || winner.status() == PaymentStatus.FAILED);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static Object terminalAttempt(CountDownLatch ready, CountDownLatch start,
            Callable<Payment> transition) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent start timed out");
        }
        try {
            return transition.call();
        } catch (IllegalPaymentTransitionException conflict) {
            return conflict;
        }
    }

    private int count(IdempotencyKey key) {
        return jdbc.queryForObject("SELECT count(*) FROM provider_payments WHERE idempotency_key = ?",
                Integer.class, key.value());
    }

    private String fingerprint(IdempotencyKey key) {
        return jdbc.queryForObject("SELECT request_fingerprint FROM provider_payments WHERE idempotency_key = ?",
                String.class, key.value());
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey(UUID.randomUUID().toString());
    }

    private static PaymentIntent intent(long minorUnits, String currency, String reference) {
        return new PaymentIntent(new Money(minorUnits, currency), new MerchantReference(reference));
    }
}
