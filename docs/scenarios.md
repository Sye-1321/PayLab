# Scenario Catalog

A PayLab scenario defines the provider behavior and fault conditions for one test run. The scenario controls provider-side behavior; the conformance layer separately evaluates the merchant's observed response.

## Scenario summary

| ID | Scenario | Purpose |
| --- | --- | --- |
| SCN-01 | `ASYNC_SUCCESS` | Establish the normal asynchronous baseline |
| SCN-02 | `TIMEOUT_BEFORE_COMMIT` | Exercise a request that fails before provider execution |
| SCN-03 | `TIMEOUT_AFTER_COMMIT` | Exercise an ambiguous outcome after provider execution |
| SCN-04 | `SAME_KEY_RETRY` | Verify idempotent replay |
| SCN-05 | `KEY_REUSE_DIFFERENT_PAYLOAD` | Reject conflicting reuse of an idempotency key |
| SCN-06 | `DUPLICATE_WEBHOOK` | Exercise at-least-once callback delivery |
| SCN-07 | `OUT_OF_ORDER_WEBHOOK` | Exercise stale event delivery after a newer event |
| SCN-08 | `WEBHOOK_RETRY` | Exercise temporary callback failure and later retry |
| SCN-09 | `INVALID_SIGNATURE` | Exercise rejection of untrusted callback evidence |
| SCN-10 | `CONCURRENT_DUPLICATE_CREATE` | Verify idempotency under concurrency |

## SCN-01 — ASYNC_SUCCESS

**Purpose:** establish the normal asynchronous payment flow.

**Provider behavior:**

1. accept a valid create-payment request;
2. persist the payment;
3. advance provider state through normal processing;
4. return the normal API response;
5. durably schedule a signed `SUCCEEDED` webhook for independent worker delivery.

The create-payment response reflects the resulting authoritative `SUCCEEDED` provider state.

**Black-box evidence:** create request, provider payment identity, callback delivery, callback response, optional status query.

**Expected result:** required baseline assertions pass.

---

## SCN-02 — TIMEOUT_BEFORE_COMMIT

**Purpose:** distinguish a transport failure that occurs before provider execution from an ambiguous post-commit failure.

**Provider behavior:**

1. record `MERCHANT_REQUEST_OBSERVED`;
2. atomically claim the run's one-shot fault by recording `PRE_COMMIT_TIMEOUT_INJECTED` with the
   request key, fingerprint, and configured delay;
3. create no provider payment for the faulted request;
4. delay the HTTP response by `responseDelayMillis`;
5. return `503 PRE_COMMIT_FAILURE` if the client remains connected through the delay.

The intended observation is a client timeout when its HTTP timeout is shorter than PayLab's bounded
application delay. This is not a TCP reset, packet loss, or socket termination.

**Provider truth:** no logical payment exists for the failed attempt.

**Expected merchant behavior:** a retry of the same financial intent can safely create one payment.
The pre-commit fault is consumed once per run. A later request creates or resolves through normal
run-scoped idempotency, progresses `CREATED -> PROCESSING -> SUCCEEDED`, and records
`PAYMENT_COMMITTED` only for a newly created payment. No webhook is scheduled.

This is distinct from `TIMEOUT_AFTER_COMMIT`, where a `SUCCEEDED` provider payment exists before the
response delay. `TIMEOUT_BEFORE_COMMIT` is executable but does not yet have a conformance evaluator.

**Relevant invariants:** INV-02.

---

## SCN-03 — TIMEOUT_AFTER_COMMIT

**Purpose:** test recovery from the highest-value ambiguous payment failure.

**Provider behavior:**

1. accept the create-payment request;
2. persist the idempotency record and payment;
3. progress and durably commit provider state as `SUCCEEDED`;
4. record the committed-payment evidence;
5. delay the normal HTTP response by the run's configured duration.

The implemented fault is a bounded application response delay, not a connection reset or packet loss.
The configured delay must exceed the merchant client's HTTP timeout to produce an observable timeout.
An equivalent same-key replay resolves the existing payment without injecting the delay again.

**Provider truth:** the payment exists even though the merchant did not receive a successful response.

**Safe merchant behavior:** resolve the existing operation by status lookup or safely replay the same request with the same idempotency key.

**Failure condition:** the merchant initiates an equivalent payment under a new idempotency key before resolving the first operation.

**Required evidence:** original request and key where available, provider commit event, injected
response-delay event, authoritative succeeded provider payment, and subsequent equivalent requests
or a successful lookup of the original payment. Successful payment lookups are recorded as
`MERCHANT_STATUS_QUERY_OBSERVED` only after the run-scoped payment is found.

