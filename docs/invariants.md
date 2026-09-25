# Payment Safety Invariants

PayLab evaluates behavior against a small set of explicit invariants. Each invariant is tied to one or more scenarios and must be backed by observable evidence.

## INV-01 — Transport ambiguity does not establish financial failure

If a side-effecting request may have reached provider execution, a timeout, connection loss, or ambiguous transport error must not be treated as proof that the financial operation failed.

Before initiating an equivalent new payment, the integration must resolve the original operation or safely replay it using the same idempotency identity.

**Why:** the provider may have committed the payment even though the response was lost.

**Covered by:** `TIMEOUT_AFTER_COMMIT`.

## INV-02 — Same idempotency key and same intent resolve to one operation

Repeated payment creation with the same idempotency key and the same material request must resolve to one logical provider payment.

**Why:** retries are normal network behavior; they must not duplicate the financial side effect.

**Covered by:** `SAME_KEY_RETRY`, `CONCURRENT_DUPLICATE_CREATE`.

## INV-03 — Same idempotency key and different intent is a conflict

An existing idempotency key must not be reused for materially different payment data.

For the current provider contract, the material request includes at least:

- operation type;
- amount in minor units;
- currency;
- merchant reference.

**Why:** one idempotency key must identify one financial intent.

**Covered by:** `KEY_REUSE_DIFFERENT_PAYLOAD`.

## INV-04 — Idempotency must survive concurrency

Concurrent requests carrying the same idempotency key and equivalent request data must not create multiple logical provider payments.

**Why:** sequential retry behavior does not prove safety when multiple application workers race.

**Covered by:** `CONCURRENT_DUPLICATE_CREATE`.

## INV-05 — Webhook authenticity is established before financial trust

A callback whose signature is invalid must not be treated as trusted payment evidence.

PayLab signs callbacks over a timestamp and the exact raw request body. Verification must be performed against those received bytes rather than a reconstructed JSON representation.

**Why:** a structurally valid callback can still be forged or tampered with.

**Covered by:** `INVALID_SIGNATURE`.

## INV-06 — Duplicate delivery does not mean duplicate financial events

The same provider event may be delivered more than once. Repeated delivery of the same event identity must not be interpreted as multiple independent payment events.

**Why:** asynchronous webhook systems commonly use at-least-once delivery.

**Covered by:** `DUPLICATE_WEBHOOK`.

## INV-07 — Stale event delivery must not regress payment state

An older provider event delivered after a newer authoritative event must not move the merchant back to an earlier payment state.

Example:

```text
provider state:  PROCESSING -> SUCCEEDED
delivery order:  SUCCEEDED -> PROCESSING (stale)
```

The merchant should remain semantically `SUCCEEDED`.

**Covered by:** `OUT_OF_ORDER_WEBHOOK`.

## INV-08 — Webhook delivery state is separate from payment state

Failure to deliver a callback does not undo or recreate the provider payment. Retrying notification delivery must not create another financial operation.

**Why:** webhook transport is notification state, not payment execution state.

**Covered by:** `WEBHOOK_RETRY`.

## INV-09 — A conformance verdict requires sufficient evidence

PayLab must not report `PASS` for an assertion that cannot be established from the evidence available to the run.

When an assertion depends on merchant-internal behavior outside PayLab's observation boundary,
insufficient evidence yields `INCONCLUSIVE`.

**Why:** a conformance tool should expose uncertainty rather than convert missing evidence into confidence.

**Covered by:** the conformance engine and any assertion requiring merchant-internal evidence.

## Provider model rules

The fictional provider uses integer minor units for money. Floating-point monetary values are not part of the provider domain model.

`SUCCEEDED` and `FAILED` are terminal provider states. Callback scheduling, retries, or delivery order do not rewrite provider truth after a terminal transition.
