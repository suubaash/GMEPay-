> 작업: T3-1 backup/DR capability / 출처: agent

# T3-1 — Backup / Restore / DR capability

Closes (partially) gap **T3-1** in `Documentation/GAP_REGISTER.md`, COO audit finding #2
(`outputs/agent/audit_coo-ops_2026-07-28.md` §2). Before this change there was no `pg_dump`, no WAL
archiving, no restore path, no DR document anywhere in the repo — all state sat in local Docker
volumes with nothing reading it out.

**No Java service, no `apps/**`, no `ops-partner-bff`, no `payment-executor`, no `prefunding` was
touched. `docker-compose.yml` was NOT modified** — a host-run script needs no sidecar. (`git status`
shows `docker-compose.yml` as modified; that is pre-existing uncommitted SENDMN/9Pay work from
another workstream, not this task.) Nothing was executed against a database, no container was
started.

---

## 1. Stateful inventory (read out of the repo, not guessed)

Sources: `docker-compose.yml` (`volumes:` + each `postgres-*` service's `POSTGRES_DB`/ports) and
`deploy/helm/gmepay/values.yaml` (`SPRING_DATASOURCE_URL` / `SPRING_DATA_MONGODB_URI`). The
machine-readable copy is `scripts/backup/inventory.env`; `scripts/backup/check-inventory.sh` fails
the build if it drifts from compose (currently **15/15 exact match**).

### PostgreSQL — 15 clusters, one app DB each (all `postgres:16-alpine`)

| # | Compose service | Database | User | Host port | Volume | Owning microservice |
|---|---|---|---|---|---|---|
| 1 | `postgres-config` | `configreg` | gmepay | 5433 | `pg-config` | config-registry |
| 2 | `postgres-txn` | `txndb` | gmepay | 5434 | `pg-txn` | transaction-mgmt |
| 3 | `postgres-prefunding` | `prefunding` | gmepay | 5435 | `pg-prefunding` | prefunding |
| 4 | `postgres-ledger` | `ledger` | gmepay | 5436 | `pg-ledger` | revenue-ledger |
| 5 | `postgres-settlement` | `settlement` | gmepay | 5437 | `pg-settlement` | settlement-reconciliation |
| 6 | `postgres-notify` | `notify` | gmepay | 5438 | `pg-notify` | notification-webhook |
| 7 | `postgres-authid` | `authid` | gmepay | 5439 | `pg-authid` | auth-identity (RBAC) |
| 8 | `postgres-scheme` | `zpadapter` | gmepay | 5440 | `pg-scheme` | scheme-adapter-zeropay |
| 9 | `postgres-executor` | `executor` | gmepay | 5441 | `pg-executor` | payment-executor |
| 10 | `postgres-qr` | `qrdb` | gmepay | 5442 | `pg-qr` | qr-service |
| 11 | `postgres-ratefx` | `ratefx` | gmepay | 5443 | `pg-ratefx` | rate-fx |
| 12 | `postgres-reporting` | `reporting` | gmepay | 5444 | `pg-reporting` | reporting-compliance |
| 13 | `postgres-sendmn` | `smnadapter` | gmepay | 5445 | `pg-sendmn` | scheme-adapter-sendmn |
| 14 | `postgres-keycloak` | `keycloak` | **keycloak** | 5446 | `pg-keycloak` | keycloak |
| 15 | `postgres-ninepay` | `npadapter` | gmepay | 5447 | `pg-ninepay` | scheme-adapter-ninepay |

Note the non-obvious ones the COO count of "15" hides: `postgres-keycloak` uses a **different DB
user** (`keycloak`, not `gmepay`), and the port band is not in service order (5445/5447 were appended
after 5446).

### Mongo / MinIO / Kafka

| Store | Service | Volume | Logical unit | Owner | Backed up? |
|---|---|---|---|---|---|
| MongoDB 7 | `mongo` :27017 | `mongo-data:/data/db` | DB `merchant` | merchant-qr-data | Yes — `mongodump --archive --gzip` |
| MinIO | `minio` :9000/9001 | `minio-data:/data` | Bucket **`gmepay-partner-vault`** (`MinioVaultClient.DEFAULT_BUCKET`, ADR-006, object-lock COMPLIANCE) | lib-vault | Yes — `mc mirror` to plain files |
| Kafka | `kafka` :29092 | **NONE** | topics `gmepay.<eventType>` (`payment.approved`, `payment.reversed`, `settlement.completed`, `ops.alert`, `ops.alert.ack`, `prefunding.alert`, `.DLT` variants, `gmepay.audit.*`); groups `notification-webhook`, `revenue-ledger`, `prefunding`, `ops-partner-bff` | lib-events-kafka | **No — see §5** |
| Zookeeper | `zookeeper` | **NONE** | ensemble metadata | — | No |
| Schema Registry | `schema-registry` :8081 | — (Kafka `_schemas`) | subjects | — | No (schemas live in `libs/lib-api-contracts`) |
| Redis | `redis` :6379 | **NONE** | cache | — | No, by design |

**Two findings worth escalating beyond the original gap text:**
1. **Kafka and Zookeeper have no named volume at all** — the audit said "local Docker volumes", but
   these two are worse than that: their logs and `__consumer_offsets` live in the container writable
   layer and are destroyed by `docker compose down`, not just by volume loss.
2. **`deploy/helm/gmepay/templates/` contains no StatefulSet and no PVC** — only
   `deployments.yaml`/`configmap.yaml`/`ingress.yaml`/`secret.yaml`. A Helm deployment assumes all
   four datastores are external, so it has *no* backup story of its own; this capability covers the
   compose stack only.

---

## 2. Files created

| Path | Purpose |
|---|---|
| `scripts/backup/inventory.env` | SSOT inventory (15 PG rows + Mongo + MinIO + explicit not-covered list). Sourced by every script — the list exists once |
| `scripts/backup/gmepay-backup.sh` | Full backup: per-DB `pg_dump -Fc -Z6 --no-owner --no-acl`, per-cluster `pg_dumpall --globals-only`, `mongodump`, `mc mirror`; `MANIFEST.txt` with sha256+size; 7-daily + 4-weekly retention |
| `scripts/backup/gmepay-restore.sh` | `--list`, destructive restore (`--db <svc>|all|mongo|minio`), and non-destructive `--verify-only` scratch-DB restore |
| `scripts/backup/check-inventory.sh` | Static awk diff of `docker-compose.yml` ↔ `inventory.env`; connects to nothing, CI-safe |
| `scripts/backup/gmepay-backup.ps1` | Thin Windows wrapper → `wsl -d gmepay-docker -- bash …`; only translates args + propagates exit code (zero duplicated logic) |
| `Documentation/RUNBOOK_BACKUP_DR.md` | The runbook (inventory, run, verify, restore, RPO/RTO, cron/systemd/Task-Scheduler, not-covered, escalation) |

`Documentation/GAP_REGISTER.md` — T3-1 marked `[~]` with the residual list.

**No existing `scripts/` dir existed.** Convention followed: bash internals like `.smoke/*.sh` (the
containers run in WSL2 per those scripts, project prefix `code-`), PowerShell at the operator surface
like `run-fleet.ps1` / `demo.ps1`.

### Design decisions
- **`docker exec` rather than host ports** — `pg_dump` always version-matches the server and no psql
  client is needed on the host. Host ports are recorded for ad-hoc use.
- **Per-DB `pg_dump -Fc`, not `pg_dumpall`** — each cluster holds exactly one app DB, so
  `pg_dumpall`'s only unique contribution is globals (captured separately with `--globals-only`).
  Custom format buys selective single-service restore, `pg_restore -j`, `--list` inspection, and
  compression. WAL/pgBackRest is the documented next step (needs `archive_command` in 15 clusters).
- **No silent partial backups** — artifacts stage into `<ts>.partial/`, promoted to `<ts>/` + a
  `SUCCESS` marker only if every component passed. Any failure ⇒ named component in the error, exit 1,
  `.partial` kept for triage, and **retention skipped entirely** so a broken run can never age out the
  last good set. Each dump is additionally re-read with `pg_restore --list` to catch truncation.
- **Three restore guards** — explicit `--set` (no "latest" shortcut), explicit `--db`,
  `--i-understand-this-destroys-data`; `--db all` also demands a typed `RESTORE-ALL` at a TTY, so cron
  can never restore everything. Sets lacking `SUCCESS` are refused.
- **No cloud/S3 invented** — default target is a local path; off-host step is a documented `rsync` to
  a second disk/NFS mount.

---

## 3. How to verify (operator)

```bash
bash scripts/backup/check-inventory.sh                      # 15/15, no drift
bash scripts/backup/gmepay-backup.sh --dry-run              # lists all 15 + mongo + minio
bash scripts/backup/gmepay-backup.sh --target /var/backups/gmepay
bash scripts/backup/gmepay-restore.sh --list
bash scripts/backup/gmepay-restore.sh --set <ts> --db postgres-txn --verify-only
```

`--verify-only` restores into `<db>_verify_<hhmmss>` in the same cluster, prints table + row counts,
drops the scratch DB, and exits non-zero if 0 tables came back. The live DB is never touched. Runbook
§3 has the weekly drill (verify `txndb` + `authid`, compare live row count, `sha256sum -c` the
manifest, confirm the off-host copy).

## 4. Static validation performed

| Check | Result |
|---|---|
| `bash -n` on all 3 `.sh` | PASS |
| PowerShell `Parser::ParseFile` on `.ps1` | PASS (0 errors) |
| `check-inventory.sh` executed | **PASS — 15 compose services == 15 inventory rows, exact** |
| `gmepay-backup.sh --dry-run` executed | Enumerated all 15 DBs + `merchant` + `gmepay-partner-vault` |
| Restore guards exercised | Missing `--set`, missing confirm flag, `--db all` without TTY, no-`SUCCESS` set, unknown service → all exit 1 with the right message |
| Missing-`docker` preflight | Exits 3 before doing anything |
| `docker-compose.yml` | Not modified |

Nothing was run against a database; no container or compose command was started.

---

## 5. What remains uncovered (why T3-1 is `[~]`, not `[x]`)

1. **Nothing is scheduled yet.** cron / systemd-timer / Task-Scheduler entries are written out
   verbatim in runbook §4 but an operator must install one. Until then the capability exists and never
   runs.
2. **No off-host copy installed.** The `rsync` line is documented; a backup on the same disk as the
   data is not a backup.
3. **No full restore drill performed.** `--verify-only` proves artifacts are readable; it does not
   prove the fleet boots against them. RTO (~4 h fleet) is an *estimate*. This is the remaining
   acceptance item for COO finding #2 and needs a live stack — out of scope here by instruction.
4. **RPO stays 24 h.** Logical nightly dumps only; no WAL archiving, so up to a day of real
   transactions is unrecoverable. Unacceptable for live money — needs pgBackRest/WAL-G, or hourly
   dumps for `txndb`/`executor`/`prefunding`/`ledger` at minimum.
5. **Kafka replay position is unrecoverable** (no volume at all). The DB outbox tables are the durable
   record, so facts survive, but post-restore consumers may re-deliver or skip. Closing it needs an
   additive compose change (a Kafka volume) — deliberately not made here.
6. **Cross-DB consistency.** The 15 dumps are sequential and not mutually consistent; a payment can be
   approved in `txndb` but unexecuted in `executor`. Mitigation is reconcile-after-restore (§5.5), not
   a coordinated snapshot.
7. **Secrets are excluded on purpose** — `.env`, Keycloak client secrets, `docker/certs`, MinIO creds,
   webhook signing secrets. A restore without them boots a fleet that cannot authenticate anywhere.
   They belong in a secret manager.
8. **MinIO object-lock** blocks overwriting retained objects on restore; the runbook says restore to a
   new bucket and repoint config rather than defeating the lock.
9. **Escalation contact is a placeholder** — there is no paging target wired anywhere in the platform
   (gap **T3-3**), so "page platform on-call" currently means an email address.
