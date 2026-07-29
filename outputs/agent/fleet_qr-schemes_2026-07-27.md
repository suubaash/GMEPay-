> 작업: QR scheme fleet integration / 출처: agent

# QR scheme fleet integration — SENDMN + NINEPAY (Phase 5 infra items)

Date: 2026-07-27. Scope: fleet/infra files only (no Java, no e2e-tests). Both Phase 5 checkboxes marked [x] in `Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md`.

## Files changed

| File | Change |
|---|---|
| `run-fleet.ps1` | +4 fleet entries (2 adapters, 2 sims), payment-executor SENDMN base-url arg, JVM-count comments 22→~28 |
| `docker-compose.yml` | +2 corridor blocks (4 services), +2 postgres instances (postgres-sendmn 5445, postgres-ninepay 5447) + volumes, payment-executor SENDMN env, header port-band comment |
| `simulators/sim-sendmn/Dockerfile` | NEW — mirrors sim-nepal-qr/sim-ninepay (standalone `-p` gradle build, in-container 8080) |
| `.smoke/trace-console.js` | +4 tap EDGES (7109–7112) for the new edges; categorize() buckets scheme-adapter-*/sim-sendmn/sim-ninepay/IPN traffic into Txn Flow |
| `Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md` | Phase 5 infra checkboxes → [x] with notes |

## Port assignments

| Component | run-fleet (localhost) | compose host | compose internal |
|---|---|---|---|
| scheme-adapter-sendmn | 18096 | 8098 | 8080 |
| scheme-adapter-ninepay | 18097 | 8099 | 8080 |
| sim-sendmn | **9108** (yml default 9106 taken by sim-nepal-qr in fleet) | 9106 | 8080 |
| sim-ninepay | 9107 | 9107 | 8080 |
| postgres-sendmn / postgres-ninepay | — | 5445 / 5447 | 5432 |

## Env/arg wiring matrix

| Edge | Property | run-fleet value | compose value |
|---|---|---|---|
| payment-executor → SENDMN adapter | `gmepay.scheme-adapters.SENDMN.base-url` | `http://localhost:18096` | `GMEPAY_SCHEME_ADAPTERS_SENDMN_BASE_URL=http://scheme-adapter-sendmn:8080` |
| SENDMN adapter → sim-sendmn | `sendmn.base-url` | `http://localhost:9108` | `SENDMN_BASE_URL=http://sim-sendmn:8080` |
| NINEPAY adapter → sim-ninepay | `gmepay.scheme.ninepay.base-url` | `http://localhost:9107` | `GMEPAY_SCHEME_NINEPAY_BASE_URL=http://sim-ninepay:8080` |
| sim-ninepay → NINEPAY adapter (IPN) | `sim.ninepay.ipn-url` | `http://localhost:18097/scheme/ipn` | `SIM_NINEPAY_IPN_URL=http://scheme-adapter-ninepay:8080/scheme/ipn` |
| sim-sendmn → SENDMN adapter (manual FX push, off by default) | `gmepay.sim.sendmn.fx-push.url` | `http://localhost:18096/partner-hosted/fx-rate` | `GMEPAY_SIM_SENDMN_FXPUSH_URL=http://scheme-adapter-sendmn:8080/partner-hosted/fx-rate` |

Both adapters: compose gives owned PostgreSQL via `SPRING_DATASOURCE_*` (H2 fallback stays for bare runs); fleet keeps H2. All 4 in `full` profile, `*tcp-health` probe, mirroring the Nepal corridor exactly.

## Trace-console

- Both adapters include `libs:lib-errors` → the universal `/ingest` self-reporter activates via run-fleet's blanket `--gmepay.trace.enabled=true`; all four edges are visible live (hub→adapter with correct caller header; adapter→sim from the adapter's outbound interceptor; IPN from the adapter's inbound filter, caller shown as `external`).
- 4 tap EDGES (7109–7112) added as declared topology, same inert-until-routed status as the existing 8 (run-fleet wires services directly, not through taps, so `-NoTrace` runs don't break).

## Validation (static only, per rules)

- `node --check` trace-console.js: OK. PowerShell `Parser::ParseFile` run-fleet.ps1: 0 errors. PyYAML parse of compose: OK (44 services).
- Host-port collision scan: only pre-existing `8092` (scheme-adapter-nepal vs settlement-reconciliation, both `full`) — NOT introduced here.

## Limitations / follow-ups (Java-side, out of scope per rules)

1. **Sims don't self-report**: standalone builds, no lib-errors → sim-side traffic appears only via the adapters' reports (labels `localhost:9108/9107`, IPN caller `external`). Fix = add lib-errors to sims (needs build restructuring) or route via taps.
2. **`TraceNames.PORT_NAMES`** (lib-errors) lacks 18096/18097/9107/9108 → outbound callee labels are raw host:port.
3. **`/__data` DB view**: new adapters have no `DevDataController` → not added to console `DB_SERVICES`.
4. **Pre-existing compose bug**: host port 8092 double-assigned (scheme-adapter-nepal / settlement-reconciliation) — `full` profile can't publish both.
5. Compose entries not runtime-verified (docker lives in WSL2 `gmepay-docker`; not started per rules).
