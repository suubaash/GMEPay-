# RUNBOOK — Backup, Restore & Disaster Recovery

**Scope:** the whole persistent surface of GMEPay+ — 15 PostgreSQL databases, MongoDB, MinIO.
**Audience:** whoever is on call. No prior knowledge of the fleet's internals assumed.
**Status:** closes gap **T3-1** (`Documentation/GAP_REGISTER.md`) / COO audit finding #2.
**Owner:** platform on-call (see §8). **Last reviewed:** 2026-07-28.

> Everything below runs against the **local Docker stack** (`docker-compose.yml`), which is where
> all state currently lives. There is no managed database and no cloud snapshot: if the Docker
> volumes are lost and no artifact from §2 exists, the data is gone. Read §7 before assuming
> anything is covered.

---

## 1. The stateful surface (what must be backed up)

Derived from `docker-compose.yml` and `deploy/helm/gmepay/values.yaml`. The machine-readable copy is
`scripts/backup/inventory.env` — that file, not this table, is what the scripts iterate.
`scripts/backup/check-inventory.sh` fails if the two drift.

### 1.1 PostgreSQL — 15 clusters, one application database each

| # | Compose service | Container (`code-…-1`) | Database | User | Host port | Owning microservice | What is lost if it goes |
|---|---|---|---|---|---|---|---|
| 1 | `postgres-config` | `code-postgres-config-1` | `configreg` | `gmepay` | 5433 | config-registry | Partner/scheme/corridor config, commission splits, operating hours — the fleet cannot route |
| 2 | `postgres-txn` | `code-postgres-txn-1` | `txndb` | `gmepay` | 5434 | transaction-mgmt | **The transaction ledger of record** + event outbox. Highest-value DB in the fleet |
| 3 | `postgres-prefunding` | `code-postgres-prefunding-1` | `prefunding` | `gmepay` | 5435 | prefunding | Float balances, top-ups, holds — money positions |
| 4 | `postgres-ledger` | `code-postgres-ledger-1` | `ledger` | `gmepay` | 5436 | revenue-ledger | Revenue postings, GL vouchers |
| 5 | `postgres-settlement` | `code-postgres-settlement-1` | `settlement` | `gmepay` | 5437 | settlement-reconciliation | Settlement batches, recon results, tie-outs |
| 6 | `postgres-notify` | `code-postgres-notify-1` | `notify` | `gmepay` | 5438 | notification-webhook | Webhook subscriptions + delivery attempts/secrets |
| 7 | `postgres-authid` | `code-postgres-authid-1` | `authid` | `gmepay` | 5439 | auth-identity | **RBAC** (roles, permissions, constraints, approval workflows), API clients |
| 8 | `postgres-scheme` | `code-postgres-scheme-1` | `zpadapter` | `gmepay` | 5440 | scheme-adapter-zeropay | ZeroPay adapter state, scheme txn refs |
| 9 | `postgres-executor` | `code-postgres-executor-1` | `executor` | `gmepay` | 5441 | payment-executor | Execution attempts, idempotency keys — **loss can cause double-execution** |
| 10 | `postgres-qr` | `code-postgres-qr-1` | `qrdb` | `gmepay` | 5442 | qr-service | Issued QR codes / tokens |
| 11 | `postgres-ratefx` | `code-postgres-ratefx-1` | `ratefx` | `gmepay` | 5443 | rate-fx | Rate snapshots, margins, quote history |
| 12 | `postgres-reporting` | `code-postgres-reporting-1` | `reporting` | `gmepay` | 5444 | reporting-compliance | KOFIU/BOK/Hometax filing state — **regulatory retention** |
| 13 | `postgres-sendmn` | `code-postgres-sendmn-1` | `smnadapter` | `gmepay` | 5445 | scheme-adapter-sendmn | SendMN (Mongolia) adapter state |
| 14 | `postgres-keycloak` | `code-postgres-keycloak-1` | `keycloak` | `keycloak` | 5446 | keycloak | Realm `gmepay`, human users/sessions. Partly re-seedable from `docker/keycloak/realm-gmepay.json` |
| 15 | `postgres-ninepay` | `code-postgres-ninepay-1` | `npadapter` | `gmepay` | 5447 | scheme-adapter-ninepay | 9Pay (VND payout) adapter state |

