ALTER TABLE test_runs ADD COLUMN webhook_url TEXT;

ALTER TABLE run_events DROP CONSTRAINT run_events_event_type_check;
ALTER TABLE run_events DROP CONSTRAINT run_events_request_fields_check;
ALTER TABLE run_events
    ADD COLUMN webhook_event_id UUID,
    ADD COLUMN http_status INTEGER,
    ADD COLUMN outcome TEXT;
ALTER TABLE run_events ADD CONSTRAINT run_events_event_type_check CHECK (event_type IN (
    'RUN_STARTED', 'MERCHANT_REQUEST_OBSERVED', 'WEBHOOK_SCHEDULED',
    'WEBHOOK_DELIVERED', 'WEBHOOK_RESPONSE_OBSERVED'
));
ALTER TABLE run_events ADD CONSTRAINT run_events_fields_check CHECK (
    (event_type = 'RUN_STARTED' AND idempotency_key IS NULL AND request_fingerprint IS NULL
        AND webhook_event_id IS NULL AND http_status IS NULL AND outcome IS NULL)
    OR (event_type = 'MERCHANT_REQUEST_OBSERVED' AND idempotency_key IS NOT NULL
        AND request_fingerprint IS NOT NULL AND webhook_event_id IS NULL
        AND http_status IS NULL AND outcome IS NULL)
    OR (event_type = 'WEBHOOK_SCHEDULED' AND idempotency_key IS NULL
        AND request_fingerprint IS NULL AND webhook_event_id IS NOT NULL
        AND http_status IS NULL AND outcome IS NULL)
    OR (event_type = 'WEBHOOK_DELIVERED' AND idempotency_key IS NULL
        AND request_fingerprint IS NULL AND webhook_event_id IS NOT NULL
        AND http_status BETWEEN 200 AND 299 AND outcome = 'ACKNOWLEDGED')
    OR (event_type = 'WEBHOOK_RESPONSE_OBSERVED' AND idempotency_key IS NULL
        AND request_fingerprint IS NULL AND webhook_event_id IS NOT NULL
        AND http_status IS NOT NULL AND outcome IS NOT NULL)
);

CREATE TABLE webhook_events (
    event_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES test_runs(run_id),
    payment_id VARCHAR(64) NOT NULL REFERENCES provider_payments(payment_id),
    event_type TEXT NOT NULL CHECK (event_type = 'PAYMENT_SUCCEEDED'),
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, payment_id, event_type)
);

CREATE TABLE webhook_deliveries (
    event_id UUID PRIMARY KEY REFERENCES webhook_events(event_id),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DELIVERED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    due_at TIMESTAMPTZ NOT NULL,
    claim_token UUID,
    claim_until TIMESTAMPTZ,
    last_http_status INTEGER,
    CONSTRAINT webhook_delivery_claim_check CHECK (
        (status = 'IN_PROGRESS' AND claim_token IS NOT NULL AND claim_until IS NOT NULL)
        OR (status <> 'IN_PROGRESS' AND claim_token IS NULL AND claim_until IS NULL)
    )
);
CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries(due_at, event_id)
    WHERE status IN ('PENDING', 'IN_PROGRESS');

CREATE TABLE webhook_delivery_attempts (
    attempt_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES webhook_events(event_id),
    attempted_at TIMESTAMPTZ NOT NULL,
    http_status INTEGER,
    outcome TEXT NOT NULL CHECK (outcome IN ('DELIVERED', 'NON_2XX', 'NETWORK_ERROR'))
);
CREATE INDEX webhook_delivery_attempts_event_idx
    ON webhook_delivery_attempts(event_id, attempted_at);
