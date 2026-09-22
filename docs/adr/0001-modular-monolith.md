# ADR-0001: Modular Monolith for the Core Service

**Status:** Accepted

## Context

Provider simulation, scenario control, webhook delivery, evidence, and conformance are separate responsibilities, but they share transaction boundaries and are deployed together in the initial system.

Splitting them into network services would introduce distributed consistency and deployment concerns without creating a necessary product boundary.

## Decision

Run the PayLab core as one Spring Boot deployable with explicit internal module boundaries.

Reference merchants remain separate applications because they are systems under test.

## Consequences

- provider and evidence transactions are easier to reason about;
- local execution requires fewer moving parts;
- module boundaries must be maintained in code rather than enforced by network separation;
- a module can be extracted later if it develops an independent scaling, security, or deployment requirement.
