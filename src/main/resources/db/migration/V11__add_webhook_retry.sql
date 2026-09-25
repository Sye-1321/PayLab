ALTER TABLE test_runs DROP CONSTRAINT test_runs_scenario_configuration_check;
ALTER TABLE test_runs ADD CONSTRAINT test_runs_scenario_configuration_check CHECK (
    (scenario_id = 'ASYNC_SUCCESS' AND response_delay_millis IS NULL)
    OR (scenario_id IN ('DUPLICATE_WEBHOOK', 'WEBHOOK_RETRY')
        AND webhook_url IS NOT NULL AND response_delay_millis IS NULL)
    OR (scenario_id IN ('SAME_KEY_RETRY', 'KEY_REUSE_DIFFERENT_PAYLOAD')
        AND webhook_url IS NULL AND response_delay_millis IS NULL)
    OR (scenario_id IN ('TIMEOUT_BEFORE_COMMIT', 'TIMEOUT_AFTER_COMMIT') AND webhook_url IS NULL
        AND response_delay_millis BETWEEN 1 AND 30000)
);

ALTER TABLE webhook_deliveries
    ADD COLUMN max_failure_retries INTEGER NOT NULL DEFAULT 0
        CHECK (max_failure_retries BETWEEN 0 AND 1),
    ADD COLUMN acknowledged_delivery_count INTEGER NOT NULL DEFAULT 0
        CHECK (acknowledged_delivery_count >= 0),
    ADD COLUMN failure_count INTEGER NOT NULL DEFAULT 0
        CHECK (failure_count >= 0);
