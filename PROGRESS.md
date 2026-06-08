# Build Manifest / Checkpoint State

Tracks what compiles + tests green, so the coding pipeline can resume after a quota pause.
Legend: ✅ green (compiles + tests pass) · 🟡 scaffolded · ⬜ not started.

## F0 — Foundation (green)
| Module | State | Notes |
|---|---|---|
| build (Gradle multi-module, JDK 21) | ✅ | `./gradlew build` SUCCESSFUL |
| libs/lib-money | ✅ | Money + CurrencyScale, 3 tests |
| libs/lib-errors | ✅ | ErrorCode + ApiError + ApiException |
| libs/lib-events | ✅ | DomainEvent + EventPublisher |
| libs/lib-rate | ✅ | RateEngine (5-step, pool identity, short-circuit, identity legs) + 4 tests |
| services/rate-fx | ✅ | Spring Boot app + POST /v1/rates (bootJar builds) |

## F1 — Money-path services
| Module | State | Notes |
|---|---|---|
| libs/lib-domain | ✅ | Scheme/Partner/Rule/Direction + Rule margin invariant, 4 tests |
| services/config-registry | ✅ | POST /v1/rules/validate |
| libs/lib-prefunding | ✅ | PrefundingAccount deduct/credit/low-balance, 5 tests |
| services/prefunding | ✅ | Spring Boot app (DB-atomic SELECT FOR UPDATE impl pending — needs Postgres) |
| services/smart-router | ⬜ | country_code -> scheme, NO_SCHEME_FOR_LOCATION |
| services/payment-executor | ⬜ | CPM/MPM orchestration, CommitTransaction |
| services/transaction-mgmt | ⬜ | state machine, 8-step trail, idempotency |
| services/scheme-adapter (zeropay) | ⬜ | ACL: REST + SFTP, ZP00xx |
| services/api-gateway | ⬜ | Spring Cloud Gateway: HMAC, idempotency, /v1 |

## F2/F3 — Integration & loop-until-green (pending)
⬜ wire modules · ⬜ contract tests vs OpenAPI · ⬜ Testcontainers (needs Docker)
