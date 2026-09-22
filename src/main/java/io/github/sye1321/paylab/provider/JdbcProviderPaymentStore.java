package io.github.sye1321.paylab.provider;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcProviderPaymentStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcProviderPaymentStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Payment createOrResolve(IdempotencyKey key, PaymentIntent intent) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(intent, "intent");
        return Objects.requireNonNull(transaction.execute(status -> createOrResolveInTransaction(key, intent)));
    }

    public Optional<Payment> findById(PaymentId id) {
        Objects.requireNonNull(id, "id");
        return jdbc.query("""
                SELECT payment_id, idempotency_key, amount_minor_units, currency, merchant_reference, status
                FROM provider_payments WHERE payment_id = ?
                """, JdbcProviderPaymentStore::readPayment, id.value()).stream().findFirst();
    }

    private Payment createOrResolveInTransaction(IdempotencyKey key, PaymentIntent intent) {
        Payment candidate = new Payment(new PaymentId(UUID.randomUUID().toString()), intent, key);
        String fingerprint = intent.fingerprint();
        var insertedIds = jdbc.query("""
                INSERT INTO provider_payments
                    (payment_id, idempotency_key, request_fingerprint, amount_minor_units,
                     currency, merchant_reference, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING payment_id
                """, (rs, rowNum) -> rs.getString("payment_id"),
                candidate.id().value(), key.value(), fingerprint, intent.amount().minorUnits(),
                intent.amount().currency(), intent.merchantReference().value(), candidate.status().name());

        if (!insertedIds.isEmpty()) {
            return candidate;
        }

        // This separate statement sees the winner after PostgreSQL resolves the unique-key race.
        var existing = jdbc.query("""
                SELECT payment_id, idempotency_key, request_fingerprint, amount_minor_units,
                       currency, merchant_reference, status
                FROM provider_payments WHERE idempotency_key = ?
                """, (rs, rowNum) -> new StoredPayment(rs.getString("request_fingerprint"), readPayment(rs, rowNum)),
                key.value()).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Idempotency winner not found for key: " + key.value()));

        if (!existing.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(key);
        }
        return existing.payment();
    }

    private static Payment readPayment(ResultSet rs, int rowNum) throws SQLException {
        return Payment.rehydrate(
                new PaymentId(rs.getString("payment_id")),
                new PaymentIntent(new Money(rs.getLong("amount_minor_units"), rs.getString("currency")),
                        new MerchantReference(rs.getString("merchant_reference"))),
                new IdempotencyKey(rs.getString("idempotency_key")),
                PaymentStatus.valueOf(rs.getString("status")));
    }

    private record StoredPayment(String fingerprint, Payment payment) {
    }
}
