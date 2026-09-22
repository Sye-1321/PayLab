package io.github.sye1321.paylab.provider;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void newKeyPersistsPaymentAndStoredStatusCanBeRehydrated() {
        IdempotencyKey key = key();
        PaymentIntent intent = intent(100, "USD", "order-1");

        Payment created = store.createOrResolve(key, intent);
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
        Payment first = store.createOrResolve(key, intent(100, "USD", "order-1"));

        Payment replay = store.createOrResolve(key, intent(100, "USD", "order-1"));

        assertEquals(first.id(), replay.id());
        assertEquals(1, count(key));
    }

    @Test
    void differentIntentConflictsWithoutChangingOriginal() {
        IdempotencyKey key = key();
        PaymentIntent originalIntent = intent(100, "USD", "order-1");
        Payment original = store.createOrResolve(key, originalIntent);

        assertThrows(IdempotencyConflictException.class,
                () -> store.createOrResolve(key, intent(101, "USD", "order-1")));

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
                return store.createOrResolve(key, intent);
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
