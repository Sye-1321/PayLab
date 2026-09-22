CREATE TABLE provider_payments (
    payment_id VARCHAR(64) PRIMARY KEY,
    idempotency_key TEXT NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL,
    amount_minor_units BIGINT NOT NULL CHECK (amount_minor_units > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    merchant_reference TEXT NOT NULL CHECK (length(trim(merchant_reference)) > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED'))
);
