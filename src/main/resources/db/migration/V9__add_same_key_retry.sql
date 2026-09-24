ALTER TABLE test_runs DROP CONSTRAINT test_runs_scenario_configuration_check;
ALTER TABLE test_runs ADD CONSTRAINT test_runs_scenario_configuration_check CHECK (
    (scenario_id = 'ASYNC_SUCCESS' AND response_delay_millis IS NULL)
    OR (scenario_id = 'DUPLICATE_WEBHOOK' AND webhook_url IS NOT NULL
        AND response_delay_millis IS NULL)
    OR (scenario_id = 'SAME_KEY_RETRY' AND webhook_url IS NULL AND response_delay_millis IS NULL)
    OR (scenario_id IN ('TIMEOUT_BEFORE_COMMIT', 'TIMEOUT_AFTER_COMMIT') AND webhook_url IS NULL
        AND response_delay_millis BETWEEN 1 AND 30000)
);
