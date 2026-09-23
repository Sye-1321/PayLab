# Conformance Model

PayLab separates fault injection from evaluation. A scenario determines what the fictional provider does; assertions determine what can be concluded from the resulting evidence.

## Evidence levels

### Provider evidence

PayLab can directly establish facts inside the simulator, including:

- whether a payment committed;
- provider payment state;
- idempotency key and request fingerprint;
- the provider payment identity returned by each successfully resolved create request;
- provider event identity;
- webhook scheduling and delivery attempts;
- the fault injected by the scenario.

`PAYMENT_REQUEST_RESOLVED` explicitly links each successful create attempt's idempotency key and
request fingerprint to the provider payment identity returned by `createOrResolve`. Equivalent or
concurrent requests may therefore produce distinct resolution evidence rows that point to the same
payment. This evidence does not by itself implement conformance evaluation for same-key retry,
conflicting-key reuse, or concurrent duplicate creation.

### Black-box merchant evidence

At the provider boundary PayLab can observe:

- create-payment requests;
- idempotency keys;
- equivalent retries;
- status queries;
- request timing;
- webhook HTTP responses.

This is sufficient for assertions such as detecting a new-key retry after an ambiguous timeout.

### Probe evidence

Some assertions concern state inside the merchant application. PayLab cannot infer those facts from HTTP traffic alone.

A merchant may expose an optional test-only probe with the minimum state needed by a scenario, for example:

- merchant payment state;
- mapped provider payment ID;
- processed provider-event count;
- fulfillment/business-side-effect count.

The probe is not required for basic use. Assertions that require probe evidence become `INCONCLUSIVE` when it is unavailable.

## Verdicts

### PASS

Available evidence proves the required behavior.

### FAIL

Available evidence proves that the required behavior was violated.

### INCONCLUSIVE

The assertion cannot be established from the evidence available to the run.

`INCONCLUSIVE` is not treated as a weaker `PASS`.

## Example

```text
Scenario: TIMEOUT_AFTER_COMMIT

Provider evidence:
  payment P-1027 committed
  response intentionally withheld

Observed merchant behavior:
  original key: ORDER-881
  next equivalent POST: 847 ms later
  retry key: ORDER-881-RETRY
  request fingerprint: unchanged

Assertion:
  equivalent new financial operation must not be started before the
  ambiguous original operation is resolved

Verdict: FAIL
Invariant: INV-01
Risk: duplicate financial execution
```

## Assertion evidence must be explicit

A report should identify the evidence used for each material verdict rather than return only a status label.

For example, after three deliveries of the same webhook:

```text
callback transport handling: PASS
exactly-once merchant side effect: INCONCLUSIVE
```

when no merchant probe is available.

## Scenario verdict

A scenario's required assertions aggregate as follows:

- any required `FAIL` -> scenario `FAIL`;
- otherwise any required `INCONCLUSIVE` -> scenario `INCONCLUSIVE`;
- otherwise -> scenario `PASS`.

Informational assertions do not affect the scenario verdict.

## Current evaluation endpoint

`GET /test-runs/{runId}/conformance` currently supports only `TIMEOUT_AFTER_COMMIT`. It evaluates
the `AMBIGUOUS_OUTCOME_RECOVERY` assertion for `INV-01` and returns the run ID, scenario and version,
overall verdict, and an `assertions` array containing the assertion ID, invariant ID, verdict,
explanation, and ordered persisted evidence event IDs.

The evaluator anchors the ambiguous operation at the first payment-referencing
`RESPONSE_DELAY_INJECTED` event, finds an earlier `PAYMENT_COMMITTED` for the same payment, and
checks the authoritative payment is `SUCCEEDED`. After that anchor:

- any equivalent request under a different idempotency key produces `FAIL`, even if safe recovery
  evidence is also present;
- otherwise an equivalent same-key replay or successful status lookup of the original payment
  produces `PASS`;
- otherwise the result is `INCONCLUSIVE`, including when the post-commit ambiguity evidence itself
  is incomplete.

The endpoint evaluates the current evidence snapshot. It does not append events, persist a result,
complete a run, or otherwise mutate payment or run state. Later merchant evidence can therefore
change a subsequent evaluation until run finalization is implemented.

## Reports

The core report formats are intended to support both humans and CI:

- terminal output;
- JSON;
- JUnit XML.

A report should include:

- run ID;
- scenario and version;
- target identity;
- timestamps;
- overall verdict;
- assertion verdicts;
- invariant references;
- evidence references;
- relevant run configuration;
- whether probe evidence was available.

PayLab does not compute a generic reliability score. A list of evidence-backed scenario results is more useful than collapsing materially different failures into a single number.

## Conformance is not certification

A PayLab result describes behavior observed under the scenarios that were run. It is not regulatory certification, PCI certification, provider approval, or proof that every production failure mode has been covered.
