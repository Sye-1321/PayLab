# ADR-0003: Separate Provider-Boundary Evidence from Merchant-Internal Evidence

**Status:** Accepted

## Context

PayLab can observe traffic at the simulated provider boundary, but HTTP traffic cannot prove every internal merchant effect.

For example, three `200 OK` responses to duplicate webhooks do not prove that inventory, wallet credit, or fulfillment happened only once.

## Decision

Assertions declare the evidence they require.

Black-box assertions use provider-side and HTTP-boundary evidence. PayLab does not infer
merchant-internal state or side effects from HTTP acknowledgements.

If an assertion requires evidence outside the current provider boundary, insufficient evidence
produces `INCONCLUSIVE` rather than `PASS`.

## Consequences

- provider and HTTP observations remain useful without merchant instrumentation;
- verdicts distinguish established behavior from behavior that is not observable;
- assertions requiring merchant-internal evidence remain `INCONCLUSIVE` when that evidence is
  unavailable.
