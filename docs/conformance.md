# Conformance Model

PayLab separates fault injection from evaluation. A scenario determines what the fictional provider
does; an evaluator determines what the available evidence establishes.

## Evidence

### Provider evidence

PayLab can directly establish facts inside the simulator, including:

- whether a payment committed and its authoritative state;
- idempotency keys, request fingerprints, and resolved provider payment identities;
- the fault injected by a scenario;
- provider event identity, webhook scheduling, delivery attempts, and HTTP responses.

`PAYMENT_REQUEST_RESOLVED` links each successful create attempt's key and fingerprint to the payment
returned by `createOrResolve`. Equivalent or concurrent requests can therefore produce distinct
evidence rows that resolve to the same payment.

### Black-box merchant evidence

At the provider boundary PayLab observes create-payment requests, idempotency keys, equivalent
retries, status queries, request timing, and webhook HTTP responses. This is sufficient for some
assertions, such as detecting a new-key retry after an ambiguous timeout.

Merchant-internal side effects are outside PayLab's current observation boundary. Assertions
requiring that evidence cannot be promoted to `PASS` from HTTP acknowledgements alone.

## Verdicts

- `PASS`: sufficient evidence establishes the required behavior.
- `FAIL`: sufficient evidence establishes a violation.
- `INCONCLUSIVE`: the available evidence is insufficient.

`INCONCLUSIVE` is not a weaker `PASS`; missing evidence is not a pass.

## Scenario verdict

Required assertions aggregate as follows:

- any required `FAIL` produces scenario `FAIL`;
- otherwise any required `INCONCLUSIVE` produces scenario `INCONCLUSIVE`;
- otherwise the scenario is `PASS`.

Informational assertions do not affect the scenario verdict.

## Current evaluation endpoint

`GET /test-runs/{runId}/conformance` currently supports `TIMEOUT_AFTER_COMMIT`, `SAME_KEY_RETRY`, and
`KEY_REUSE_DIFFERENT_PAYLOAD`. Its response contains the run ID, scenario and version, overall
verdict, and assertions with their assertion ID, invariant ID, verdict, explanation, and ordered
persisted evidence event IDs.

### TIMEOUT_AFTER_COMMIT

`AMBIGUOUS_OUTCOME_RECOVERY` (`INV-01`) anchors the ambiguous operation at the first
payment-referencing `RESPONSE_DELAY_INJECTED` event, requires an earlier `PAYMENT_COMMITTED` for the
same authoritative `SUCCEEDED` payment, then evaluates later evidence:

- an equivalent request under a different idempotency key produces `FAIL`;
- otherwise an equivalent same-key replay or successful lookup of the original payment produces
  `PASS`;
- incomplete ambiguity or recovery evidence produces `INCONCLUSIVE`.

Unsafe new-key evidence takes precedence over safe recovery evidence.

### SAME_KEY_RETRY

`IDEMPOTENT_REPLAY` (`INV-02`) requires a complete original operation and authoritative `SUCCEEDED`
provider state. A later equivalent same-key observation that resolves to the original payment
produces `PASS`. An equivalent new-key observation or same-key resolution to a different payment
produces `FAIL`. Missing, incomplete, or different-payload retry evidence is `INCONCLUSIVE`.

Unsafe new-key evidence takes precedence over safe replay evidence.

### KEY_REUSE_DIFFERENT_PAYLOAD

`IDEMPOTENCY_KEY_SCOPE` (`INV-03`) requires a complete succeeded original operation. A later
different fingerprint under the original key produces `FAIL` from that observation, regardless of
the provider's defensive HTTP `409`. `PASS` requires the different intent to use a different key and
resolve to a distinct committed, authoritative `SUCCEEDED` payment. Missing resolution or completion
evidence is `INCONCLUSIVE`.

Conflicting key reuse takes precedence over safe distinct-intent evidence.

## Evaluation lifecycle

The endpoint evaluates the current evidence snapshot. It does not append events, persist a verdict,
complete a run, or mutate payment state. Later evidence can therefore change a subsequent response.
Each assertion includes the evidence references supporting its decision.

## Conformance is not certification

A PayLab result describes behavior observed under the scenarios that were run. It is not regulatory
certification, PCI certification, provider approval, or proof that every production failure mode has
been covered.
