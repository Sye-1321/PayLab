# Architecture

PayLab separates provider behavior, fault injection, evidence collection, and conformance evaluation so that a test result can explain both what happened and why it received its verdict.

## System context

```text
                              +-------------------+
                              | Merchant backend  |
                              +---------+---------+
                                        |
                              payment API requests
                                        |
                                        v
+----------------------------------------------------------------+
|                             PayLab                             |
|                                                                |
|  Provider   Scenario   Webhook   Evidence   Conformance         |
|  model      engine     delivery  recorder   evaluator           |
|                                                                |
+-------------------------------+--------------------------------+
                                |
                           PostgreSQL
                                |
                                +------------------------------+
                                                               |
                                                    signed callbacks
                                                               |
                                                               v
                                                    Merchant webhook

Optional probe: PayLab ---------------------------------> Merchant test state
```

The PayLab core is a modular Java/Spring Boot service. Reference merchants run separately because they are systems under test rather than internal modules.

## Core modules

### Provider

Owns the fictional payment-provider contract:

- payment creation;
- payment lookup;
- provider payment state;
- idempotency records;
- request fingerprinting.

### Scenario

Controls scenario-specific behavior and fault injection, including response delay/suppression, duplicate delivery, ordering changes, and signature mode.

A scenario changes provider behavior for a test run; it does not directly mutate merchant state.

`TIMEOUT_BEFORE_COMMIT` uses a partial unique PostgreSQL index and an atomic run-event insert as its
one-shot fault claim. The winning request commits `PRE_COMMIT_TIMEOUT_INJECTED` before provider
creation is considered; it then exits scenario execution without creating a payment. The HTTP error
boundary applies the configured delay after that database statement has completed. A concurrent
loser or later retry sees the consumed claim and follows normal provider creation.

### Webhook

Owns:

- provider event creation;
- signing;
- durable scheduling;
- delivery attempts;
- retry state.

### Evidence

Records an append-only timeline of material events from a test run, such as:

```text
RunStarted
MerchantRequestObserved
PaymentCommitted
ResponseSuppressed
MerchantRetryObserved
WebhookScheduled
WebhookDelivered
WebhookResponseObserved
AssertionEvaluated
RunCompleted
```

The timeline exists for explanation, debugging, and reevaluating assertions. PayLab does not require the entire application to be event-sourced.

### Conformance

Owns:

- assertions;
- evidence requirements;
- invariant references;
- verdict evaluation.

The first concrete evaluator covers only `TIMEOUT_AFTER_COMMIT` assertion
`AMBIGUOUS_OUTCOME_RECOVERY` (`INV-01`). It reads the persisted event sequence and authoritative
provider payment without writing evaluation events or results. Consequently, evaluation is
deterministic for a fixed database snapshot, while a later request or successful status lookup may
change a subsequent evaluation.

### Reporting

Renders evaluated results for humans and CI. Report formatting does not decide conformance.

## Provider payment model

The provider model is intentionally small:

```text
CREATED -> PROCESSING -> SUCCEEDED
                    \-> FAILED
```

`SUCCEEDED` and `FAILED` are terminal provider states.

Transport behavior is separate from provider truth. Delaying a response, retrying a callback, or reordering callback delivery does not rewrite the payment's provider state.

Money is represented as integer minor units plus currency code.

## Idempotency

A create-payment request carries an idempotency key. PayLab derives a deterministic fingerprint from the material request fields. The same-key guarantees below apply within a test run.

```text
same key + same fingerprint      -> same logical payment
same key + different fingerprint -> conflict
```

The database is authoritative for this guarantee. In-memory locks may be used as an optimization but are not the correctness boundary.

Concurrency tests run against PostgreSQL.

## Durable webhook delivery

Webhook delivery is durable work stored in PostgreSQL.

A provider transition that requires a callback persists the event and delivery work before the operation is considered durable. Workers claim due work using database locking suitable for concurrent consumers, such as `FOR UPDATE SKIP LOCKED`.

Network I/O is not performed while holding a long database transaction. Delivery attempts and retry scheduling are persisted so that process restart does not lose pending work.

Each delivery row has a successful-delivery target. Normal asynchronous success uses a target of one;
`DUPLICATE_WEBHOOK` uses two for the same immutable event and payload. A successful attempt returns
the row to immediately due `PENDING` work until the target is reached. Non-2xx and network outcomes
remain terminal rather than consuming or retrying toward that target. Claim-token checks prevent a
stale worker from recording over the current lease, although a crash after an HTTP send and before
result persistence can necessarily cause more wire deliveries than the configured target.

This design keeps the v0.1 consistency model in one transactional store. A message broker can be introduced later if independent consumers or measured throughput justify it.

## Test-run association

Every provider request evaluated by the conformance engine belongs to a test run. The run supplies the active scenario and its parameters and gives recorded evidence a stable correlation identity.

The external API should keep run correlation explicit without changing payment semantics. The precise control-plane endpoint and run-correlation mechanism are part of the API design rather than the payment-safety model.

## Determinism

Time, generated identifiers where relevant, and scenario randomness are injected rather than buried inside domain code. A run records the scenario version and deterministic inputs needed to reproduce the logical fault sequence.

## Persistence model

Expected core records include:

- payment;
- idempotency record;
- test run;
- run event;
- webhook event;
- webhook delivery attempt;
- assertion result;
- target configuration.

The final relational schema should follow transaction and query boundaries rather than mirror this list mechanically.

## Testing strategy

### Unit tests

Cover state transitions, request fingerprints, signature primitives, and assertion evaluation.

### PostgreSQL integration tests

Cover uniqueness, concurrent payment creation, worker claiming, retry scheduling, and restart recovery using Testcontainers.

### End-to-end tests

Run scenarios against reference merchants containing known-safe and intentionally unsafe behaviors.

### Property-based tests

Use generated inputs or event sequences for invariants where broad state-space exploration is valuable, especially terminal-state monotonicity and idempotency.

## Observability

Observability exists to explain runs, not to add dashboard surface area. Useful signals include scenario duration, webhook attempts/retries, assertion outcomes, idempotency conflicts, and trace correlation across one test run.
