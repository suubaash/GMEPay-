# kyb-adapter — service backlog bundle

Vendor-agnostic Know-Your-Business (KYB) integration service. Owns the `KybProvider` port (per ADR-009) and the production adapter against Octa Solution (per ADR-014). Module: `services/kyb-adapter`. Owner: **Backend**. Lands in: **R1/R2** (Slice 3 of the WS 21 Partner Setup re-baseline).

## Service contract (MSA: own DB-less, API-only)

- **Datastore:** none owned; persistence of screening outcomes is config-registry's `partner_kyb` table. kyb-adapter is a stateless integration layer.
- **APIs / events I EXPOSE:** `POST /v1/kyb/screen`, `GET /v1/kyb/result/{vendorRef}`; secured by Keycloak (role OPS_ADMIN).
- **APIs / events I CONSUME:** none directly; called synchronously by ops-partner-bff during the wizard step-3 patch.
- **Events I PUBLISH:** `gmepay.kyb.screening` (decision, hits, vendorRef) — actually produced by config-registry after `partner_kyb` upsert; kyb-adapter only returns the result synchronously.
- **Dependencies:**
  - `lib-kyb` — `KybProvider` port + DTOs (KybRequest/KybResult/ScreeningHit).
  - `lib-events-kafka` — for the screening event topic admin (kyb-adapter does not publish itself; downstream wiring covered by `config-registry`).
  - `lib-vault` — to fetch vendor API credentials from Vault KV at `secret/kyb/octa`.
  - HashiCorp Vault (R3 deployment) — vendor API secret storage.
- **Integration rule:** never read another service's database; never embed vendor SDKs anywhere except inside this module. Stub adapter (`StubKybAdapter`) ships alongside `OctaKybAdapter` so dev + CI work without sandbox creds.

> Self-contained backlog. Build it as its own Gradle module against `lib-kyb`, `lib-events-kafka`, and `lib-vault`. Each ticket has a deliverable + acceptance checks.

<!-- ws-21-partner-setup-rebaseline -->

## Partner Setup re-baseline tickets (WS 21)

These tickets create the service and its vendor integrations. They are the entire backlog for kyb-adapter at R1/R2. Ticket prefix `21.3-Pxx` matches Slice 3 of `docs/PARTNER_SETUP_PLAN.md`.

### 21.3-P05 — New service: services/kyb-adapter scaffold (Spring Boot module)
*Slice:* **3** · *Est:* 75 min · *Role:* Backend · *Owner:* kyb-adapter · *ADR refs:* ADR-009

**Context.** Per ADR-009 the vendor integration is its own Spring Boot service so we can isolate vendor SDK transitive deps and swap vendors without touching config-registry. Scaffold the module, wire lib-kyb + lib-events-kafka + Vault client.

**Steps.** Create `services/kyb-adapter/build.gradle` with deps: lib-kyb, lib-events-kafka, lib-vault, spring-boot-starter-web, spring-cloud-vault-config, spring-boot-starter-data-redis (cache for screening results), spring-boot-starter-oauth2-resource-server; main class `KybAdapterApplication`; application.yml with port 8087; expose POST /v1/kyb/screen and GET /v1/kyb/result/{vendorRef}; secure with the same Keycloak realm.

**Deliverable.** `services/kyb-adapter/build.gradle; services/kyb-adapter/src/main/java/com/gme/pay/kyb/adapter/KybAdapterApplication.java; services/kyb-adapter/src/main/resources/application.yml`

**Acceptance.**
- ./gradlew :services:kyb-adapter:bootRun starts on :8087
- /actuator/health returns 200
- POST /v1/kyb/screen requires JWT with role OPS_ADMIN
- Module appears in `./gradlew projects`

### 21.3-P06 — StubKybAdapter — deterministic test double for Slice 3 dev
*Slice:* **3** · *Est:* 60 min · *Role:* Backend · *Owner:* kyb-adapter · *ADR refs:* ADR-009

**Context.** Until Octa sandbox creds land (calendar blocker), StubKybAdapter satisfies Slice 3 development and the exit gate. Deterministic: same input → same output so tests are repeatable. Echoes back CLEAR unless the partner's legal_name contains the trigger word 'SANCTIONED_TEST'.

**Steps.** Create `services/kyb-adapter/src/main/java/com/gme/pay/kyb/adapter/stub/StubKybAdapter.java` implementing KybProvider; @ConditionalOnProperty(name="gme.kyb.adapter", havingValue="stub", matchIfMissing=true); deterministic seed = hash(partner_code); generates 0 hits if legal_name lacks trigger word, 2 hits (OFAC + PEP) if it contains it; latency simulated 200-500ms via Thread.sleep for realism.

**Deliverable.** `services/kyb-adapter/src/main/java/com/gme/pay/kyb/adapter/stub/StubKybAdapter.java`

**Acceptance.**
- KybResult for partner_code TEST_CLEAN returns decision=CLEAR with empty hits
- Partner with legal_name containing 'SANCTIONED_TEST' returns decision=HIT with 2 ScreeningHit rows
- Same input twice returns the same vendor_reference (deterministic)
- Adapter swaps cleanly to OctaKybAdapter (21.3-P07) by flipping gme.kyb.adapter property

### 21.3-P07 — OctaKybAdapter — Octa Solution vendor implementation (gated by sandbox creds)
*Slice:* **3** · *Est:* 180 min · *Role:* Backend · *Owner:* kyb-adapter · *ADR refs:* ADR-014

**Context.** Per ADR-014 the chosen vendor is Octa Solution. This adapter calls the Octa REST API. CALENDAR-BLOCKED on sandbox creds delivery; until creds arrive the bean is `@ConditionalOnProperty(name="gme.kyb.adapter", havingValue="octa")` so the Stub remains default.

**Steps.** Create `services/kyb-adapter/src/main/java/com/gme/pay/kyb/adapter/octa/OctaKybAdapter.java`; Octa client config from Vault KV (path `secret/kyb/octa`); endpoints per Octa API doc — `POST /v2/screen`, `GET /v2/result/{ref}`; map Octa response fields to KybResult; circuit breaker via Resilience4j (failureRate threshold 50%, slidingWindow 20 calls); retry on 5xx with exponential backoff capped at 8s.

**Deliverable.** `services/kyb-adapter/src/main/java/com/gme/pay/kyb/adapter/octa/OctaKybAdapter.java; services/kyb-adapter/src/main/java/com/gme/pay/kyb/adapter/octa/OctaApiClient.java`

**Acceptance.**
- WireMock integration test: POST /v2/screen returning Octa's documented hit structure maps cleanly to KybResult
- Circuit opens after 50% failure rate over 20 calls; subsequent calls fail fast with `KYB_CIRCUIT_OPEN`
- 5xx retried 3 times with backoff 1s/2s/4s then surfaces failure
- Octa creds NEVER logged; only the vendor_reference appears in logs

