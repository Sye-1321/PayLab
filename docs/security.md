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

`INVALID_SIGNATURE` covers forged or tampered callback evidence.

## Duplicate and replayed events

Provider events carry stable identities. Repeated delivery of the same event is expected behavior and must not be interpreted as multiple provider events.

A timestamp freshness policy can bound acceptance of old callbacks. Duplicate delivery is exercised by `DUPLICATE_WEBHOOK`; broader replay policy can be extended independently.

## Stale event delivery

A validly signed event can still be stale. Authenticity proves who produced the event, not that applying it would be semantically correct.

`OUT_OF_ORDER_WEBHOOK` exercises this distinction.

## Idempotency abuse and races

Idempotency safety depends on both request identity and database-enforced concurrency behavior.

PayLab rejects reuse of a key with materially different payment data and tests concurrent same-key creation against PostgreSQL.

## Callback target / SSRF risk

Because PayLab sends HTTP requests to merchant callback URLs, target configuration is an SSRF boundary.

Development defaults should restrict destinations to explicitly allowed hosts or local/container networks. Unsafe URL schemes are rejected, and arbitrary external destinations are not enabled implicitly.

## Secret handling

- secrets are supplied through environment/configuration rather than committed to source;
- signing secrets are redacted from logs and reports;
- evidence stores the metadata required to explain verification without persisting secret material.

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
