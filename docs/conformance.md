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

`GET /test-runs/{runId}/conformance` currently supports `TIMEOUT_AFTER_COMMIT`, `SAME_KEY_RETRY`, and
`KEY_REUSE_DIFFERENT_PAYLOAD`.
It returns the run ID, scenario and version,
overall verdict, and an `assertions` array containing the assertion ID, invariant ID, verdict,
explanation, and ordered persisted evidence event IDs.

For `SAME_KEY_RETRY`, `IDEMPOTENT_REPLAY` (`INV-02`) requires a complete original operation and
authoritative `SUCCEEDED` provider state. A later equivalent same-key observation and resolution to
the original payment produces `PASS`; an equivalent new-key observation or a same-key resolution to
a different payment produces `FAIL`; and missing, incomplete, or different-payload retry evidence is
`INCONCLUSIVE`. Unsafe new-key evidence takes precedence over safe replay evidence.

For `KEY_REUSE_DIFFERENT_PAYLOAD`, `IDEMPOTENCY_KEY_SCOPE` (`INV-03`) requires a complete succeeded
original operation. A later different fingerprint under the original key produces `FAIL` from the
observation alone, regardless of the provider's expected defensive HTTP `409`. `PASS` requires a
different fingerprint under a different key to resolve unambiguously to a distinct committed,
authoritative `SUCCEEDED` payment. No second material intent, missing resolution or completion
evidence, and overlapping equivalent observations are `INCONCLUSIVE`. Conflicting key reuse takes
precedence over safe distinct-intent evidence.

For `TIMEOUT_AFTER_COMMIT`, the evaluator anchors the ambiguous operation at the first payment-referencing
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
