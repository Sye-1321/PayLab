ALTER TABLE test_runs ADD COLUMN finalized_at TIMESTAMPTZ NULL;

CREATE TABLE finalized_conformance_results (
    run_id UUID PRIMARY KEY REFERENCES test_runs(run_id),
    verdict TEXT NOT NULL CHECK (verdict IN ('PASS', 'FAIL', 'INCONCLUSIVE'))
);

CREATE TABLE finalized_conformance_assertions (
    run_id UUID NOT NULL REFERENCES finalized_conformance_results(run_id),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    assertion_id TEXT NOT NULL,
    invariant_id TEXT NOT NULL,
    verdict TEXT NOT NULL CHECK (verdict IN ('PASS', 'FAIL', 'INCONCLUSIVE')),
    explanation TEXT NOT NULL,
    evidence_event_ids BIGINT[] NOT NULL,
    PRIMARY KEY (run_id, ordinal)
);
