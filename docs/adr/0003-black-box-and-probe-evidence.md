# ADR-0003: Separate Black-Box Evidence from Optional Merchant Probe Evidence

**Status:** Accepted

## Context

PayLab can observe traffic at the simulated provider boundary, but HTTP traffic cannot prove every internal merchant effect.

For example, three `200 OK` responses to duplicate webhooks do not prove that inventory, wallet credit, or fulfillment happened only once.

## Decision

Assertions declare the evidence they require.

Black-box assertions use provider-side and HTTP-boundary evidence. Assertions about merchant-internal state may use an optional test-only probe.

If the required evidence is unavailable, the assertion returns `INCONCLUSIVE` rather than `PASS`.

## Consequences

- PayLab remains useful without merchant instrumentation;
- deeper assertions are possible when a probe is available;
- reports distinguish observed behavior from inferred internal behavior;
- probe design must remain narrow and test-only rather than becoming a general introspection API.
