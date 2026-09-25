package io.github.sye1321.paylab.run;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.sye1321.paylab.provider.IdempotencyKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConcurrentCreateRendezvous {

    private final ConcurrentHashMap<RaceKey, Gate> gates = new ConcurrentHashMap<>();
    private final Duration peerTimeout;

    public ConcurrentCreateRendezvous(
            @Value("${paylab.concurrent-create.peer-timeout:5s}") Duration peerTimeout) {
        this.peerTimeout = peerTimeout;
    }

    public Participation awaitPeer(TestRunId runId, IdempotencyKey idempotencyKey) {
        RaceKey key = new RaceKey(runId, idempotencyKey);
        Gate gate = gates.computeIfAbsent(key, ignored -> new Gate());
        if (gate.arrivals.incrementAndGet() == 2) {
            gate.released.complete(null);
        }
        try {
            gate.released.get(peerTimeout.toNanos(), TimeUnit.NANOSECONDS);
            return new Participation(key, gate);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failGate(key, gate);
            throw new ConcurrentCreateExecutionException("Interrupted while waiting for concurrent request",
                    interrupted);
        } catch (TimeoutException timeout) {
            failGate(key, gate);
            throw new ConcurrentCreateExecutionException("Timed out waiting for concurrent request", timeout);
        } catch (ExecutionException peerFailure) {
            throw new ConcurrentCreateExecutionException("Concurrent request rendezvous failed", peerFailure);
        }
    }

    public void complete(Participation participation) {
        gates.remove(participation.key(), participation.gate());
    }

    private void failGate(RaceKey key, Gate gate) {
        gate.released.completeExceptionally(
                new ConcurrentCreateExecutionException("Concurrent request rendezvous failed"));
        gates.remove(key, gate);
    }

    public record Participation(RaceKey key, Gate gate) {
    }

    public record RaceKey(TestRunId runId, IdempotencyKey idempotencyKey) {
        public RaceKey {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        }
    }

    public static final class Gate {
        private final AtomicInteger arrivals = new AtomicInteger();
        private final CompletableFuture<Void> released = new CompletableFuture<>();
    }
}
