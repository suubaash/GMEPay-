# docker-compose.yml — profiles, ports, and the CI smoke test

Reference for the local/dev stack defined in the repo-root `docker-compose.yml`
(tickets **17.1-G03** static reconciliation and **17.4-G05** Schema Registry).
The build machine that authored this file has **no Docker**; the file is
verified statically here and executed by the `compose-smoke` CI job on
`ubuntu-latest`.

## Profiles

Every service carries an explicit `profiles:` list, so you must pass a profile
flag — a bare `docker compose up` starts nothing.

| Profile | Contents |
|---|---|
| `core` | All infrastructure (9× PostgreSQL, MongoDB, Redis, ZooKeeper, Kafka, Schema Registry, Keycloak) + the money-path services: config-registry, rate-fx, prefunding, qr-service, transaction-mgmt, payment-executor, revenue-ledger, settlement-reconciliation, merchant-qr-data, scheme-adapter-zeropay, notification-webhook, ops-partner-bff, **auth-identity** (promoted `full` → `core` by gap T1-1: partner activation issues real credentials through it) |
| `full` | Everything in `core` plus: api-gateway, smart-router, reporting-compliance |

Infrastructure and money-path services are tagged with *both* profiles
(`["core", "full"]`); the extra services are tagged `["full"]` only, so a
single `--profile full` boots the entire platform.

```bash
# money path only (what CI smoke-tests)
docker compose --profile core up --build

# the whole platform
docker compose --profile full up --build

# tear down (drops the postgres/mongo volumes too)
docker compose --profile full down -v
```

## Internal-auth secret (`GMEPAY_INTERNAL_AUTH_SECRET`)

### What an operator must export before starting anything

```bash
export GMEPAY_INTERNAL_AUTH_SECRET="$(openssl rand -hex 32)"   # any shared/tunnelled host: REQUIRED
export GMEPAY_RBAC_SECRET="$(openssl rand -hex 32)"            # same discipline, gateway RBAC stamp
export KC_PUBLIC_URL=https://auth.example.com                  # only if Keycloak is not on localhost
```

For the host fleet instead of compose (`.\run-fleet.ps1`), the same variable plus the OIDC issuer:

```powershell
$env:GMEPAY_INTERNAL_AUTH_SECRET = '<random 32+ bytes>'
$env:OIDC_ISSUER_URI             = 'http://localhost:8097/realms/gmepay'   # default, set explicitly to be sure
```

Both compose and `run-fleet.ps1` fall back to the clearly-non-production literal
`dev-internal-svc-secret-not-for-prod` so a bare local boot still works. In compose that fallback
lives in **exactly one place** — the top-level `x-internal-auth-secret` anchor — and every service
block references it, so removing the checked-in default (gap-register item **T0-6**) is a one-line
change rather than an eleven-site sweep. **Never** ship that literal to a shared, tunnelled or
production environment. Helm never carries a working default at all: the value comes from
`secrets.data.GMEPAY_INTERNAL_AUTH_SECRET`, a `CHANGE_ME_…` / `REPLACE_FROM_SECRETS_MANAGER` /
`REPLACE_FROM_KEY_VAULT` / `REPLACE_WITH_INTERNAL_SECRET` placeholder per overlay.

### Why it is not optional

