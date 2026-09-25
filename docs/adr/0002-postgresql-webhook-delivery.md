# ADR-0002: PostgreSQL-Backed Durable Webhook Delivery

**Status:** Accepted

## Context

Webhook delivery must survive process restart, support retries, and allow multiple workers to claim due work safely.

The provider state, event creation, and callback scheduling also need clear transactional relationships.

## Decision

Store callback delivery work in PostgreSQL.

Workers claim due work with database concurrency primitives suitable for multiple consumers, such as row locking with `SKIP LOCKED`. Network I/O occurs outside long-running database transactions, and each delivery attempt is recorded before retry state is advanced.

A separate message broker is not part of this design.

## Consequences

- payment/event/delivery durability can be coordinated in one transactional store;
- process restart does not lose pending callback work;
- local operation stays simple;
- PostgreSQL carries both application state and durable work.
