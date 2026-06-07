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

## F1 — Money-path services (pending)
⬜ config-registry · ⬜ smart-router · ⬜ payment-executor · ⬜ transaction-mgmt ·
⬜ prefunding · ⬜ scheme-adapter (zeropay) · ⬜ api-gateway · ⬜ partner-api/webhook

## F2/F3 — Integration & loop-until-green (pending)
⬜ wire modules · ⬜ contract tests vs OpenAPI · ⬜ Testcontainers (needs Docker)