All 15 run `postgres:16-alpine`, each on its own named volume (`pg-config` … `pg-keycloak`).
Note ports 5433–5447 are contiguous **except** that 5446 is Keycloak's and 5445/5447 were appended
later — do not assume port order matches the table order.

### 1.2 MongoDB

| Compose service | Container | Volume | Database | Owning microservice | Contents |
|---|---|---|---|---|---|
| `mongo` (`mongo:7`, 27017) | `code-mongo-1` | `mongo-data:/data/db` | `merchant` | merchant-qr-data | Merchant master records, QR payload data |

**Caveat:** merchant-qr-data defaults to an **in-memory** Mongo unless `SPRING_DATA_MONGODB_URI` is
set (it is set in both compose and Helm). If a deployment forgets it, that data was never persisted
at all and there is nothing to back up — check before assuming coverage.

### 1.3 MinIO (object vault)

| Compose service | Container | Volume | Bucket | Owner | Contents |
|---|---|---|---|---|---|
| `minio` (9000/9001) | `code-minio-1` | `minio-data:/data` | `gmepay-partner-vault` | lib-vault (ADR-006) | Partner KYB documents, uploaded evidence |

Bucket name is `MinioVaultClient.DEFAULT_BUCKET`. `VaultBucketInitializer` creates it with
**object-lock COMPLIANCE retention**, which is why the backup mirrors objects **out** to plain files
rather than snapshotting the volume — see §5.4 for what that means on restore.

### 1.4 Kafka / Zookeeper — stateful but **NOT backed up** (deliberate)

| Compose service | Volume | Reality |
|---|---|---|
| `kafka` (`cp-kafka:7.5.0`, 29092) | **none** | Topic logs and `__consumer_offsets` live in the container's writable layer. `docker compose down` destroys them. |
| `zookeeper` (`cp-zookeeper:7.5.0`) | **none** | Same. |
| `schema-registry` (8081) | — | State is the Kafka `_schemas` topic, so same exposure. |

Topics are auto-created (`KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"`), named `gmepay.<eventType>`:
`gmepay.payment.approved`, `gmepay.payment.reversed`, `gmepay.settlement.completed`,
`gmepay.ops.alert`, `gmepay.ops.alert.ack`, `gmepay.prefunding.alert`, plus `.DLT` variants and
`gmepay.audit.<aggregateType>`. Consumer groups: `notification-webhook`, `revenue-ledger`,
`prefunding`, `ops-partner-bff`.

Because the events are published from **DB outbox tables**, the durable record is in `txndb` /
`settlement` / etc. — Kafka is a transport, and losing it costs *replay position*, not the facts.
Consequence: after any restore, consumers may re-deliver or skip events. See §7.

`redis` (6379) has no volume either — cache only, no backup needed.

### 1.5 Kubernetes note

`deploy/helm/gmepay/templates/` contains **only** `deployments.yaml`, `configmap.yaml`,
`ingress.yaml`, `secret.yaml` — there is **no StatefulSet or PVC for any datastore**. The chart
assumes Postgres/Mongo/MinIO/Kafka are external services reached by DNS name. So a Helm deploy has
*no* backup story of its own; whoever provides those endpoints owns their backups, and this runbook
covers the compose stack only.

---

## 2. How to run a backup

```bash
# On the Docker host (WSL2 distro 'gmepay-docker'):
cd /mnt/d/GMEPay+/code
bash scripts/backup/gmepay-backup.sh --target /var/backups/gmepay

# See exactly what it would do, touching nothing:
bash scripts/backup/gmepay-backup.sh --dry-run
```

From Windows (thin wrapper, all logic stays in the bash script):

