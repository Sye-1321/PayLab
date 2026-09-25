# PayLab

PayLab is a payment resilience conformance lab for backend integrations.

It provides a fictional payment provider, injects failure conditions that are difficult to exercise with ordinary sandboxes, records what the integration does at the provider boundary, and evaluates that behavior against explicit payment-safety rules.

The central question is simple:

> What does the backend do when the payment may have succeeded, but the network does not give it a clean answer?

PayLab focuses on server-to-server payment collection with asynchronous webhooks. It does not process real money and is not an implementation of any specific PSP.

## Why it exists

Happy-path tests can show that an integration can create a payment and receive a callback. They do not show whether the integration remains safe when:

- the provider commits a payment but the HTTP response is lost;
- the same callback is delivered more than once;
- callbacks arrive out of order;
- webhook delivery temporarily fails;
- an idempotency key is reused with different payment data;
- concurrent requests race on the same financial intent;
- a callback has an invalid signature.

Those cases are where duplicate execution, stale state, forged payment events, and incorrect retries tend to appear.

## How PayLab works

```text
Merchant backend
      |
      | payment requests
      v
+-------------------+
|      PayLab       |
|-------------------|
| provider simulator|
| scenario engine   |
| webhook delivery  |
| evidence recorder |
| conformance checks|
+-------------------+
      |
      | signed / delayed / duplicated / reordered webhooks
      v
Merchant webhook endpoint
```

A test run selects a scenario. Requests associated with that run are handled by the fictional provider under the scenario's rules. PayLab records provider-side truth, observed merchant requests, retries, status queries, webhook deliveries, and webhook responses. Assertions are then evaluated from that evidence.

When merchant-internal state is required to prove an assertion, PayLab can use an optional test-only probe exposed by the merchant. Without sufficient evidence, the result is `INCONCLUSIVE` rather than an assumed pass.

## Example: timeout after commit

```text
1. Merchant sends a create-payment request.
2. PayLab commits the payment.
3. PayLab intentionally withholds the HTTP response.
4. Merchant sees a timeout; provider truth is still SUCCEEDED.
5. PayLab observes the merchant's next action.
```

Safe behavior resolves or safely replays the original operation. Starting an equivalent payment with a new idempotency key before resolving the first operation is reported as a failure because it can create duplicate financial execution.

## Core scenarios

| Scenario | What it exercises |
| --- | --- |
| `ASYNC_SUCCESS` | Normal asynchronous payment flow |
| `TIMEOUT_BEFORE_COMMIT` | Transport failure before provider execution |
| `TIMEOUT_AFTER_COMMIT` | Ambiguous outcome after provider execution |
| `SAME_KEY_RETRY` | Idempotent replay |
| `KEY_REUSE_DIFFERENT_PAYLOAD` | Conflicting reuse of an idempotency key |
| `DUPLICATE_WEBHOOK` | At-least-once callback delivery |
| `OUT_OF_ORDER_WEBHOOK` | Stale event delivered after a newer event |
| `WEBHOOK_RETRY` | Callback failure followed by retry |
| `INVALID_SIGNATURE` | Rejection of untrusted callback evidence |
| `CONCURRENT_DUPLICATE_CREATE` | Idempotency under a real request race |

See [`docs/scenarios.md`](docs/scenarios.md) for the scenario contracts.

## Results

PayLab uses three verdicts:

- `PASS` — available evidence proves the required behavior;
- `FAIL` — available evidence proves a violation;
- `INCONCLUSIVE` — the assertion cannot be established from the available evidence.

The distinction is intentional. For example, receiving `200 OK` for three duplicate webhooks does not prove that the merchant executed an internal fulfillment side effect only once.

See [`docs/conformance.md`](docs/conformance.md) for the evidence and verdict model.

## Design

The core service is a modular Java/Spring Boot application backed by PostgreSQL. PostgreSQL is the authority for concurrency-sensitive state such as idempotency and durable webhook work. Reference merchants are separate applications because they are systems under test.

The architecture is described in [`docs/architecture.md`](docs/architecture.md).

## Security and limitations

PayLab is intended for development and test environments. It does not provide regulatory certification, PCI compliance, or production payment-provider compatibility. Real card data, real payment credentials, and real customer funds are outside its scope.

Webhook authenticity, replay/duplication behavior, callback targets, secret handling, and test evidence are covered in [`docs/security.md`](docs/security.md).

## Webhook contract

`ASYNC_SUCCESS`, `DUPLICATE_WEBHOOK`, and `WEBHOOK_RETRY` test runs require one callback URL, for example:

```json
{"scenario":"ASYNC_SUCCESS","webhookUrl":"http://localhost:8081/webhooks/paylab"}
```

The internal successful-payment webhook capability delivers the exact persisted JSON bytes using
`Content-Type: application/json`:

