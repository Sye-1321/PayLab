# Security Model

PayLab is a development and test tool. It does not process real payment credentials or card data and does not claim PCI, regulatory, or production payment-provider certification.

Security work focuses on the integration boundary and on preventing the harness from introducing obvious unsafe behavior of its own.

## Assets

- webhook signing secrets;
- payment/test evidence;
- target configuration;
- provider-state integrity;
- conformance-result integrity.

## Trust boundaries

```text
Merchant backend  <---- HTTP ---->  PayLab
       ^                            |
       |                            |
       +------ signed webhook ------+

PayLab <---- trusted application connection ----> PostgreSQL
```

## Webhook authenticity and tampering

PayLab signs callbacks using HMAC over a timestamp and the exact raw body.

The reference integration verifies the received raw bytes before trusting the event. Parsing and reserializing JSON before verification is not equivalent because serialization can change the byte representation.

`INVALID_SIGNATURE` sends the normal immutable event and exact persisted body with only the
`PayLab-Signature` value intentionally corrupted. Its delivery policy is persisted independently of
the test-run scenario, so workers do not infer security behavior from mutable control-plane state.
An HTTP rejection is useful transport evidence, but cannot prove the absence of merchant-internal
side effects; the scenario therefore has no conformance evaluator yet.

## Duplicate and replayed events

Provider events carry stable identities. Repeated delivery of the same event is expected behavior and must not be interpreted as multiple provider events.

A timestamp freshness policy can bound acceptance of old callbacks. Duplicate delivery is exercised by `DUPLICATE_WEBHOOK`; broader replay policy can be extended independently.

## Stale event delivery

A validly signed event can still be stale. Authenticity proves who produced the event, not that applying it would be semantically correct.

`OUT_OF_ORDER_WEBHOOK` now executes this distinction with two validly signed immutable snapshots:
the newer `PAYMENT_SUCCEEDED` event is delivered before the older `PAYMENT_PROCESSING` event while
provider truth remains `SUCCEEDED`. Authenticity does not imply freshness or semantic applicability.
There is no conformance evaluator yet because two HTTP acknowledgements cannot prove that merchant
state did not regress.

## Idempotency abuse and races

Idempotency safety depends on both request identity and database-enforced concurrency behavior.

PayLab rejects reuse of a key with materially different payment data and tests concurrent same-key creation against PostgreSQL.

## Callback target / SSRF risk

Because PayLab sends HTTP requests to merchant callback URLs, target configuration is an SSRF boundary.

Development defaults should restrict destinations to explicitly allowed hosts or local/container networks. Unsafe URL schemes are rejected, and arbitrary external destinations are not enabled implicitly.

The implemented callback validator accepts only absolute `http` or `https` URLs with a host and
without user-info. The default host allow-list is `localhost`, `127.0.0.1`, and `::1`. Additional
development/test host names must be explicitly supplied through the comma-separated
`paylab.webhook.allowed-hosts` configuration property (environment variable
`PAYLAB_WEBHOOK_ALLOWED_HOSTS`). The configured URL is stored on its test run; signing secrets are
not stored with runs.

## Secret handling

- secrets are supplied through environment/configuration rather than committed to source;
- signing secrets are redacted from logs and reports;
- evidence stores the metadata required to explain verification without persisting secret material.

For the current callback contract, `PayLab-Signature` is lowercase hexadecimal HMAC-SHA256 over
`UTF8(PayLab-Timestamp) || "." || exactRawBody`. `PayLab-Timestamp` contains Unix epoch seconds and
`PayLab-Event-Id` contains the stable provider event UUID. The signing key comes only from
`paylab.webhook.signing-secret` / `PAYLAB_WEBHOOK_SIGNING_SECRET` and has no application default.

## Evidence integrity

Run evidence is append-only at the application level. Reports refer back to recorded evidence instead of rewriting history to match the current projection.

Cryptographic evidence-chain sealing is outside the current scope.

## Resource limits

Test runs and callback retries are bounded. Payload size, retry count, and execution duration require limits so a target cannot create unbounded work.

## Explicit non-goals

PayLab does not provide:

- PCI DSS certification;
- secure processing of real cardholder data;
- production merchant authentication;
- multi-tenant isolation guarantees;
- bank-grade operational security;
- proof that a merchant is safe outside the scenarios and evidence evaluated by PayLab.