```powershell
cd D:\GMEPay+\code\scripts\backup
.\gmepay-backup.ps1                                     # default target
.\gmepay-backup.ps1 -Target /mnt/d/gmepay-backups       # land on the D: drive
.\gmepay-backup.ps1 -Action list
.\gmepay-backup.ps1 -Action check-inventory
```

**What it produces** — `<target>/<YYYYMMDDThhmmssZ>/`:

```
postgres/postgres-txn__txndb.dump      # pg_dump -Fc, one per DB (15 files)
globals/postgres-txn__globals.sql      # pg_dumpall --globals-only, per cluster
mongo/merchant.archive.gz              # mongodump --archive --gzip
minio/gmepay-partner-vault/…           # mc mirror, plain objects
MANIFEST.txt                           # sha256 + size of every artifact
backup.log
SUCCESS                                # written ONLY if every component passed
```

**Why per-DB `pg_dump -Fc` and not `pg_dumpall`:** each cluster holds exactly one application
database, so `pg_dumpall`'s only unique value is cluster globals — captured separately with
`--globals-only`. Custom format instead buys selective restore of a single service's DB,
`pg_restore -j` parallel restore, `--list` inspection (which is what makes §3 cheap), and built-in
compression. A physical/WAL backup (pgBackRest / WAL-G) would give a much lower RPO but needs
`archive_command` configured in all 15 clusters plus an off-host repository — that is the documented
next step, not what exists today.

**Failure behaviour — there are no silent partial backups.** Artifacts are staged in
`<timestamp>.partial/` and renamed to `<timestamp>/` only after every component succeeded. Any
failure leaves the `.partial` directory for triage, writes **no** `SUCCESS` marker, exits non-zero,
and **skips retention entirely** — a broken run can never delete the last good one. Each dump is
additionally re-read with `pg_restore --list` before being accepted, which catches the truncated-file
failure mode.

**Retention:** `--keep-daily N` (default 7) + `--keep-weekly M` (default 4, Sunday-dated sets).
Only `SUCCESS`-marked sets are pruning candidates; `.partial` directories are never auto-deleted.

**Off-host copy (do this — a backup on the same disk is not a backup):** the target is a plain
directory, so pick whatever the org already has. There is no S3/cloud dependency in this repo and
this runbook does not add one.

```bash
# Example: nightly push to a second physical disk or an NFS/SMB mount.
rsync -a --delete /var/backups/gmepay/ /mnt/backup-disk/gmepay/
```

---

## 3. How to verify a backup (do this weekly — an unverified backup is a rumour)

Non-destructive. It restores the artifact into a **scratch database** in the same cluster, prints the
table list and row counts, then drops the scratch DB. The live database is never touched.

```bash
bash scripts/backup/gmepay-restore.sh --set 20260728T020000Z --db postgres-txn --verify-only
```

```powershell
.\gmepay-backup.ps1 -Action verify -Set 20260728T020000Z -Db postgres-txn
```

Expected output ends with `VERIFY postgres-txn/txndb OK — <N> tables restored, scratch DB dropped`.
It exits non-zero if 0 tables came back.

**Weekly drill checklist:**

1. `--verify-only` on `postgres-txn` **and** `postgres-authid` (ledger of record + RBAC).
2. Compare the reported row count for the main transaction table against live:
   `docker exec code-postgres-txn-1 psql -U gmepay -d txndb -Atc "select count(*) from transactions;"`
   — the backup should be ≤ live and within one backup interval's worth of rows.
3. Verify checksums: `cd <set> && sha256sum -c <(awk '/^[0-9a-f]{64}/{print $1"  "$3}' MANIFEST.txt)`
4. Confirm the off-host copy (§2) has the same set.
5. Record the date + result in this file's revision note.

**Once, before pilot traffic:** do a full `--db all` restore into a throwaway copy of the stack and
bring the fleet up against it. That is the only thing that proves RTO. **This has not been done yet
— see §7.**

---

## 4. RPO / RTO and the schedule they imply

| | Target | Basis |
|---|---|---|
| **RPO** | **24 h** (up to 24 h of transactions lost) | Nightly logical dump only; no WAL archiving, so there is nothing between dumps |
| **RTO** | **~1 h** for one database; **~4 h** for the full fleet, *estimated* | Restore is 15 × `pg_restore -j2` + service restarts. **Not yet measured — see §7** |

