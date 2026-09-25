# PayLab

PayLab is a payment resilience conformance lab for backend integrations. It provides a fictional
payment provider, injects failure conditions that ordinary sandbox happy paths rarely exercise,
records evidence at the provider boundary, and evaluates that evidence against explicit
payment-safety rules.

The central question is:

> What does the backend do when the payment may have succeeded, but the network does not give it a
> clean answer?

PayLab focuses on server-to-server payment collection with asynchronous webhooks. It does not
process real money and is not an implementation of any specific payment service provider.

## Why it exists

Happy-path tests do not establish that an integration remains safe when a provider commits a
payment but the response is lost, concurrent requests race, callbacks fail or arrive more than
once, events arrive out of order, or an idempotency key is misused. Those conditions can expose
duplicate financial execution, stale state, unsafe retries, and trust in forged events.

PayLab does not infer merchant-internal side effects from HTTP acknowledgements. Assertions that
require evidence outside the current provider boundary remain `INCONCLUSIVE`.

## Scenarios

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

Detailed contracts are in [`docs/scenarios.md`](docs/scenarios.md).

## Verdicts

- `PASS` — sufficient evidence establishes the required behavior.
- `FAIL` — sufficient evidence establishes a violation.
- `INCONCLUSIVE` — the available evidence is insufficient.

Missing evidence is not a pass. See [`docs/conformance.md`](docs/conformance.md) for the evidence
model and the currently supported evaluators.

## Architecture

PayLab is a modular Java/Spring Boot application backed by PostgreSQL. PostgreSQL is the authority
for provider payments, idempotency, run evidence, and durable webhook work. Scenario-specific
executors inject faults without changing the payment model's core safety boundaries. Merchant
systems are external systems under test.

The provider API uses explicit test-run correlation. Run creation, retrieval, events, and supported
conformance evaluation are exposed over HTTP. `CONCURRENT_DUPLICATE_CREATE` adds a bounded,
process-local rendezvous before the normal PostgreSQL create operation so two requests exercise a
real uniqueness race.

See [`docs/architecture.md`](docs/architecture.md) for transaction, persistence, and delivery
details.

## Webhook contract

Webhook scenarios require a callback URL when creating the test run. PayLab delivers the exact
persisted JSON body with `Content-Type: application/json` and these headers:

- `PayLab-Event-Id`: stable provider event UUID;
- `PayLab-Timestamp`: Unix epoch seconds for the delivery attempt;
- `PayLab-Signature`: lowercase hexadecimal HMAC-SHA256 of the UTF-8 timestamp, a literal `.`, and
  the exact raw request body.

The signing key must be supplied as `paylab.webhook.signing-secret` (environment variable
`PAYLAB_WEBHOOK_SIGNING_SECRET`); there is no default. Delivery work, attempts, retries, intentional
duplicates, and event ordering are persisted in PostgreSQL. See [`docs/scenarios.md`](docs/scenarios.md)
for scenario behavior and [`docs/security.md`](docs/security.md) for trust boundaries.

## Current conformance support

`GET /test-runs/{runId}/conformance` currently evaluates:

- `TIMEOUT_AFTER_COMMIT` against `INV-01`;
- `SAME_KEY_RETRY` against `INV-02`;
- `KEY_REUSE_DIFFERENT_PAYLOAD` against `INV-03`.

Evaluation reads the current evidence snapshot and does not finalize the run or persist a verdict.
Other scenarios currently have no conformance evaluator.

## Safety and scope

PayLab is intended for development and test environments. It does not provide regulatory
certification, PCI compliance, production payment-provider compatibility, or proof about behavior
outside the scenarios and evidence it evaluates. Real cardholder data, real payment credentials,
and real customer funds are outside its scope.

## Documentation

- [`docs/invariants.md`](docs/invariants.md) — payment-safety rules
- [`docs/scenarios.md`](docs/scenarios.md) — detailed scenario contracts
- [`docs/conformance.md`](docs/conformance.md) — evidence and verdict semantics
- [`docs/architecture.md`](docs/architecture.md) — current system structure
- [`docs/security.md`](docs/security.md) — security model and trust boundaries
- [`docs/adr/`](docs/adr/) — durable architectural decisions
