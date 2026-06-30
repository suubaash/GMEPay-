> 작업: Wave3 smart-router wiring / 출처: agent

# Wave-3 smart-router — REST adapter for data-driven scheme resolution

## Build
`./gradlew :services:smart-router:test` → **BUILD SUCCESSFUL, 37 tests** (was 30; +7 new).

## What landed (services/smart-router/ only; libs + other services untouched)
- **`RestPartnerSchemeRegistry`** (new) implements the `PartnerSchemeRegistry`
  port over config-registry's live `partner_scheme` read surface, replacing the
  in-memory fixture in production. Mirrors `RestPartnerSchemeResolver`'s
  rest-client pattern (Spring 6 `RestClient`, `gmepay.config-registry.base-url`,
  two-ctor `@Autowired` trap).
  - **Gated** `@ConditionalOnProperty("gmepay.config-registry.enabled"=true)`;
    `InMemoryPartnerSchemeRegistry` stays the default/test fallback
    (`@Profile("!config-registry")`, unchanged).
  - Reads `GET /v1/partners` → filters routable partners operating in the
    country → fans out `GET /v1/admin/partners/{code}/schemes` → maps
    `PartnerSchemeView` → `PartnerSchemeRecord`.
  - Upstream failure → `SCHEME_UNAVAILABLE` (503, no silent fallback); per-partner
    404 → write-race, contributes nothing.
- **Branches over LIVE data**: mapping drops `enabled=false` + non-`ACTIVE`
  status rows, filters by view `countryCode`, derives CPM/MPM from
  `supportsCpm`/`supportsMpm` (fallback: `approvalMethodCpm/Mpm` presence),
  sorts by `priority`. `LocationSchemeResolver`'s NO_SCHEME_FOR_LOCATION /
  DIRECTION_NOT_ENABLED / PAYMENT_MODE_NOT_SUPPORTED branches run unchanged over
  the fetched rows.
- **Tests**: `RestPartnerSchemeRegistryTest` (MockRestServiceServer, registry NOT
  running) — view→record mapping, success resolution, each of the 3 data
  branches, approval-method fallback, 5xx→SCHEME_UNAVAILABLE.
- CHANGELOG updated.

## Contract note
The Wave-3 commit (a36997e) extended `PartnerSchemeView` with the 5
location-resolution fields but did **not** add a dedicated config-registry
location endpoint — the adapter consumes the existing per-partner
`/v1/admin/partners/{code}/schemes` (returns `List<PartnerSchemeView>`) + the
`/v1/partners` directory, the same surface `RestPartnerSchemeResolver` uses.

## Remaining (≤3)
1. A first-class config-registry `schemesByCountry` endpoint would replace the
   N+1 directory-then-fan-out scan (current adapter mirrors the existing resolver
   to stay within the frozen contract).
2. Wiring `gmepay.config-registry.enabled=true` + `config-registry` profile into
   the deploy env (no code change needed; defaults keep in-memory).
3. `priority` ties across partners fall back to encounter order — confirm
   config-registry guarantees a stable global priority once the real data lands.
