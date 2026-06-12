# ops-partner-bff — service backlog bundle

Backend-for-frontend serving admin-ui and partner-portal-ui (19 endpoints, built ahead of WBS v2 — legitimized by WBS v3). Module `services/ops-partner-bff`. Speaks REST to 10 upstream services; stubs remain only under the `demo` profile.


<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 17.5-G02 — Flip BFF's 10 clients from Stub to REST
*Completion phase:* **R2** · *Est:* 240 min · *Role:* Backend · *Deps:* 17.5-G01

**Context.** BFF ships Stub*Client @Component beans; Rest* impls exist for some. Implement the missing Rest* clients and verify all 10 against live services.

**Steps.**
- Implement remaining Rest*Client beans (RestTemplate/RestClient)
- Profile 'live' activates REST, 'demo' keeps stubs
- Verify all 19 endpoints return live data

**Deliverable.** BFF live against real services

**Acceptance.**
- 19/19 endpoints 200 with live data on compose stack
- Stubs only under demo profile

### 18.1-G01 — Document BFF surface as INTER_SERVICE contract
*Completion phase:* **R2** · *Est:* 90 min · *Role:* Backend

**Context.** 19 endpoints exist but aren't in docs/INTER_SERVICE_CONTRACTS.md; UI agents had to read code. Freeze the BFF wire contract.

**Steps.**
- Add BFF section: 19 endpoints, DTO field tables
- Mark BigDecimal-as-string + ISO dates explicitly
- Link from UI bundles

**Deliverable.** Frozen BFF contract doc

**Acceptance.**
- Doc matches springdoc output; UI contract tests reference it

### 18.1-G02 — BFF auth pass-through + partner scoping
*Completion phase:* **R2** · *Est:* 140 min · *Role:* Backend · *Deps:* 18.4-G02

**Context.** Portal endpoints take partnerId from path with no authz: any caller can read any partner. Enforce JWT partner claim = path partnerId.

**Steps.**
- Security filter validating JWT (18.4)
- 403 on partner mismatch; admin role bypass
- Tests for scoping matrix

**Deliverable.** Partner isolation enforced

**Acceptance.**
- Cross-partner read returns 403 in tests

### 18.4-G02 — BFF validates JWT + role gates
*Completion phase:* **R3** · *Est:* 180 min · *Role:* Backend · *Deps:* 18.4-G01

**Context.** BFF AuthController currently fakes login. Delegate to auth-identity; spring-security resource server validates; method security on admin vs portal routes.

**Steps.**
- POST /v1/auth/login proxies to auth-identity
- Resource-server JWKS validation
- @PreAuthorize role checks per endpoint group

**Deliverable.** BFF secured by real JWT

**Acceptance.**
- Expired/forged token → 401; wrong role → 403; tests cover matrix