The `X-Gme-Internal` gate (`com.gme.pay.internalauth`, issue #90) is **fail-closed** since gaps
T0-2 / T0-5. Four services **refuse to boot** without a secret, and every caller that omits it is
answered **401** — never allowed through. So the same value must be present on *both* sides of every
gated edge. One secret, eleven services:

| Service | Why it needs the secret | Missing ⇒ |
|---|---|---|
| `auth-identity` | gates `/internal/auth/**` (JWT minting, API-key issuance), `/v1/rbac/**`, `/v1/approvals/**` | **refuses to start** |
| `prefunding` | gates all 18 money-moving / float-reading routes (`/internal/**`, `/v1/prefunding/**`) | **refuses to start** |
| `scheme-adapter-zeropay` | gates `/internal/scheme/zeropay/**` (real KFTC authorize/commit) + `registration-status` | **refuses to start** |
| `rate-fx` | gates `POST /v1/rates/snapshots` (treasury-rate override that re-prices every later quote) | **refuses to start** |
| `payment-executor` | caller → prefunding debit + ZeroPay authorize/commit; also gates its own `GET /v1/balance` | boots, but **every payment declines** |
| `config-registry` | caller → auth-identity key issuance + prefunding credit-limit push | activation 502s / credit-limit push 401s |
| `qr-service` | caller → prefunding CPM `reserve`/`release` | **CPM issuance declines** |
| `ops-partner-bff` | caller → auth-identity RBAC/approvals/sandbox-keys + prefunding balance/alerts | RBAC + balance panels 401 |
| `settlement-reconciliation` | caller → the ZeroPay registration-status prerequisite | fails CLOSED ⇒ **settlement generation blocked** |
| `api-gateway` | caller → auth-identity `/v1/rbac/resolve` | RBAC claim resolution fails |
| `transaction-mgmt` | gates `/actuator/metrics` + `/v3/api-docs`; **required** if `GMEPAY_DEVTOOLS_ENABLED=true` | introspection stays anonymous |

### Where it is wired

| Surface | Mechanism |
|---|---|
| `docker-compose.yml` | `GMEPAY_INTERNAL_AUTH_SECRET: *internal-auth-secret` in each of the 11 service blocks (single top-level anchor) |
| `deploy/helm/gmepay/values.yaml` | `envSecretKeys: [… GMEPAY_INTERNAL_AUTH_SECRET]` per service, sourced from the chart's credentials `Secret` |
| `values-onprem/aws/azure.yaml` | inherited — the overlays override only `env` (datasource URLs), never `envSecretKeys` |
| `run-fleet.ps1` | one `$env:GMEPAY_INTERNAL_AUTH_SECRET` assignment; `Start-Process` children inherit it |
| `e2e-tests` | `SchemeFleet.INTERNAL_AUTH_ENV` injected into every launched JVM; the HTTP helpers add the header |

Do **not** set `GMEPAY_DEVTOOLS_ENABLED` or `GMEPAY_SANDBOX_E2E_ENABLED` anywhere shared: they
expose table dumps / a real "spend money" runner. `GMEPAY_INTERNAL_AUTH_ENABLED` is **no longer read
by any service** — the gate is pinned on and cannot be switched off from config.

Verify the whole matrix statically, with no Docker and no servers:

```bash
python scripts/check_internal_auth_wiring.py     # derives the requirement from the code, then
                                                 # asserts compose + all 4 Helm values + run-fleet
node docker/keycloak/check-topology.mjs          # OIDC realm/client/issuer/port agreement
```

## Port map

All Spring Boot containers listen on **8080 internally** (`SERVER_PORT=8080`
env beats any `server.port` in a module's `application.yml`/`.properties`
because OS environment variables rank above config files in Spring's property
precedence). Host ports fan out as follows:

| Host port | Service | Profile | Notes |
|---|---|---|---|
| 8080 | api-gateway | full | actuator `/actuator/health` exposed |
| 8081 | **schema-registry** | core+full | canonical Confluent SR port (ADR-001) |
| 8082 | rate-fx | core+full | |
| 8083 | prefunding | core+full | |
| 8084 | smart-router | full | |
| 8085 | qr-service | core+full | |
| 8086 | auth-identity | full | |
| 8087 | transaction-mgmt | core+full | |
| 8088 | payment-executor | core+full | |
| 8089 | merchant-qr-data | core+full | boots on in-memory repo (Mongo autoconfig excluded in module) |
| 8090 | scheme-adapter-zeropay | core+full | |
| 8091 | notification-webhook | core+full | |
| 8092 | settlement-reconciliation | core+full | |
| 8093 | revenue-ledger | core+full | |
| 8094 | reporting-compliance | full | |
| 8095 | ops-partner-bff | core+full | |
| 8096 | **config-registry** | core+full | **moved from 8081** to free the SR port |
| 8097 | **keycloak** | core+full | human IdP (ADR-011); see below |
| 5433–5440 | postgres-{config,txn,prefunding,ledger,settlement,notify,authid,scheme} | core+full | one PostgreSQL per stateful service |
| 5446 | postgres-keycloak | core+full | Keycloak's own datastore (separate from authid) |
| 6379 | redis | core+full | used by api-gateway (replay protection + health) |
| 27017 | mongo | core+full | |
| 29092 | kafka (EXTERNAL listener) | core+full | host access; see below |

### Kafka listeners

| Listener | Address | Who uses it |
|---|---|---|
| `PLAINTEXT` (internal, inter-broker) | `kafka:9092` | containers on the `gmepay` network — `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` |
| `EXTERNAL` | `localhost:29092` | host tooling (only 29092 is published) |

Both are advertised explicitly (`KAFKA_ADVERTISED_LISTENERS`); 9092 is *not*
published to the host on purpose — its advertised name `kafka` only resolves
inside the compose network.

### Schema Registry (ADR-001 / 17.4-G05)

`confluentinc/cp-schema-registry:7.6.1` on port 8081, `depends_on` Kafka
healthy, `SCHEMA_REGISTRY_SCHEMA_COMPATIBILITY_LEVEL=backward` pinned per
ADR-001. Schema registration / compatibility gating in CI is a separate
17.4-G05 work item; compose only stands the registry up.

## Healthchecks and start order

Every container has a healthcheck, and **all** `depends_on` entries use
`condition: service_healthy`, so `docker compose up` serialises the boot:
DBs → owning services → composite services (payment-executor, ops-partner-bff)
→ api-gateway; ZooKeeper → Kafka → Schema Registry / Kafka consumers.

| Target | Probe |
|---|---|
| postgres-* | `pg_isready -U gmepay -d <db>` |
| redis | `redis-cli ping` |
| mongo | `mongosh --eval 'db.adminCommand({ping: 1})'` |
| zookeeper | `zookeeper-shell localhost:2181 ls /` (ships in the cp image; no nc/curl needed) |
| kafka | `kafka-topics --bootstrap-server kafka:9092 --list` |
| schema-registry | `curl -fs http://localhost:8081/subjects` (bash `/dev/tcp` fallback) |
| api-gateway | `curl/wget http://127.0.0.1:8080/actuator/health` (bash `/dev/tcp` fallback) — the only module with `spring-boot-starter-actuator` on its classpath |
| every other Spring service | TCP probe `bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080'` — **no actuator on the classpath**, and the eclipse-temurin JRE image is not guaranteed to carry curl; bash is |

Spring-service probes budget 36 × 5 s = **3 minutes** (17.1-G03 acceptance:
all healthchecks green within 3 min) with a 15–20 s `start_period`.

Notes baked into the file (do not "fix" without reading these):

- **api-gateway** enables the Redis health indicator in its `application.yml`;
  compose therefore sets `SPRING_DATA_REDIS_HOST=redis` — without it the
  indicator probes localhost and `/actuator/health` reports DOWN forever.
- **auth-identity** reads `gme.config-registry.base-url` (not `gmepay.*`), so
  its env var is `GME_CONFIG_REGISTRY_BASE_URL`.
- **scheme-adapter-zeropay** declares `management.server.port: 8091` in its
  module YAML, but without the actuator starter that key is inert; the TCP
  probe on 8080 is correct.
- **merchant-qr-data** excludes Mongo auto-configuration in its
  `application.properties` (unit tests run Mongo-free); in compose it boots on
  the in-memory repository fallback. `SPRING_DATA_MONGODB_URI` is pre-wired for
  the day the exclude is removed.
- `SPRING_KAFKA_BOOTSTRAP_SERVERS` and several `GMEPAY_*_BASE_URL` vars are
  *forward-compatible no-ops* today (no spring-kafka / no `@Value` reader in
  those modules yet) — they are kept so 17.4-G0x lands without compose changes.
- **scheme-adapter-zeropay** gained JPA/Flyway persistence mid-wave (17.2-G09);
  compose wires it to its own `postgres-scheme` instance (host port 5440, db
  `zpadapter`) via `SPRING_DATASOURCE_*` — without those env vars the module
  falls back to its H2 default.
- Each app JVM is capped via `JAVA_TOOL_OPTIONS=-Xmx320m` so the full stack
  (16 JVMs + Kafka + 8 Postgres + Mongo + Redis) fits a 16 GB CI runner.

## How CI smoke-tests this file

Job `compose-smoke` in `.github/workflows/ci.yml` (ubuntu-latest):

1. `docker compose config -q` — schema/interpolation validation.
2. `docker compose --profile core up -d --quiet-pull` — builds the missing
   `gmepay/*:dev` images and boots the core profile; the command itself blocks
   until each `service_healthy` dependency condition is met.
3. `sleep 90`, then `docker compose ps` — the job **fails if any container
   reports `unhealthy` or `exited`** (grep over the status column), dumping
   `docker compose logs --tail 100`.
4. `docker compose down -v` teardown (always).

The job is `continue-on-error: true` until its first observed green run, then
flips to required (17.1-G03 acceptance).

Local note: this repo's build machine has no Docker — do **not** try to run
`docker compose` or `gradlew integrationTest` locally; unit tests stay on H2
(PostgreSQL mode).

## Keycloak (ADR-011)

`quay.io/keycloak/keycloak:25.0` runs the **human** identity provider for
admin-ui and partner-portal-ui. Machine credentials (API keys, HMAC, mTLS)
stay with `auth-identity` — the two IdPs never share state. See ADR-011 for
the rationale.

| Property | Value |
|---|---|
| Image | `quay.io/keycloak/keycloak:25.0` (Quarkus distribution; do **not** revert to the EOL `jboss/keycloak` image) |
| Host port | **8097** (admin console: `http://localhost:8097`) |
| Internal port | 8080 |
| Start command | `start-dev --import-realm` (dev mode — no HTTPS required) |
| Datastore | `postgres-keycloak` (Postgres 16, host port **5446**, db/user/password = `keycloak`) |
| Master-realm admin | `admin / admin` (env vars `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`) |
| Realm seed | `docker/keycloak/realm-gmepay.json` mounted read-only as `/opt/keycloak/data/import/realm-gmepay.json` (the FILE, not the directory, so `docker/keycloak/README.md` can sit beside it) |
| Browser-facing base URL | `KC_HOSTNAME_URL` = `${KC_PUBLIC_URL:-http://localhost:8097}` — pins the `iss` claim; one knob also drives every resource server's `OIDC_ISSUER_URI` |
| Healthcheck | TCP probe on 8080 (the `/health` endpoint lives on the management port, which is off in dev mode) |

### Seeded realm `gmepay`

The realm JSON imports on first boot (subsequent boots are idempotent — Keycloak
skips re-import when the realm already exists). It seeds:

| Kind | Name | Purpose |
|---|---|---|
| Client | `admin-ui` | **public** OIDC, auth-code + PKCE S256, redirect `http://localhost:3000/auth/callback` — **no secret** |
| Client | `partner-portal-ui` | **public** OIDC, auth-code + PKCE S256, redirect `http://localhost:3001/auth/callback` — **no secret** |
| Mapper (both clients) | `permissions` | multivalued user attribute → `permissions` claim; `ops-partner-bff` authorizes from this (`TokenClaims`) |
| Mapper (both clients) | `partner_id` | user attribute → `partner_id` claim; scopes `/v1/portal/{partnerId}/**` |
| Realm role | `OPERATOR` | back-office user; gates `/v1/admin/**` at the BFF |
| Realm role | `PARTNER_USER` | partner-portal-ui human; per-partner scoping enforced at the BFF |
| User | `admin / demo` | OPERATOR, full hub `permissions` — replaces the deleted `password=demo` BFF login |
| User | `operator-readonly / demo` | OPERATOR with read-only permissions (admin writes 403) |
| User | `partner-demo / demo` | PARTNER_USER, `partner_id=GMEREMIT` |
| User | `partner-sendmn / demo` | PARTNER_USER, `partner_id=SENDMN` |

Both clients were confidential with committed dev secrets until gap **T1-2**: a
browser SPA cannot present a `client_secret`, so every token exchange returned
`invalid_client`. They are public + PKCE now — see `docker/keycloak/README.md` for the
canonical realm/client/issuer/port table per environment and
`node docker/keycloak/check-topology.mjs` to verify every file still agrees.

The richer `PARTNER_ADMIN` / `PARTNER_VIEWER` split mentioned in ADR-011
§Consequences lands in **Slice 8** alongside per-partner self-service users.
Staging and prod use a different realm export with **empty user lists**; real
users are provisioned via SCIM/LDAP federation.

### Port-band note

The 8080..8096 application band was already full when Keycloak was added, and
8090 (the Slice 1 brief's first choice) is owned by `scheme-adapter-zeropay`.
Keycloak therefore sits at host port **8097**, one step beyond the original
application band. The admin console URL and OIDC discovery URL follow:

```
admin console:   http://localhost:8097/
OIDC discovery:  http://localhost:8097/realms/gmepay/.well-known/openid-configuration
```

Inside the compose network the gateway/BFF reach Keycloak at
`http://keycloak:8080` (the internal port is unchanged at 8080).