```json
{
  "eventId": "7bb847b5-44f2-4d4d-bf35-d4be8e4b172c",
  "type": "PAYMENT_SUCCEEDED",
  "createdAt": "2026-09-23T00:00:00Z",
  "data": {
    "paymentId": "payment-id",
    "amountMinor": 10000,
    "currency": "ETB",
    "merchantReference": "order-123",
    "status": "SUCCEEDED"
  }
}
```

Each attempt includes `PayLab-Event-Id`, `PayLab-Timestamp` (Unix epoch seconds), and
`PayLab-Signature`. The signature is the lowercase hexadecimal HMAC-SHA256 of the UTF-8 timestamp,
a literal `.`, and the exact raw request body. The instance secret must be supplied as
`paylab.webhook.signing-secret` (for example, environment variable
`PAYLAB_WEBHOOK_SIGNING_SECRET`); there is no default secret.

Creating a payment for an `ASYNC_SUCCESS` run advances provider truth from `CREATED` through
`PROCESSING` to `SUCCEEDED`, then durably schedules the signed callback. The payment response
therefore reports `SUCCEEDED`; callback HTTP delivery remains the webhook worker's responsibility.

`DUPLICATE_WEBHOOK` follows the same provider-state progression but intentionally delivers one
persisted `PAYMENT_SUCCEEDED` event twice. Both successful deliveries use the same event ID and exact
raw JSON bytes. Each attempt receives its own timestamp and valid signature. This is distinct from
retry behavior: any non-2xx response or network error is terminal, so the second delivery is scheduled
only after the first one succeeds. A worker crash after sending but before recording an acknowledgement
can still produce additional delivery attempts under the durable lease model. The scenario records
provider-side delivery evidence but has no conformance evaluator or merchant probe; HTTP 2xx responses
alone do not prove exactly-once merchant processing.

`WEBHOOK_RETRY` schedules one immutable event and one delivery with one persisted failure retry.
After the first non-2xx response or network error, the failed attempt is recorded and the delivery
returns to delayed `PENDING` work with its claim cleared. A later 2xx marks that same delivery
`DELIVERED`; a second failure exhausts the allowance and marks it `FAILED`. The event ID, raw body,
payment ID, event row, and delivery row remain unchanged across attempts. Unlike
`DUPLICATE_WEBHOOK`, this second attempt is caused by failure rather than an intentional second
successful delivery. This scenario has no conformance evaluator, and acknowledgement does not prove
exactly-once merchant processing.

`TIMEOUT_BEFORE_COMMIT` runs require `responseDelayMillis` from 1 through 30,000 and no webhook URL.
The first create request is recorded, atomically claims the run's one-shot pre-commit fault, and
records `PRE_COMMIT_TIMEOUT_INJECTED` without creating a provider payment. PayLab then delays the HTTP
response. A client whose timeout is shorter observes a timeout; a sufficiently patient client receives
`503 PRE_COMMIT_FAILURE`. Later requests proceed through normal payment creation to `SUCCEEDED`, with
no webhook scheduled. This scenario does not yet have a conformance evaluator.

`TIMEOUT_AFTER_COMMIT` runs require `responseDelayMillis` from 1 through 30,000 and do not accept a
webhook URL. A newly created payment reaches durable `SUCCEEDED` state before PayLab applies the
configured application response delay. To observe a timeout, the merchant client's HTTP timeout must
be shorter than that delay. A same-key equivalent retry resolves normally without applying the delay
again.

The current evidence snapshot for this scenario can be evaluated with
`GET /test-runs/{runId}/conformance`. The response contains the overall verdict and the
`AMBIGUOUS_OUTCOME_RECOVERY` assertion for `INV-01`, including the persisted run-event IDs that
support it. An equivalent same-key replay or a successful lookup of the original payment establishes
`PASS`; an equivalent request under a different key establishes `FAIL`; and incomplete fault or
recovery evidence is `INCONCLUSIVE`. Evaluation is read-only and is not final: later evidence can
change a subsequent result because run finalization and persisted certification results are not yet
implemented. `SAME_KEY_RETRY` has an evidence-backed `IDEMPOTENT_REPLAY` evaluator for `INV-02`, and
`KEY_REUSE_DIFFERENT_PAYLOAD` has an evidence-backed `IDEMPOTENCY_KEY_SCOPE` evaluator for `INV-03`;
other scenarios currently remain unsupported by the conformance endpoint.

## Documentation

- [`docs/invariants.md`](docs/invariants.md) — payment-safety rules PayLab evaluates
- [`docs/scenarios.md`](docs/scenarios.md) — failure scenarios and expected behavior
- [`docs/conformance.md`](docs/conformance.md) — evidence levels and verdict semantics
- [`docs/architecture.md`](docs/architecture.md) — system structure and persistence/concurrency design
- [`docs/security.md`](docs/security.md) — threat model and security boundaries
- [`docs/adr/`](docs/adr/) — architectural decisions whose rationale is useful to contributors
