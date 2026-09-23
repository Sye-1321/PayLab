CREATE TABLE test_runs (
    run_id UUID PRIMARY KEY,
    scenario_id TEXT NOT NULL,
    scenario_version INTEGER NOT NULL CHECK (scenario_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE run_events (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES test_runs(run_id),
    event_type TEXT NOT NULL CHECK (event_type IN ('RUN_STARTED', 'MERCHANT_REQUEST_OBSERVED')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key TEXT,
    request_fingerprint CHAR(64),
    CONSTRAINT run_events_request_fields_check CHECK (
        (event_type = 'RUN_STARTED' AND idempotency_key IS NULL AND request_fingerprint IS NULL)
        OR (event_type = 'MERCHANT_REQUEST_OBSERVED' AND idempotency_key IS NOT NULL
            AND request_fingerprint IS NOT NULL)
    )
);

CREATE INDEX run_events_run_order_idx ON run_events(run_id, event_id);

ALTER TABLE provider_payments
    ADD COLUMN run_id UUID REFERENCES test_runs(run_id);

ALTER TABLE provider_payments
    DROP CONSTRAINT provider_payments_idempotency_key_key;

ALTER TABLE provider_payments
    ADD CONSTRAINT provider_payments_run_idempotency_key_key UNIQUE (run_id, idempotency_key);

CREATE UNIQUE INDEX provider_payments_legacy_idempotency_key_key
    ON provider_payments(idempotency_key) WHERE run_id IS NULL;
