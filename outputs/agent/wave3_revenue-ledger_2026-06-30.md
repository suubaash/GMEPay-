> 작업: Wave3 revenue-ledger idempotent residual / 출처: agent

# Wave-3 — revenue-ledger: idempotent rounding-residual on retry

## Build
`./gradlew :services:revenue-ledger:test` → BUILD SUCCESSFUL. 73 tests, 0 failures.
4 new idempotency tests confirmed run (not skipped).

## Idempotency mechanism
- `postRoundingResidual(reference, residual, currency)` now pre-checks via new port method
  `JournalStore.findRoundingResidualByReference(reference)`; on a hit it returns the existing journal
  (same id) and posts nothing → no second line, aggregate counts once.
- DB backstop: Flyway **V006** adds `rounding_residual_keys(reference PRIMARY KEY, journal_id, posted_at)`.
  `JpaJournalStore.save` inserts the guard row in the SAME transaction iff the journal touches
  `REVENUE_ROUNDING`; a concurrent double-post trips the PK and rolls back.
- Used a dedicated key table, NOT a partial/expression UNIQUE INDEX, because H2 in PostgreSQL MODE (the
  no-Docker `@DataJpaTest` engine) supports neither `CREATE UNIQUE INDEX ... WHERE` nor expression
  indexes — verified directly. A plain PK is portable across H2 + PostgreSQL.
- Coexistence: guard scoped to the `REVENUE_ROUNDING` account only. payment-executor per-TXN refs and
  settlement-reconciliation per-batch ids both flow through the same path; revenue-capture / fee-share /
  reversal journals carrying the same reference on OTHER accounts are untouched — no collision.
- `InMemoryJournalStore` + the unit-test stub implement the new port method too (scan for a
  REVENUE_ROUNDING line on the reference).

## Files
- LedgerPostingService.java (guard), JournalStore.java (port +1 method)
- JpaJournalStore.java + InMemoryJournalStore.java (impl)
- RoundingResidualKeyEntity.java + RoundingResidualKeyRepository.java (new)
- V006__rounding_residual_idempotency.sql (new)
- RoundingResidualController.java javadoc, CHANGELOG.md
- Tests: JournalPersistenceIT (+3), RoundingResidualTest (+1)

## Remaining (≤3)
1. No concurrency/race test in the suite (in-memory + H2 single-threaded); the PK backstop is reasoned,
   not test-exercised. A Testcontainers PG concurrent-post IT would prove the rollback path.
2. `findByReference` still returns ALL journals for a ref (rounding + capture + fee-share + reversal);
   callers wanting only the residual should use the new `findRoundingResidualByReference`.
3. Cross-service: settlement-reconciliation/payment-executor calls are FROZEN here — verify at
   integration that both bind `POST /v1/journals/rounding-residual` and tolerate the 200-same-id no-op.