The current `AMBIGUOUS_OUTCOME_RECOVERY` evaluation returns `PASS` for equivalent same-key replay or
successful original-payment status lookup, `FAIL` for an equivalent new-key request, and
`INCONCLUSIVE` when required fault or recovery evidence is absent. Failure evidence takes precedence
over safe-recovery evidence.

**Relevant invariant:** INV-01.

---

## SCN-04 — SAME_KEY_RETRY

**Purpose:** verify normal idempotent replay.

**Provider behavior:** accept repeated equivalent requests with the same idempotency key and resolve them to the same logical payment.

**Assertions:** one logical payment exists and replay responses identify that operation.

**Relevant invariant:** INV-02.

---

## SCN-05 — KEY_REUSE_DIFFERENT_PAYLOAD

**Purpose:** prevent one idempotency identity from representing two financial intents.

Example:

```text
request 1: key=ORDER-1 amountMinor=10000 currency=ETB
request 2: key=ORDER-1 amountMinor=50000 currency=ETB
```

**Provider behavior:** reject the second request as an idempotency conflict without altering the original payment.

**Relevant invariant:** INV-03.

---

## SCN-06 — DUPLICATE_WEBHOOK

**Purpose:** test at-least-once callback delivery.

**Configuration:** a callback URL is required and `responseDelayMillis` is not accepted.

**Provider behavior:** create one successful provider payment and one immutable
`PAYMENT_SUCCEEDED` event, then intentionally deliver that event twice. After the first 2xx response,
the durable delivery returns to `PENDING`; after the second 2xx response, it becomes `DELIVERED`.
Both requests contain the same event ID and exact persisted payload bytes, while their timestamps and
signatures are generated per attempt. A non-2xx response or network error is terminal and does not
trigger the second intentional delivery.

This is not the `WEBHOOK_RETRY` scenario. The two deliveries are the configured successful path, not
recovery from a temporary failure. The normal `ASYNC_SUCCESS` scenario retains a target of one
successful delivery. Durable claims are leased, so a worker crash after sending but before recording
the result can cause an additional attempt beyond the configured target.

**Black-box assertions:** the repeated deliveries occur as configured and merchant responses are recorded.

**Probe-backed assertion:** the merchant processes one logical event / business side effect.

No conformance evaluator or merchant probe is implemented for this scenario. Exactly-once internal
processing therefore cannot be inferred from HTTP acknowledgements.

**Relevant invariants:** INV-06, INV-09.

---

## SCN-07 — OUT_OF_ORDER_WEBHOOK

**Purpose:** test state monotonicity when event delivery order differs from provider-state order.

Example provider progression:

```text
PROCESSING -> SUCCEEDED
```

Delivery order:

```text
SUCCEEDED
PROCESSING   # older event delivered later
```

**Probe-backed pass condition:** merchant state remains semantically `SUCCEEDED`.

**Probe-backed failure condition:** merchant regresses to `PROCESSING`.

Without merchant-state evidence, the internal-state assertion is `INCONCLUSIVE`.

**Relevant invariants:** INV-07, INV-09.

---

## SCN-08 — WEBHOOK_RETRY

**Purpose:** test callback recovery after temporary merchant failure.

**Provider behavior:**

1. create one provider event;
2. attempt delivery;
3. record a retryable failure;
4. schedule a later attempt;
5. deliver the same event identity again.

**Assertions:** payment truth remains unchanged, attempt history is retained, and retry does not create a new payment.

**Relevant invariant:** INV-08.

---

## SCN-09 — INVALID_SIGNATURE

**Purpose:** test rejection of untrusted callback evidence.

**Provider behavior:** deliver a structurally valid callback with an intentionally invalid signature. A tampered-body variant may sign one body and deliver altered bytes.

**Pass condition:** the merchant rejects the callback according to the integration contract and does not treat it as trusted payment evidence.

**Relevant invariant:** INV-05.

---

## SCN-10 — CONCURRENT_DUPLICATE_CREATE

**Purpose:** verify idempotency under a real request race.

**Provider behavior:** release multiple equivalent create requests concurrently with the same idempotency key.

**Expected result:** exactly one logical payment exists and successful callers resolve to that same operation.

The concurrency test must run against PostgreSQL rather than an in-memory database because database uniqueness and locking are part of the behavior under test.

**Relevant invariant:** INV-04.

## Scenario reproducibility

A run records the scenario identity, scenario version, relevant parameters, and deterministic seed where randomness is used. Reproduction targets the same logical fault sequence; real network duration does not need to be byte-for-byte identical.