24 h RPO is **not acceptable for live money movement.** Before real traffic either move to
continuous archiving (pgBackRest/WAL-G, RPO in minutes) or raise the dump frequency to hourly for at
least `txndb`, `executor`, `prefunding`, and `ledger`. The nightly schedule below is the floor, not
the goal.

### 4.1 cron (WSL2 / Linux host) — the recommended installation

```cron
# /etc/cron.d/gmepay-backup   (mode 0644, root)
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
MAILTO=platform-oncall@gmeremit.com

# Nightly full logical backup, 02:00 KST (17:00 UTC previous day).
0 17 * * *  root  /bin/bash /mnt/d/GMEPay+/code/scripts/backup/gmepay-backup.sh --target /var/backups/gmepay >> /var/log/gmepay-backup.log 2>&1
# Off-host copy, 15 min later.
15 17 * * * root  /usr/bin/rsync -a --delete /var/backups/gmepay/ /mnt/backup-disk/gmepay/ >> /var/log/gmepay-backup.log 2>&1
# Weekly verification drill, Monday 03:00 KST.
0 18 * * 0  root  /bin/bash /mnt/d/GMEPay+/code/scripts/backup/gmepay-restore.sh --set "$(ls -1 /var/backups/gmepay | grep -E '^20.*Z$' | tail -1)" --db postgres-txn --verify-only >> /var/log/gmepay-verify.log 2>&1
```

The script's non-zero exit is what makes `MAILTO` fire — do not wrap it in anything that swallows the
exit code.

### 4.2 systemd timer (alternative, if the distro has systemd — `.smoke/infra-up.sh` shows it does)

```ini
# /etc/systemd/system/gmepay-backup.service
[Unit]
Description=GMEPay+ full logical backup
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
ExecStart=/bin/bash /mnt/d/GMEPay+/code/scripts/backup/gmepay-backup.sh --target /var/backups/gmepay
ExecStartPost=/usr/bin/rsync -a --delete /var/backups/gmepay/ /mnt/backup-disk/gmepay/
```

```ini
# /etc/systemd/system/gmepay-backup.timer
[Unit]
Description=Nightly GMEPay+ backup (02:00 KST)

[Timer]
OnCalendar=*-*-* 17:00:00 UTC
Persistent=true

[Install]
WantedBy=timers.target
```

```bash
systemctl daemon-reload && systemctl enable --now gmepay-backup.timer
systemctl list-timers gmepay-backup.timer     # confirm the next run
```

### 4.3 Windows Task Scheduler (only if the WSL distro is not auto-started)

WSL2 does not run cron unless the distro is up, so on a workstation host register the task on Windows
instead. Run as SYSTEM, "Run whether user is logged on or not", highest privileges:

```powershell
$act = New-ScheduledTaskAction -Execute 'powershell.exe' `
  -Argument '-NoProfile -ExecutionPolicy Bypass -File "D:\GMEPay+\code\scripts\backup\gmepay-backup.ps1" -Target /mnt/d/gmepay-backups'
$trg = New-ScheduledTaskTrigger -Daily -At 2:00am
$set = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 3)
Register-ScheduledTask -TaskName 'GMEPay-Nightly-Backup' -Action $act -Trigger $trg `
  -Settings $set -User 'SYSTEM' -RunLevel Highest -Description 'GMEPay+ full logical backup (T3-1)'
```

Check results with `Get-ScheduledTaskInfo -TaskName 'GMEPay-Nightly-Backup'`; `LastTaskResult` must
be `0`. A non-zero result means the backup failed — treat it as a page-worthy alert.

---

## 5. Restore after data loss

`gmepay-restore.sh` **drops and recreates** the target database. Three guards must all be satisfied:
an explicit `--set` (there is deliberately no "latest" shortcut), an explicit `--db`, and
`--i-understand-this-destroys-data`; `--db all` additionally requires typing `RESTORE-ALL` at an
interactive prompt, so cron can never do it by accident. Sets without a `SUCCESS` marker are refused
outright.

