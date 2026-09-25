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

**Purpose:** verify normal sequential idempotent replay. This scenario is executable with no webhook
URL or response delay.

**Provider behavior:** the first valid request creates one logical payment and progresses it to
`SUCCEEDED`. A later equivalent request using the same idempotency key resolves that existing payment
without another commit, webhook, or delay.

The `IDEMPOTENT_REPLAY` assertion returns `PASS` when persisted request-resolution evidence proves
the sequential same-key retry resolved the original payment. An equivalent request under a different
key is unsafe and returns `FAIL`, with failure taking precedence over safe replay evidence. Missing or
incomplete retry evidence is `INCONCLUSIVE`; same-key reuse with a different payload is not treated as
an equivalent retry and belongs to `KEY_REUSE_DIFFERENT_PAYLOAD`.

**Relevant invariant:** INV-02.

---

## SCN-05 — KEY_REUSE_DIFFERENT_PAYLOAD

**Purpose:** prevent one idempotency identity from representing two financial intents. This scenario
is executable with no webhook URL or response delay.

Example:

```text
request 1: key=ORDER-1 amountMinor=10000 currency=ETB
request 2: key=ORDER-1 amountMinor=50000 currency=ETB
```

**Provider behavior:** a materially different intent under a different key creates a distinct
`SUCCEEDED` payment. Reusing the original key for a different intent is defensively rejected with
HTTP `409` without altering the original payment.

The `IDEMPOTENCY_KEY_SCOPE` assertion returns `PASS` only when different request fingerprints use
different keys and resolve to distinct committed, authoritative `SUCCEEDED` payments. An observed
different fingerprint under the original key returns `FAIL` even though the provider correctly
rejects it: the violation is the integration's attempted idempotency-key reuse. Missing, unresolved,
or ambiguously paired second-intent evidence is `INCONCLUSIVE`. Unsafe reuse takes precedence over
earlier safe distinct-intent evidence.

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

**Merchant-internal condition:** the merchant processes one logical event / business side effect.

No conformance evaluator currently exists for this scenario. Exactly-once internal processing
cannot be established from HTTP acknowledgements alone.

**Relevant invariants:** INV-06, INV-09.

---

## SCN-07 — OUT_OF_ORDER_WEBHOOK

**Purpose:** test state monotonicity when event delivery order differs from provider-state order.

**Configuration:** a callback URL is required and `responseDelayMillis` is not accepted.

Example provider progression:

```text
PROCESSING -> SUCCEEDED
```

Delivery order:

```text
SUCCEEDED
PROCESSING   # older event delivered later
```

**Provider behavior:** progress one authoritative payment through `CREATED -> PROCESSING -> SUCCEEDED`,
then persist two independently serialized immutable snapshots. The older `PAYMENT_PROCESSING` event
has status `PROCESSING`; the newer `PAYMENT_SUCCEEDED` event has status `SUCCEEDED`. Their persisted
`createdAt` values preserve that logical order, while durable `due_at` values schedule `SUCCEEDED`
first and the stale `PROCESSING` callback second. Both deliveries use valid signatures and normal
one-attempt delivery semantics. Provider truth remains `SUCCEEDED` after both callbacks.

The late processing event is authentic but stale. Authenticity does not imply freshness or semantic
applicability.

**Merchant-internal condition:** merchant state remains semantically `SUCCEEDED` rather than
regressing to `PROCESSING`.

No conformance evaluator currently exists for this scenario. HTTP acknowledgement of both callbacks
proves transport receipt, not that merchant state remained monotonic; without merchant-internal
evidence, the assertion is `INCONCLUSIVE`.

**Relevant invariants:** INV-07, INV-09.

---

## SCN-08 — WEBHOOK_RETRY

**Purpose:** test callback recovery after temporary merchant failure.

**Configuration:** a callback URL is required and `responseDelayMillis` is not accepted.

**Provider behavior:**

1. create one `SUCCEEDED` provider payment and one immutable `PAYMENT_SUCCEEDED` event;
2. persist one delivery with an allowance of one failure retry;
3. record a first non-2xx or network failure and return the delivery to delayed `PENDING` work;
4. deliver the same event ID and exact persisted body bytes again;
5. mark the delivery `DELIVERED` after a 2xx response, or `FAILED` if the retry also fails.

Each attempt is durably recorded. A non-2xx attempt also records `WEBHOOK_RESPONSE_OBSERVED`; a
network error has no fabricated HTTP-response evidence. Provider payment truth remains `SUCCEEDED`
throughout. The retry reuses the original event and delivery rows and does not create another payment
or event.

`WEBHOOK_RETRY` is failure-driven redelivery. By contrast, `DUPLICATE_WEBHOOK` intentionally requires
two successful acknowledgements and treats any delivery failure as terminal. Failure retries and the
successful-delivery target are separate persisted policies.

No conformance evaluator exists for this scenario. A later 2xx response proves transport
acknowledgement, not exactly-once merchant-internal processing.

**Relevant invariant:** INV-08.

---

## SCN-09 — INVALID_SIGNATURE

**Purpose:** test rejection of untrusted callback evidence.

**Configuration:** a callback URL is required and `responseDelayMillis` is not accepted.

**Provider behavior:** progress one provider payment through `CREATED -> PROCESSING -> SUCCEEDED`,
then persist one normal, immutable `PAYMENT_SUCCEEDED` event and one delivery. The event ID, payment
data, timestamp format, and exact raw JSON body are unchanged. For the single delivery attempt,
PayLab computes the normal HMAC-SHA256 signature and deterministically corrupts one hexadecimal
character, leaving a plausible 64-character lowercase hexadecimal value. The persisted delivery
policy is `signature_mode = INVALID`, a target of one, and no failure retries.

The merchant HTTP response is recorded. A non-2xx response makes the delivery `FAILED`; a 2xx response
means only that the HTTP endpoint acknowledged the request. HTTP rejection is transport evidence and
does not by itself prove the merchant avoided payment-state updates, fulfillment, or other internal
side effects. No conformance evaluator is implemented for this scenario.

**Relevant invariant:** INV-05.

---

## SCN-10 — CONCURRENT_DUPLICATE_CREATE

**Purpose:** verify idempotency under a real request race.

**Configuration:** no callback URL or response delay is accepted.

**Provider behavior:** two real HTTP create requests with the same run and idempotency key enter
PayLab on separate request threads. PayLab briefly holds the first request before provider creation.
When the second participant arrives, both are released into
`JdbcProviderPaymentStore.createOrResolve` concurrently, where each uses an independent PostgreSQL
transaction. This rendezvous guarantees an actual provider-side create race rather than relying on
requests happening to overlap because they were sent close together.

**Expected result:** PostgreSQL uniqueness permits one logical payment, both successful callers
resolve to the same payment, and provider truth converges to `SUCCEEDED`. A later same-key replay
detects the existing payment and does not wait for another race participant, while still using
`createOrResolve` as the authoritative idempotency and fingerprint check.

The concurrency test must run against PostgreSQL rather than an in-memory database because database
uniqueness and transaction behavior are part of the scenario. The v0.1 rendezvous is process-local,
so both initial race participants must reach the same PayLab application instance.

**Relevant invariant:** INV-04.
