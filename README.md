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

**CI:** [`docs/CI.md`](docs/CI.md) is the single source of truth for which pipeline
jobs *block* a merge (build, integration, e2e, ui-build, secret scanning,
dependency scanning) and which only *report* — plus the security gates that still
do **not** exist (SAST, image scanning, SBOM, digest pinning). Read it before
treating a green pipeline as coverage.

## Status — read `Documentation/GAP_REGISTER.md` first
`Documentation/GAP_REGISTER.md` is the **single source of truth** for what is built, what is
built-but-gated, and what does not exist. Nothing else in this repo (including this file, the
WBS status audit and the flywheel docs) supersedes it.

The headline, so that no reader has to infer it:

- The money mechanics (QR decode → route → price → limit → fund → submit → record → book) are
  **built and covered by tests against simulators**.
- **Nothing has ever run against a live fleet.** One Windows laptop under Docker Compose; the
  Helm chart has never been applied.
- **No regulatory filing has ever been made**, on any lane — no submission channel is configured.
- **No settlement file has ever been transmitted** to a scheme.
- **No partner has ever verified a webhook signature.**
- **No transaction has ever been screened** for sanctions/PEP, and the payment path carries no
  party identity to screen.
- **No restore drill and no load test have ever been run**, and no service-level objective is
  declared.

The exec-facing summary of all of the above — generated from the register, so it cannot drift —
is `outputs/feature_spec_artifact.html` / `outputs/GMEPay+_Feature_Specification.docx`
(regenerate with `node outputs/docgen/build.js`; never hand-edit the rendered files).

Historical note: this section used to read "Phase F0 (foundation) complete and green", which was
true in early 2026 and had not been touched since. The `Layout` block above is likewise
illustrative rather than complete — there are 21 services and 2 front-ends today.

## Frontend
Two Next.js 14 + MUI 6 + Redux Toolkit + RHF/Yup + Lottie front-ends live under
`apps/` — `admin-ui` for GME Ops/Admin and `partner-portal-ui` for sending
partners. Both talk only to the Ops/Partner BFF. See
[`apps/README.md`](apps/README.md) for setup and dev quickstart, and
[`docs/UI_DEVELOPMENT.md`](docs/UI_DEVELOPMENT.md) for the design-system
conventions (theme, money/rounding helpers, error/empty/loading patterns).
