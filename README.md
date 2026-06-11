# GMEPay+ — Global QR Payment Hub (monorepo)

Java 21 · Spring Boot 3.3 · Gradle multi-module. See `../Documentation/` for the full spec and the
`GMEPay+_Task_Backlog.xlsx` ticket backlog this code is built from.

## Layout
```
libs/
  lib-money     exact money (BigDecimal) + ISO-4217 currency scale
  lib-errors    canonical ErrorCode + ApiError envelope
  lib-events    DomainEvent + EventPublisher (Outbox now, Kafka at integration)
  lib-rate      the 3-currency USD-pivot rate engine (RATE-04) + tests
services/
  rate-fx       Rate & FX Engine service (REST: POST /v1/rates)
```

## Build & test
```bash
./gradlew build         # compiles all modules + runs unit tests
./gradlew :services:rate-fx:bootRun   # run the rate-fx service
```
Requires JDK 21. (Docker-based integration tests are added later and need Docker.)

## Status
Phase F0 (foundation) complete and green. See `PROGRESS.md` for the build manifest.

## Frontend
Two Next.js 14 + MUI 6 + Redux Toolkit + RHF/Yup + Lottie front-ends live under
`apps/` — `admin-ui` for GME Ops/Admin and `partner-portal-ui` for sending
partners. Both talk only to the Ops/Partner BFF. See
[`apps/README.md`](apps/README.md) for setup and dev quickstart, and
[`docs/UI_DEVELOPMENT.md`](docs/UI_DEVELOPMENT.md) for the design-system
conventions (theme, money/rounding helpers, error/empty/loading patterns).
