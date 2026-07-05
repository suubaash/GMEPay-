# QR Hub Flywheel — Fulfillment Tracker

Working checklist for `docs/QR_HUB_GROWTH_FLYWHEEL.md`. Each item is either
**agent-buildable** (code in this repo) or **externally gated** (GME team,
partners, regulators — tracked here, fulfilled elsewhere).
**Status legend:** ✅ done · 🟡 in progress · ⬜ not started · 🔒 externally gated

## Agent-buildable items

| # | Item | Flywheel ref | Status | Notes |
|---|---|---|---|---|
| 1 | Loop-KPI dashboard: BFF `GET /v1/admin/flywheel` + admin-ui Flywheel page (7 metrics, §5) | Turn 0 | ✅ | `FlywheelController` + `apps/admin-ui/src/app/flywheel/`; ops-entered metrics via `flywheel.*` platform settings |
| 2 | Seed the three `flywheel.*` platform-settings keys with descriptions so ops can fill them | Turn 0 | ⬜ | config-registry settings store |
| 3 | Partner self-service loop: time-to-first-transaction measured per partner and surfaced in partner detail | Loop B | ⬜ | activation join exists in Delivery overview; per-partner drill-down pending |
| 4 | Scheme Adapter SDK: extract shared adapter contract + certification harness from `scheme-adapter-zeropay`/`-nepal` | Loop A / Turn 2 | ⬜ | start after the second adapter passes its scheme test suite |
| 5 | Simulator-backed partner sandbox E2E script (`sign up → KYB → keys → E2E → prefund`) | Loop B | ⬜ | builds on `gmepay-test-platform` + `simulators/` |
| 6 | Multilateral netting design note + settlement netting calc across opposing corridors | Loop C / Turn 2 | ⬜ | `settlement-reconciliation`; needs two-sided corridor flows first |
| 7 | Remittance→QR conversion metric feed (needs payer-level ids on transactions) | Loop D | ⬜ | transaction-mgmt schema addition |
| 8 | R0–R3 execution (money path real, Docker CI, auth) — prerequisite for the loop to spin | Turn 0 | 🟡 | tracked in `docs/COMPLETION_PLAN_V3.md`, not duplicated here |

## Externally gated items (🔒 — not fulfillable by code)

| Item | Flywheel ref | Owner |
|---|---|---|
| ZeroPay/KFTC certification; Nepal scheme commercial agreement | Turn 0–2 | GME + KFTC/scheme |
| Overseas sending-partner signings (Oct–Dec 2026) | Turn 1 | GME bizdev |
| Acceptance-market licenses in 2–3 diaspora corridors | Turn 2 | GME legal/compliance |
| Remittance+QR product bundling decision | Loop D | GME Product |
| Merchant campaign / value-added data services go-ahead | Loop E / Turn 3 | GME Product (post-volume) |

Keep statuses current as items land; the flywheel doc itself stays strategy-only.
