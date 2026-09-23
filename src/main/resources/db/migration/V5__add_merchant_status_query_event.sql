ALTER TABLE run_events DROP CONSTRAINT run_events_fields_check;
ALTER TABLE run_events DROP CONSTRAINT run_events_event_type_check;
ALTER TABLE run_events ADD CONSTRAINT run_events_event_type_check CHECK (event_type IN (
    'RUN_STARTED', 'MERCHANT_REQUEST_OBSERVED', 'PAYMENT_COMMITTED', 'RESPONSE_DELAY_INJECTED',
    'MERCHANT_STATUS_QUERY_OBSERVED', 'WEBHOOK_SCHEDULED', 'WEBHOOK_DELIVERED',
    'WEBHOOK_RESPONSE_OBSERVED'
));
ALTER TABLE run_events ADD CONSTRAINT run_events_fields_check CHECK (
    (event_type = 'RUN_STARTED' AND idempotency_key IS NULL AND request_fingerprint IS NULL
        AND webhook_event_id IS NULL AND http_status IS NULL AND outcome IS NULL
        AND payment_id IS NULL AND response_delay_millis IS NULL)
    OR (event_type = 'MERCHANT_REQUEST_OBSERVED' AND idempotency_key IS NOT NULL
        AND request_fingerprint IS NOT NULL AND webhook_event_id IS NULL
        AND http_status IS NULL AND outcome IS NULL AND payment_id IS NULL
        AND response_delay_millis IS NULL)
    OR (event_type = 'PAYMENT_COMMITTED' AND idempotency_key IS NULL
        AND request_fingerprint IS NULL AND webhook_event_id IS NULL
        AND http_status IS NULL AND outcome IS NULL AND payment_id IS NOT NULL
        AND response_delay_millis IS NULL)
    OR (event_type = 'RESPONSE_DELAY_INJECTED' AND idempotency_key IS NULL
        AND request_fingerprint IS NULL AND webhook_event_id IS NULL
        AND http_status IS NULL AND outcome IS NULL AND payment_id IS NOT NULL
        AND response_delay_millis BETWEEN 1 AND 30000)
    OR (event_type = 'MERCHANT_STATUS_QUERY_OBSERVED' AND idempotency_key IS NULL
        AND request_fingerprint IS NULL AND webhook_event_id IS NULL
        AND http_status IS NULL AND outcome IS NULL AND payment_id IS NOT NULL
        AND response_delay_millis IS NULL)
    OR (event_type = 'WEBHOOK_SCHEDULED' AND idempotency_key IS NULL
        AND request_fingerprint IS NULL AND webhook_event_id IS NOT NULL
        AND http_status IS NULL AND outcome IS NULL AND payment_id IS NULL
        AND response_delay_millis IS NULL)
    OR (event_type = 'WEBHOOK_DELIVERED' AND idempotency_key IS NULL
        AND request_fingerprint IS NULL AND webhook_event_id IS NOT NULL
        AND http_status BETWEEN 200 AND 299 AND outcome = 'ACKNOWLEDGED'
        AND payment_id IS NULL AND response_delay_millis IS NULL)
    OR (event_type = 'WEBHOOK_RESPONSE_OBSERVED' AND idempotency_key IS NULL
        AND request_fingerprint IS NULL AND webhook_event_id IS NOT NULL
        AND http_status IS NOT NULL AND outcome IS NOT NULL AND payment_id IS NULL
        AND response_delay_millis IS NULL)
);