### 5.1 Find what you have

```bash
bash scripts/backup/gmepay-restore.sh --list
```

### 5.2 One database (the common case — one service corrupted its own data)

```bash
# 1. Stop the owning service so nothing writes mid-restore (see the §1.1 owner column).
docker compose stop transaction-mgmt

# 2. Prove the artifact is good BEFORE destroying the live DB.
bash scripts/backup/gmepay-restore.sh --set 20260728T020000Z --db postgres-txn --verify-only

# 3. Restore.
bash scripts/backup/gmepay-restore.sh --set 20260728T020000Z --db postgres-txn \
     --i-understand-this-destroys-data

# 4. Bring the service back; Flyway re-validates the schema on boot.
docker compose start transaction-mgmt
docker compose logs --tail=50 transaction-mgmt     # expect no Flyway validate error
```

If Flyway reports a checksum/version mismatch, the dump predates a migration: restore, then let the
service apply the newer migrations on boot — do **not** hand-edit `flyway_schema_history`. Remember
Flyway 10 stores versions **zero-padded** (`'023'`).

### 5.3 Full fleet (volumes lost / host rebuilt)

```bash
docker compose down                      # NOT -v if any volume survived
docker compose up -d postgres-config postgres-txn postgres-prefunding postgres-ledger \
  postgres-settlement postgres-notify postgres-authid postgres-scheme postgres-executor \
  postgres-qr postgres-ratefx postgres-reporting postgres-sendmn postgres-keycloak \
  postgres-ninepay mongo minio                      # datastores only, no app services yet

bash scripts/backup/gmepay-restore.sh --set 20260728T020000Z --db all \
     --i-understand-this-destroys-data              # type RESTORE-ALL when prompted

docker compose --profile core up -d                 # then the rest of the fleet
```

**Rebuild order that matters:** `configreg` and `authid` first (nothing routes or authorises without
them), then `txndb`, then the money DBs (`prefunding`, `ledger`, `settlement`, `executor`), then
adapters and `reporting`. `--db all` follows the inventory order, which already starts with config
and includes authid before the adapters; if you restore by hand, follow that order.

### 5.4 Mongo and MinIO

```bash
bash scripts/backup/gmepay-restore.sh --set 20260728T020000Z --db mongo  --i-understand-this-destroys-data
bash scripts/backup/gmepay-restore.sh --set 20260728T020000Z --db minio  --i-understand-this-destroys-data
```

Mongo uses `mongorestore --drop --nsInclude merchant.*`. MinIO mirrors the objects back and creates
the bucket if absent — **but** objects still under object-lock COMPLIANCE retention in an existing
bucket cannot be overwritten, by design. If you must restore over a locked bucket, restore into a new
bucket name and repoint `GMEPAY_VAULT_*` config; do not try to defeat the lock (it is the compliance
control).

### 5.5 After any restore

1. Run reconciliation for the affected window (settlement-reconciliation).
2. Compare `txndb` against scheme-side records for the RPO gap — transactions accepted after the
   last backup exist at the scheme but not in our ledger. **They must be re-keyed or written off
   explicitly; they do not come back.**
3. Check `executor` idempotency keys before letting any retry/replay run, or you will double-execute.
4. Notify Compliance if the gap crosses a filing period (`reporting`).

---

## 6. Adding a new database

1. Add the service to `docker-compose.yml`.
2. Add the row to `scripts/backup/inventory.env` (`PG_INVENTORY`).
3. `bash scripts/backup/check-inventory.sh` — must print `OK`.

Step 2 is not optional: a DB absent from the inventory is silently never backed up. Wire
`check-inventory.sh` into CI next to the other static checks so the drift fails the build.

---

## 7. What is NOT covered (read this before saying "we have backups")

