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
| `core` | All infrastructure (8× PostgreSQL, MongoDB, Redis, ZooKeeper, Kafka, Schema Registry) + the money-path services: config-registry, rate-fx, prefunding, qr-service, transaction-mgmt, payment-executor, revenue-ledger, settlement-reconciliation, merchant-qr-data, scheme-adapter-zeropay, notification-webhook, ops-partner-bff |
| `full` | Everything in `core` plus: api-gateway, auth-identity, smart-router, reporting-compliance |

Infrastructure and money-path services are tagged with *both* profiles
(`["core", "full"]`); the four extra services are tagged `["full"]` only, so a
single `--profile full` boots the entire platform.

```bash
# money path only (what CI smoke-tests)
docker compose --profile core up --build

# the whole platform
docker compose --profile full up --build

# tear down (drops the postgres/mongo volumes too)
docker compose --profile full down -v
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
| 5433–5440 | postgres-{config,txn,prefunding,ledger,settlement,notify,authid,scheme} | core+full | one PostgreSQL per stateful service |
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
