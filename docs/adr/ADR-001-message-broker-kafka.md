# ADR-001 — Message broker: Apache Kafka + Schema Registry

**Status:** Accepted (user decision, 2026-06-10)
**Ticket:** 18.7-G01

## Context
The two source-of-truth images conflict: the detailed architecture diagram (designated "match to this") specifies **Apache Kafka + Schema Registry**; the tile board lists **RabbitMQ**. The codebase already implements the transactional-outbox pattern behind a broker-agnostic `lib-events EventPublisher` (currently `LogEventPublisher`).

## Decision
**Apache Kafka**, with Confluent Schema Registry for event-schema governance (compatibility = BACKWARD). Topic naming `gmepay.<aggregate>.<event>`, per-aggregate keys for ordering, acks=all idempotent producer.

## Consequences
- Tickets 17.4-G01..G05 proceed as written (KafkaEventPublisher, outbox drains, webhook consumer, Schema Registry).
- Event replay + consumer groups become available for reconciliation and audit rebuilds.
- RabbitMQ is removed from the stack list; `docs/STACK.md` updated; the tile board's broker entry is superseded by this ADR.
- Ops cost: ZooKeeper/KRaft + Schema Registry to operate (already present in `docker-compose.yml`).
