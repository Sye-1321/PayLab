package io.github.sye1321.paylab.run;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.sye1321.paylab.provider.IdempotencyKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class JdbcRunEventStoreTests {

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
    private JdbcRunEventStore events;

    @Autowired
    private JdbcTestRunStore runs;

    @Test
    void concurrentPreCommitFaultClaimsHaveExactlyOneWinner() throws Exception {
        TestRunId runId = runs.create(ScenarioId.TIMEOUT_BEFORE_COMMIT, null, 5_000).runId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent start timed out");
                }
                return events.tryAppendPreCommitTimeoutInjected(runId,
                        new IdempotencyKey(UUID.randomUUID().toString()), "a".repeat(64), 5_000);
            };
            Future<Boolean> first = executor.submit(claim);
            Future<Boolean> second = executor.submit(claim);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int winners = (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
            assertEquals(1, events.findByRun(runId).stream()
                    .filter(event -> event.eventType() == RunEventType.PRE_COMMIT_TIMEOUT_INJECTED)
                    .count());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
