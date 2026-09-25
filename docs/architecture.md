# Architecture

PayLab separates provider behavior, scenario execution, evidence collection, durable webhook
delivery, and conformance evaluation so that each supported verdict can be traced to recorded facts.

## System context

```text
Merchant backend
        |
        v
      PayLab
        |
        v
   PostgreSQL

PayLab
  |
  +------ signed callbacks ------> merchant webhook endpoint
```

The PayLab core is one modular Java/Spring Boot service. Merchant systems are external systems under
test.

## Current responsibilities

### Provider model

The provider owns payment creation and lookup, payment state, idempotency, and request
fingerprinting. Its state machine is:

```text
CREATED -> PROCESSING -> SUCCEEDED
                    \-> FAILED
```

`SUCCEEDED` and `FAILED` are terminal. Transport failures and webhook delivery state do not rewrite
provider truth. Money is represented as integer minor units plus a currency code.

### Scenario execution

Scenario-specific executors apply the configured response delay, pre-commit fault, webhook delivery
policy, event order, or signature mode while preserving the provider model and its transaction
boundaries.

`TIMEOUT_BEFORE_COMMIT` uses a partial unique PostgreSQL index and an atomic run-event insert to
claim its one-shot fault. The winning request commits `PRE_COMMIT_TIMEOUT_INJECTED` without creating
a payment; concurrent or later requests see the consumed claim and follow normal provider creation.

`CONCURRENT_DUPLICATE_CREATE` uses a bounded, process-local rendezvous keyed by test run and
idempotency key. The first two HTTP requests are released together immediately before the normal
`createOrResolve` operation. They use independent transactions, and PostgreSQL uniqueness remains
the race authority. Both participants must reach the same PayLab instance.

### Run evidence

PayLab records an append-only application-level timeline using the implemented `RunEventType`
values:

```text
RUN_STARTED
MERCHANT_REQUEST_OBSERVED
PAYMENT_REQUEST_RESOLVED
PRE_COMMIT_TIMEOUT_INJECTED
PAYMENT_COMMITTED
RESPONSE_DELAY_INJECTED
MERCHANT_STATUS_QUERY_OBSERVED
WEBHOOK_SCHEDULED
WEBHOOK_DELIVERED
WEBHOOK_RESPONSE_OBSERVED
```

The timeline supports explanation, debugging, and reevaluation; the application is not event
sourced.

### Conformance evaluation

The current conformance endpoint evaluates `TIMEOUT_AFTER_COMMIT`, `SAME_KEY_RETRY`, and
`KEY_REUSE_DIFFERENT_PAYLOAD`. Open runs are evaluated from persisted events and authoritative
provider payments. Finalization stores the exact evaluation and sets `test_runs.finalized_at` in one
transaction. Finalized conformance requests read that snapshot rather than later evidence, and new
run-scoped payment creation and lookup requests are rejected.

## Idempotency boundary

A create-payment request carries an idempotency key. PayLab derives a deterministic fingerprint
from its material fields. Within a test run:

```text
same key + same fingerprint      -> same logical payment
same key + different fingerprint -> conflict
```

PostgreSQL uniqueness and transactions are the correctness boundary. The concurrency tests use
PostgreSQL rather than an in-memory substitute.

## Durable webhook delivery

Provider events, webhook deliveries, and delivery attempts are persisted in PostgreSQL. Workers
claim due deliveries with row locking and `SKIP LOCKED`; network I/O occurs outside the claim
transaction. Claim-token checks prevent a stale worker from recording over the current lease. A
crash after an HTTP send but before result persistence can still cause an additional wire delivery.

The delivery row separates its successful-delivery target from its failure-retry allowance:

- normal asynchronous success targets one successful delivery with no failure retry;
- `DUPLICATE_WEBHOOK` targets two successful deliveries of the same immutable event and treats a
  failure as terminal;
- `WEBHOOK_RETRY` targets one success and permits one persisted failure retry on the same event and
  delivery;
- `INVALID_SIGNATURE` persists an invalid signature mode for one attempt;
- `OUT_OF_ORDER_WEBHOOK` persists separate processing and succeeded snapshots and schedules the
  succeeded event first.

Payment state and webhook delivery state remain separate.

## Test-run correlation

`POST /test-runs` creates a run containing its scenario identity, version, and relevant configured
parameters. Run retrieval, event retrieval, and conformance evaluation are exposed under
`/test-runs/{runId}`. Payment creation and lookup carry the run ID explicitly, and recorded provider
and webhook evidence is correlated to that run.

## Persistence model

The persisted records that materially define current behavior are:

- test runs;
- provider payments, including idempotency key and request fingerprint;
- run events;
- webhook events;
- webhook deliveries;
- delivery attempts;
- finalized conformance results and their ordered assertions.

## Current testing

The repository uses domain tests, Spring/HTTP integration tests, PostgreSQL/Testcontainers tests,
and real concurrent-request tests. They cover state transitions, fingerprints and uniqueness,
scenario fault/recovery behavior, run evidence, webhook signatures, durable claim/lease behavior,
retry and duplicate delivery, event ordering, and conformance responses.