| Not covered | Consequence | Mitigation / status |
|---|---|---|
| **Kafka topic data + consumer offsets** | No volume at all (§1.4) — `docker compose down` destroys them. Replay position is unrecoverable; consumers may re-deliver or skip after a restore | Events originate in DB outbox tables, so the facts survive. Add a Kafka volume + `kafka-consumer-groups --describe` snapshot to close this |
| **Schema Registry subjects** | Lost with Kafka (`_schemas` topic) | Schemas are in `libs/lib-api-contracts`; re-register on boot |
| **In-flight state** | A logical dump is a point-in-time snapshot per DB, taken **sequentially** — the 15 dumps are *not* mutually consistent. A payment mid-flight can appear approved in `txndb` but unexecuted in `executor` | Run backups during the quiet window; always reconcile after restore (§5.5). Cross-DB consistency needs a coordinated snapshot, which does not exist |
| **The RPO gap itself** | Up to 24 h of real transactions are simply gone (§4) | No WAL archiving. This is the single biggest remaining hole |
| **Secrets** | Not backed up **on purpose**. `.env`, Keycloak client secrets, `docker/certs`, MinIO credentials, webhook signing secrets are not in any artifact | Store them in the org's secret manager. A restore without them yields a fleet that boots but cannot authenticate to anything |
| **Redis** | Not backed up (no volume) | Cache only |
| **Generated file drops** | `build/bok-out`, `$TMPDIR/gmepay/outbound` (ZeroPay SFTP stub), settlement/report files not yet transmitted | Regenerable from `reporting` + `settlement` DBs |
| **Off-host / off-site copy** | The §2 `rsync` step is **documented, not installed**. Until an operator installs it, backups sit on the same host as the data they protect | Install §4.1 line 2 |
| **A performed full restore drill** | RTO in §4 is an estimate. `--verify-only` proves the artifacts are readable; it does not prove the fleet comes up against them | **Do the §3 full drill before pilot traffic.** This is the remaining acceptance item for COO finding #2 |
| **Kubernetes/Helm deployments** | The chart has no StatefulSet/PVC — datastores are external and out of scope (§1.5) | Whoever provides those endpoints owns their backups |

---

## 8. Escalation

| When | Do this |
|---|---|
| Backup exited non-zero (cron mail, or Task Scheduler `LastTaskResult ≠ 0`) | Read the named failed component in `<target>/<ts>.partial/backup.log`. Most common: a datastore container is not running → start it and re-run. **Re-run the same day** — the previous set is still there because retention never runs on failure |
| No new set for **2 nights** | Platform on-call. Two nights means the RPO is already 48 h+ |
| `--verify-only` fails or reports 0 tables | Treat every set since the last good verification as suspect. Take a fresh backup immediately, verify it, and do not prune anything until one verifies clean |
| Data loss suspected (money-path DB) | **Stop the affected service before anything else** — a running service writing into a half-lost DB makes recovery worse. Then page platform on-call + CFO-side finance owner (money DBs) or Compliance (`reporting`). Do not restore without both a `--verify-only` pass and an owner on the call |
| Object-lock blocks a MinIO restore | Do **not** try to defeat the lock. Restore to a new bucket and repoint config (§5.4); loop in Compliance |
| Host/volumes gone entirely | §5.3, then §5.5. Expect to re-key the RPO gap manually |

Contacts: platform on-call — `platform-oncall@gmeremit.com` *(placeholder: replace with the real
rota; there is no paging target wired today — see gap **T3-3**)*.

---

## 9. Files

| Path | Purpose |
|---|---|
| `scripts/backup/inventory.env` | **SSOT** stateful inventory, sourced by every script |
| `scripts/backup/gmepay-backup.sh` | Full backup (Postgres + globals + Mongo + MinIO), manifest, retention |
| `scripts/backup/gmepay-restore.sh` | Restore / `--verify-only` / `--list`, with the three guards |
| `scripts/backup/check-inventory.sh` | Static drift check vs `docker-compose.yml` — CI-safe |
| `scripts/backup/gmepay-backup.ps1` | Thin Windows wrapper (`wsl -d gmepay-docker`); no duplicated logic |
| `Documentation/RUNBOOK_BACKUP_DR.md` | This runbook |
