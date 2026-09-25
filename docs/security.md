# Security Model

PayLab is a development and test tool. It does not process real payment credentials or card data and does not claim PCI, regulatory, or production payment-provider certification.

Security work focuses on the integration boundary and on preventing the harness from introducing obvious unsafe behavior of its own.

## Assets

- webhook signing secrets;
- payment/test evidence;
- configured callback targets;
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

Merchant integrations must verify the signature against the received raw bytes before trusting the
event. Parsing and reserializing JSON before verification is not equivalent because serialization
can change the byte representation.

`INVALID_SIGNATURE` sends the normal immutable event and exact persisted body with only the
`PayLab-Signature` value intentionally corrupted. Its delivery policy is persisted independently of
the test-run scenario, so workers do not infer security behavior from mutable control-plane state.
An HTTP rejection is useful transport evidence, but cannot prove the absence of merchant-internal
side effects; the scenario therefore has no conformance evaluator yet.

## Duplicate and replayed events

Provider events carry stable identities. Repeated delivery of the same event is expected behavior and must not be interpreted as multiple provider events.

Merchant integrations should apply an appropriate timestamp freshness policy to bound acceptance of
old callbacks. Duplicate delivery is exercised by `DUPLICATE_WEBHOOK`.

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

- the signing key is supplied through environment/configuration;
- there is no default signing secret;
- the signing secret is not stored in test-run records.

For the current callback contract, `PayLab-Signature` is lowercase hexadecimal HMAC-SHA256 over
`UTF8(PayLab-Timestamp) || "." || exactRawBody`. `PayLab-Timestamp` contains Unix epoch seconds and
`PayLab-Event-Id` contains the stable provider event UUID. The signing key comes only from
`paylab.webhook.signing-secret` / `PAYLAB_WEBHOOK_SIGNING_SECRET` and has no application default.

## Evidence integrity

Run evidence is append-only at the application level. Conformance responses refer to persisted event
IDs rather than rewriting evidence to match the current evaluation.

Cryptographic evidence-chain sealing is outside the current scope.

Webhook retry allowances and the concurrent-create peer wait are bounded by the implemented scenario
policies.

## Explicit non-goals

PayLab does not provide:

- PCI DSS certification;
- secure processing of real cardholder data;
- production merchant authentication;
- multi-tenant isolation guarantees;
- proof that a merchant is safe outside the scenarios and evidence evaluated by PayLab.
