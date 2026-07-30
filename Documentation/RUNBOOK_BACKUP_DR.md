# RUNBOOK — Backup, Restore & Disaster Recovery

**Scope:** the whole persistent surface of GMEPay+ — 15 PostgreSQL databases, MongoDB, MinIO,
plus continuous WAL streaming (PITR) for the five money-critical clusters.
**Audience:** whoever is on call. No prior knowledge of the fleet's internals assumed.
**Status:** closes gap **T3-1** (`Documentation/GAP_REGISTER.md`) / COO audit finding #2; the
schedule + PITR + drill residual is **T3-8** (see §4, §4a, §3a).
**Owner:** platform on-call (see §8). **Last reviewed:** 2026-07-28.

> **The two things this runbook cannot do for you.** Everything here is installable and
> checkable, but two acceptance items are physically an operator's:
> **(1)** install the schedule — `scripts/backup/gmepay-schedule-install.sh` (§4.1) — and enable
> PITR once (`gmepay-pitr.sh --init --basebackup --start`, §4a);
> **(2)** run one full restore drill (§3a) and write the measured duration into
> `<target>/DRILL_LOG.md`. Until (1) the RPO is unbounded no matter what §4 says; until (2)
> the RTO is an estimate. `scripts/backup/gmepay-backup-check.sh` reports the truth about both
> at any moment — run it before you tell anyone the platform has backups.

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

### 1.5 The PITR set — five clusters with continuous WAL streaming (T3-8)

The nightly dump gives every database a 24 h RPO. That is not acceptable for money, so these
five **also** stream their write-ahead log continuously and carry periodic base backups, which
is what makes point-in-time recovery possible. The list is `PITR_SERVICES` in
`scripts/backup/inventory.env`; `scripts/backup/gmepay-pitr.sh` operates it.

| Compose service | Database | Why it is in the set |
|---|---|---|
| `postgres-config` | `configreg` | nothing routes or prices without it |
| `postgres-txn` | `txndb` | the transaction ledger of record + event outbox |
| `postgres-prefunding` | `prefunding` | float balances / holds — money positions |
| `postgres-ledger` | `ledger` | revenue postings, GL vouchers |
| `postgres-executor` | `executor` | idempotency keys; losing them risks **double execution**, which is worse than losing the record of a payment |

The other ten are nightly-dump-only. That is a deliberate line, not an oversight: each streamed
cluster costs a physical replication slot on a live money-path primary (see the retention hazard
in §4a), so the set is the smallest one whose loss cannot be reconstructed or tolerated.
`qrdb`, `ratefx`, adapter state and `keycloak` are re-derivable, re-fetchable or re-seedable;
`reporting` and `settlement` are recomputable from `txndb` + `ledger` and are the strongest
candidates if the set is ever widened.

### 1.6 Kubernetes note

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

**Off-host copy — a backup on the same disk is not a backup.** The target is a plain directory, so
pick whatever the org already has (second physical disk, NFS/SMB mount). There is no S3/cloud
dependency in this repo and this runbook does not add one. It is **installed as a scheduled job**,
not left as a line to remember:

```bash
sudo bash scripts/backup/gmepay-schedule-install.sh --target /var/backups/gmepay \
     --offhost /mnt/backup-disk/gmepay        # job 2 of 5; see §4.0
# what that job runs, if you want it by hand once:
rsync -a --delete /var/backups/gmepay/ /mnt/backup-disk/gmepay/
```

`gmepay-backup-check.sh` §4 verifies the mirror exists, is current, and holds the *same newest set*
as the local target — so a mount that quietly went read-only or unmounted is caught within the hour,
instead of at the moment you need it.

**WAL / PITR is a separate artifact stream** and is not inside `<target>/<ts>/`: streamed segments and
base backups live under `$GMEPAY_PITR_ARCHIVE` (default `/var/backups/gmepay-wal`) and are produced by
`gmepay-pitr.sh` (§4a). Mirror that directory off-host too — WAL that only exists on the failed host
protects nothing.

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
3. Verify checksums — or just let the check script do it:
   `bash scripts/backup/gmepay-backup-check.sh --target <target> --verify-checksums`
4. Confirm the off-host copy (§2) has the same set — also covered by the check script's §4.
5. `bash scripts/backup/gmepay-pitr.sh --status` — slot active, receiver up, lag not growing, a base
   backup present for every PITR cluster. A green nightly dump with a dead WAL receiver means the
   money path is silently back to a 24 h RPO.
6. Record the date + result in `<target>/DRILL_LOG.md`.

**This weekly check is not the drill.** `--verify-only` proves an artifact is readable; §3a is the
thing that proves the platform comes back, and it has never been run.

---

## 3a. The full restore drill — **the operator action that converts the RTO estimate into a number**

`--verify-only` (§3) proves an artifact is *readable*. It does not prove the fleet comes up against
it. Nobody has ever done that, which is why every RTO figure in §4 is marked *estimated*. This is
the procedure. **It must be run by an operator on a real host — no script in this repo does it, and
no static check can substitute for it.**

**Do not run this against the production stack.** It ends with a fleet booted from backup data, and
the point is to time that, not to adopt it.

**Prerequisites**
- A `SUCCESS`-marked backup set, and an off-host copy of it (restore *from the off-host copy* — that
  is the copy you will actually have in a real disaster).
- A throwaway host, VM or second WSL distro with Docker and enough disk for all 15 volumes.
- A stopwatch. The output of this drill is a **duration**, not a feeling.
- 3–4 h booked. Do it before pilot traffic, then every 6 months and after any change to the
  inventory or the schema baseline.

**Procedure**

| # | Step | Record |
|---|---|---|
| 1 | `T0` — note the wall-clock start. Copy the backup set to the drill host **over the network**, from the off-host copy. | copy duration + set size |
| 2 | `bash scripts/backup/check-inventory.sh` on the drill host — 15/15, no drift. | pass/fail |
| 3 | Clean slate: `docker compose down -v` on the drill host (this is why it must be a throwaway). | — |
| 4 | Bring up **datastores only** (§5.3's `docker compose up -d postgres-… mongo minio`). | time to healthy |
| 5 | `gmepay-restore.sh --set <ts> --db all --i-understand-this-destroys-data` (type `RESTORE-ALL`). | restore duration, any failed DB |
| 6 | `docker compose --profile core up -d` — the fleet. | time to all-healthy |
| 7 | **Flyway**: `docker compose logs | grep -i flyway` — no `validate` failure anywhere. A mismatch means the dump predates a migration; let the service migrate on boot, never hand-edit `flyway_schema_history` (Flyway 10 stores versions zero-padded, `'023'`). | list of services that migrated |
| 8 | **Prove it is the data, not an empty schema.** Row counts on the four that matter: `transactions` (txndb), `execution_attempts` (executor), the prefunding balance table, `ledger` postings. Compare against the numbers recorded in the manifest/source system. | counts, and the delta vs source |
| 9 | **Prove the platform works, not just that it booted.** Log into admin-ui, open one restored transaction, and run one settlement reconciliation for a restored window. A fleet that starts but cannot serve a page has not met its RTO. | pass/fail per check |
| 10 | `T1` — note the wall-clock end. **RTO = T1 − T0.** | the number |
| 11 | Tear the drill host down. | — |
| 12 | **Write it down** in `<target>/DRILL_LOG.md`: date, operator, backup set, measured RTO, every step that failed or needed improvisation, and what was changed as a result. Update §4's RTO row with the measured value and stop calling it an estimate. | the log entry |

`gmepay-backup-check.sh` §6 looks for `DRILL_LOG.md` and warns when it is missing or older than
90 days — so a drill that is never repeated stops being invisible.

**A PITR drill is a separate, smaller exercise** and worth doing on the same day: pick a target time
~10 minutes in the past, run `gmepay-pitr.sh --restore-plan --target-time '<t>'`, and execute it for
`postgres-txn` alone on the drill host. What it proves that the logical drill cannot: that the base
backup + streamed WAL actually replay, and that the recovery target lands where you asked.

---

## 4. RPO / RTO and the schedule they imply

| | Target | Basis |
|---|---|---|
| **RPO — the five PITR clusters** (`configreg`, `txndb`, `prefunding`, `ledger`, `executor`) | **seconds to low minutes**, bounded by the receiver's replication lag | Continuous `pg_receivewal` with `--synchronous` + weekly base backup (§4a). The residual exposure is the in-flight WAL record, not a dump interval. **Conditional on the receivers actually running** — `gmepay-backup-check.sh` §5 is what tells you they are |
| **RPO — the other ten databases + Mongo + MinIO** | **24 h** | Nightly logical dump only. Deliberate: see §1.5 for why the line is drawn there |
| **RPO — if PITR is not enabled or a receiver is down** | **24 h, for everything** | The nightly dump is the floor. This is the state on a host where `gmepay-pitr.sh --init/--start` has never run, which is every host until an operator does it |
| **RTO** | **~1 h** for one database; **~4 h** for the full fleet — *still estimated* | Restore is 15 × `pg_restore -j2` + service restarts. §3a is the procedure that replaces this row with a measurement. **Not yet measured** |

**What changed with T3-8, stated precisely.** The money path's RPO is no longer a day — *once the
schedule is installed and PITR is initialised*. Neither of those happens by merging code, and the
platform's real RPO on any given host is whatever `gmepay-backup-check.sh` says it is there. The
honest summary while that is outstanding is: "the capability exists, is installable in one command,
and is unverified on any host."

### 4.0 Install the schedule — one command, idempotent

The cron/systemd/Task-Scheduler text in §4.1–§4.3 used to be the *installation instructions*. It
was correct and it was never installed anywhere, which is how the platform ended up with a backup
capability that ran only when someone remembered. Use the installer; the verbatim text below is now
reference for reading what got installed.

```bash
# On the Docker host (WSL2 distro 'gmepay-docker'). Needs root to write the units.
sudo bash scripts/backup/gmepay-schedule-install.sh \
     --target /var/backups/gmepay \
     --offhost /mnt/backup-disk/gmepay        # omit only if you have nowhere off-host YET

bash scripts/backup/gmepay-schedule-install.sh --print     # show the units, install nothing
bash scripts/backup/gmepay-schedule-install.sh --status    # what is installed right now
sudo bash scripts/backup/gmepay-schedule-install.sh --uninstall
```

It installs **five** jobs, because a nightly dump on its own is not the capability: the nightly
backup, the off-host mirror, the weekly PITR base backup, a **5-minute WAL-receiver watchdog**, and
the **hourly capability check**. It picks systemd when the host has it and cron otherwise
(`--flavour` forces one). It **refuses to install over an existing schedule** unless `--force`, and
a re-run with identical arguments is a no-op that says so — two schedules writing to two targets
means neither one is "the backup".

On a **Windows** host where the WSL distro is not always up, cron inside the distro never fires.
Register on the Windows side instead — same five jobs, each a thin `wsl -d gmepay-docker -- bash …`
call, which also starts the distro:

```powershell
# Elevated PowerShell.
cd D:\GMEPay+\code\scripts\backup
.\gmepay-schedule-install.ps1 -Target /mnt/e/gmepay-backups -Offhost /mnt/f/gmepay-backups
.\gmepay-schedule-install.ps1 -Check        # what is registered + how each task last exited
.\gmepay-schedule-install.ps1 -Uninstall
```

### 4.0.1 Prove it is running — `gmepay-backup-check.sh`

Installing a schedule and having backups are different claims. This is the second one:

```bash
bash scripts/backup/gmepay-backup-check.sh --target /var/backups/gmepay \
     --offhost /mnt/backup-disk/gmepay [--verify-checksums]
```

It reports, and **exits on**, six things: is a schedule installed at all; the newest
`SUCCESS`-marked set and **its age in hours** (i.e. the real RPO right now); whether that set holds
all 15 dumps; whether the off-host copy exists and is current; the live PITR state (slot, receiver,
lag, base backup) per cluster; and whether a restore drill has ever been recorded.

| exit | meaning |
|---|---|
| 0 | OK — schedule installed, newest good set fresh, PITR up |
| 1 | WARN — degraded but recoverable (e.g. no off-host copy configured) |
| 2 | **CRITICAL — the RPO is not what this runbook claims.** Page (§8) |
| 3 | cannot check |

The hourly job installed in §4.0 runs exactly this, so a non-zero exit is what makes cron mail /
`LastTaskResult` fire. Nothing else in the platform pages on a missing backup — gap **T3-3** owns
routing it to a human.

### 4a. PITR / WAL streaming — enable it once (T3-8)

**Two commands' worth of setup, and it is not optional for the money path.**

```bash
bash scripts/backup/gmepay-pitr.sh --init         # create the slots, bound WAL retention
bash scripts/backup/gmepay-pitr.sh --basebackup   # the base backup — WAL alone restores NOTHING
bash scripts/backup/gmepay-pitr.sh --start        # start the pg_receivewal sidecars
bash scripts/backup/gmepay-pitr.sh --status       # slot / receiver / lag / newest base, per cluster
```

**Why `pg_receivewal` and not `archive_mode` + `archive_command`.** Both give PITR. Streaming was
chosen because: `archive_mode` is a postmaster-level setting, so enabling it means **restarting all
five money clusters** and adding a writable archive path *inside* each container (five new bind
mounts in `docker-compose.yml`), whereas `pg_receivewal` needs neither — `wal_level=replica` and
`max_wal_senders=10` are already the PostgreSQL 16 defaults, and creating a replication slot is an
online operation. It also gives a **lower RPO** (`archive_command` only ships a segment once it is
full at 16 MB, or when `archive_timeout` fires; streaming with `--synchronous` fsyncs as records
arrive), the archive lands **off the data volume by construction** (the receiver container mounts a
host directory and nothing else), it matches the `docker exec`/version-matched-client posture the
rest of `scripts/backup/` already uses, and it is **reversible** — stop the receiver, drop the slot,
and the cluster is byte-identical to before.

> **The hazard, stated plainly.** A physical replication slot makes the primary **retain WAL** the
> receiver has not consumed. A receiver that dies unnoticed grows `pg_wal` until the disk fills and
> **PostgreSQL shuts down** — the backup mechanism causing the outage, which is worse than the gap
> it closes. Two guards, both installed: `--init` sets `max_slot_wal_keep_size` (default `8GB`,
> `inventory.env`) with `ALTER SYSTEM` + `pg_reload_conf()` — a **reload, not a restart** — so past
> that bound the primary invalidates the slot and keeps serving (you lose PITR continuity, not the
> database); and the 5-minute watchdog + hourly check make "unnoticed" impossible. **Watch the
> `SLOT-LAG` column in `--status`.** If it grows monotonically the receiver is not keeping up and
> you are heading for slot invalidation.

**Where things live:** `$GMEPAY_PITR_ARCHIVE` (default `/var/backups/gmepay-wal`),
`<archive>/<service>/wal/` for streamed segments (gzipped) and `<archive>/<service>/base/<ts>/` for
base backups, newest 2 kept. **Put this on a different physical disk from the Docker volumes** —
and note that `<archive>/<svc>/wal` must not be pruned past the oldest base backup you keep, or that
base becomes unusable.

**After any PITR restore** the cluster is promoted onto a **new timeline**: the pre-restore WAL
cannot be replayed onto it, so `--init --basebackup --start` must be re-run and the old archive is
history. `--restore-plan` prints this at the end too.

### 4.1 cron (WSL2 / Linux host) — *reference only; do not hand-install*

**Use §4.0.** What the installer writes is the authoritative version and it is five jobs, not three
— see it exactly, without installing anything:

```bash
bash scripts/backup/gmepay-schedule-install.sh --print --flavour cron \
     --target /var/backups/gmepay --offhost /mnt/backup-disk/gmepay
```

Shape, for reading:

```cron
# /etc/cron.d/gmepay-backup   (mode 0644, root)  — written by gmepay-schedule-install.sh
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
MAILTO=platform-oncall@gmeremit.com
GMEPAY_PITR_ARCHIVE=/var/backups/gmepay-wal

0 17 * * *   root  …/gmepay-backup.sh --target /var/backups/gmepay        # nightly, 02:00 KST
15 17 * * *  root  rsync -a --delete /var/backups/gmepay/ /mnt/backup-disk/gmepay/
0 16 * * 6   root  …/gmepay-pitr.sh --basebackup                          # weekly base, Sun 01:00 KST
*/5 * * * *  root  …/gmepay-pitr.sh --start                               # WAL receiver watchdog
7 * * * *    root  …/gmepay-backup-check.sh --target /var/backups/gmepay  # capability check
```

Two rules for anything you add here yourself: a script's **non-zero exit is what makes `MAILTO`
fire**, so never wrap one in something that swallows the exit code; and do not hand-edit the
installed file — re-run the installer with `--force`, or the stamp at
`/etc/gmepay-backup-schedule.conf` stops matching reality and `--status` starts lying.

If you want the weekly `--verify-only` drill as a sixth cron line (the installer does not add it,
because §3a's real drill is the acceptance item and a scripted `--verify-only` can lull you into
thinking it was done):

```cron
0 18 * * 0  root  /bin/bash …/gmepay-restore.sh --set "$(ls -1 /var/backups/gmepay | grep -E '^20.*Z$' | tail -1)" --db postgres-txn --verify-only >> /var/log/gmepay-verify.log 2>&1
```

### 4.2 systemd timer — *reference only; the installer's default on a systemd host*

`gmepay-schedule-install.sh` writes five `.service`/`.timer` pairs (`gmepay-backup`,
`gmepay-offhost`, `gmepay-basebackup`, `gmepay-walwatch`, `gmepay-backupcheck`) and enables each
timer. See them with `--print` (no `--flavour` needed on a systemd host); inspect what is live with
`--status`, or `systemctl list-timers 'gmepay-*'`. The pair below is the shape of the first one.

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

### 4.3 Windows Task Scheduler (required when the WSL distro is not auto-started)

WSL2 does not run cron unless the distro is up, so on a workstation-class host the schedule must live
on the Windows side — otherwise the backup silently does not happen on every day nobody opened a
terminal. `gmepay-schedule-install.ps1` (§4.0) registers the same five jobs as SYSTEM tasks, each a
`wsl -d gmepay-docker -- bash …` call, which also **starts the distro**. Do not hand-register:

```powershell
cd D:\GMEPay+\code\scripts\backup
.\gmepay-schedule-install.ps1 -Target /mnt/e/gmepay-backups -Offhost /mnt/f/gmepay-backups
.\gmepay-schedule-install.ps1 -Check      # per-task state + LastTaskResult, exits non-zero if bad
```

`-Check` is the Windows equivalent of `--status`: every task's `LastTaskResult` must be `0`
(`267009` = running, `267011` = never run yet). A non-zero result means that job **failed** — treat
it as page-worthy, and read the log named in §8. It exits `2` when nothing is registered at all,
which is the state on a fresh host.

Note the task target is `wsl.exe`, not `powershell.exe -File gmepay-backup.ps1`: one fewer shell in
the chain to swallow an exit code, and the same bash scripts run on Windows and Linux hosts.

---

## 5. Restore after data loss

`gmepay-restore.sh` **drops and recreates** the target database. Three guards must all be satisfied:
an explicit `--set` (there is deliberately no "latest" shortcut), an explicit `--db`, and
`--i-understand-this-destroys-data`; `--db all` additionally requires typing `RESTORE-ALL` at an
interactive prompt, so cron can never do it by accident. Sets without a `SUCCESS` marker are refused
outright.

### 5.0 Which path — and the cross-DB consistency question, answered honestly

There are now **two** recovery paths, and the choice matters more than the mechanics.

| | Logical restore (§5.2–§5.4) | **PITR** (`gmepay-pitr.sh --restore-plan`) |
|---|---|---|
| Covers | all 15 DBs + Mongo + MinIO | the five PITR clusters only (§1.5) |
| Recovers to | the moment of the nightly dump | **any moment** you name, to the second |
| Data lost | up to 24 h | seconds–minutes (the receiver's lag) |
| Cross-DB consistency | **none — see below** | **yes, for those five**, at one common recovery target time |
| Use when | a non-PITR DB is lost, or Mongo/MinIO, or you need everything | a money DB is lost or corrupted, or a bad change must be undone |

**The consistency caveat, precisely.** The 15 logical dumps are taken **sequentially**, so they are
snapshots of 15 *different* moments. Restoring a whole set therefore gives a fleet where a payment
can be `APPROVED` in `txndb` while `executor` has no execution attempt for it, `prefunding` still
holds the funds, and `ledger` has no posting — because those four files were written minutes apart.
There is no coordinated snapshot across separate PostgreSQL clusters, and this repo does not pretend
otherwise.

What T3-8 changes is that **a consistent recovery point now exists for the five clusters where
inconsistency costs money**: replaying each one's WAL to the *same* `recovery_target_time` is by
definition a common point, so the invariants between `txndb`, `executor`, `prefunding`, `ledger` and
`configreg` hold at that instant. `--restore-plan` emits the same target time for every cluster for
exactly this reason — **do not** hand-pick different times per cluster, or you have manufactured the
problem PITR is solving.

**For the other ten there is only the reconcile-after-restore procedure**, and it is not a
formality — it is the step that finds the mismatches the sequential dumps created:

1. **Establish the window.** Earliest and latest dump timestamps in the set (`MANIFEST.txt` /
   file mtimes). Everything inside it is suspect.
2. **`txndb` ↔ `executor`.** For transactions in the window: any `txndb` row in a non-terminal or
   approved state with **no** matching `execution_attempts` row is a payment we believe we made and
   have no evidence of. Resolve against the **scheme's** record, never by assumption.
   `POST /v1/transactions/{txnRef}/resolve` is the operator action.
3. **`executor` idempotency keys BEFORE any retry.** A restore can roll idempotency keys backwards,
   and a retry then double-executes. Check the keys exist for every in-window attempt first.
4. **`prefunding` ↔ `txndb`.** Holds with no live transaction must be released; balances must be
   re-derived from the transaction set, not trusted as stored.
5. **`ledger` ↔ `txndb`.** Re-post revenue for in-window transactions that have no posting. Double
   posting is the risk — check before writing.
6. **`settlement`.** Re-run reconciliation for the window; it is designed to surface exactly this.
7. **`reporting`.** If the window crosses a filing period, the filed aggregates are now wrong.
   Compliance decides on an amendment — this is not a technical call.
8. **Write down what you changed and why.** A reconciliation nobody can audit is indistinguishable
   from a fabrication.

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

1. Work through the **reconcile-after-restore procedure in §5.0** — that is where the cross-DB
   consistency steps live, in order, with the operator action for each.
2. Compare `txndb` against scheme-side records for the RPO gap. Transactions accepted after the
   recovery point exist **at the scheme** and not in our ledger. **They must be re-keyed or written
   off explicitly; they do not come back.** With PITR enabled this gap is minutes for the five money
   clusters instead of up to a day — but it is never zero.
3. Check `executor` idempotency keys before letting any retry/replay run, or you will double-execute.
4. Notify Compliance if the gap crosses a filing period (`reporting`).
5. **If the restore was a PITR restore:** the cluster is on a new timeline. Re-run
   `gmepay-pitr.sh --init --basebackup --start` for it — the pre-restore WAL archive is history and
   cannot protect the promoted cluster. Then `gmepay-backup-check.sh` to confirm PITR is live again.
6. **Record it.** Append what happened, what was recovered, the measured duration and the residual
   gap to `<target>/DRILL_LOG.md`. A real incident is the most valuable RTO measurement you will
   ever get; losing it means the next §4 estimate is no better than this one.

---

## 6. Adding a new database

1. Add the service to `docker-compose.yml`.
2. Add the row to `scripts/backup/inventory.env` (`PG_INVENTORY`).
3. `bash scripts/backup/check-inventory.sh` — must print `OK`.
4. **Decide whether it belongs in `PITR_SERVICES`** (§1.5) and say why in the comment there. If it
   holds money state or idempotency keys, it does. Then `gmepay-pitr.sh --init --basebackup --start`
   for the new service — the schedule's watchdog keeps it running but does not enrol it.

Step 2 is not optional: a DB absent from the inventory is silently never backed up. Wire
`check-inventory.sh` into CI next to the other static checks so the drift fails the build. Step 4 is
the one that gets forgotten, and its failure mode is a money DB with a 24 h RPO that everyone
believes is streaming — `gmepay-backup-check.sh` will not catch it, because a database nobody
enrolled is a database nobody is watching.

---

## 7. What is NOT covered (read this before saying "we have backups")

| Not covered | Consequence | Mitigation / status |
|---|---|---|
| **Kafka topic data + consumer offsets** | No volume at all (§1.4) — `docker compose down` destroys them. Replay position is unrecoverable; consumers may re-deliver or skip after a restore | Events originate in DB outbox tables, so the facts survive. Add a Kafka volume + `kafka-consumer-groups --describe` snapshot to close this |
| **Schema Registry subjects** | Lost with Kafka (`_schemas` topic) | Schemas are in `libs/lib-api-contracts`; re-register on boot |
| **Cross-DB consistency of a LOGICAL restore** | The 15 dumps are taken **sequentially**, so they are snapshots of 15 different moments. A payment can come back approved in `txndb` with no attempt in `executor`, funds still held in `prefunding` and no posting in `ledger` | **Partly closed.** For the five PITR clusters, replaying to one common `recovery_target_time` **is** a consistent recovery point (§5.0) — that is the consistent-snapshot path. For the other ten there is no coordinated snapshot and never will be across separate clusters: the mitigation is the reconcile-after-restore procedure in §5.0, stated step by step, plus running backups in the quiet window |
| **The 24 h RPO — for the ten non-PITR databases, Mongo and MinIO** | Up to 24 h of that data is gone | Accepted deliberately (§1.5): each is re-derivable, re-fetchable or recomputable from the PITR set. `reporting` and `settlement` are the candidates if the line moves |
| **PITR that has never been enabled on a host** | The money DBs' RPO is 24 h regardless of what §4 says. Merging code does not create replication slots | `gmepay-pitr.sh --init --basebackup --start` (§4a) — one operator action. `gmepay-backup-check.sh` §5 reports the truth per host, and exits 2 when it is not there |
| **WAL retention pressure from a dead receiver** | A slot with no consumer makes the primary retain WAL; unbounded, that fills the disk and stops PostgreSQL — the backup causing the outage | Bounded by `max_slot_wal_keep_size` (`--init`, default 8GB) so the primary sheds the slot instead of dying, plus the 5-min watchdog and hourly check. **Residual: past that bound PITR continuity silently ends** and only the check tells you |
| **Secrets** | Not backed up **on purpose**. `.env`, Keycloak client secrets, `docker/certs`, MinIO credentials, webhook signing secrets are not in any artifact | Store them in the org's secret manager. A restore without them yields a fleet that boots but cannot authenticate to anything |
| **Redis** | Not backed up (no volume) | Cache only |
| **Generated file drops** | `build/bok-out`, `$TMPDIR/gmepay/outbound` (ZeroPay SFTP stub), settlement/report files not yet transmitted | Regenerable from `reporting` + `settlement` DBs |
| **Off-host / off-site copy** | Until it is installed, backups sit on the same host as the data they protect — one disk or one ransomware event takes both | **Installable:** `gmepay-schedule-install.sh --offhost <dir>` (§4.0) adds the mirror job. Omitting `--offhost` is allowed but `gmepay-backup-check.sh` §4 WARNs about it every hour until it is set. **Still needs a real second location from the operator** — this repo does not choose one, and there is no S3/cloud dependency here |
| **A performed full restore drill** | RTO in §4 is an estimate. `--verify-only` proves the artifacts are readable; it does not prove the fleet comes up against them | **§3a is now a step-by-step procedure an operator can execute**, ending in a recorded number. It has still **never been run** — this remains the open acceptance item for COO finding #2 and T3-8, and no code change can close it |
| **Alerting on any of the above** | `gmepay-backup-check.sh` exits 2 when the RPO is not what this runbook claims, but nothing routes that to a human beyond cron mail / `LastTaskResult` | Gap **T3-3** owns the paging target. Until it is wired, "escalation" below means someone reads mail |
| **Kubernetes/Helm deployments** | The chart has no StatefulSet/PVC — datastores are external and out of scope (§1.6). Nothing in this runbook applies: `scripts/backup/` is `docker exec`-based | Whoever provides those endpoints owns their backups (RDS snapshots + PITR, etc.). Gap **T3-9** records this explicitly at the top of `deploy/helm/gmepay/values.yaml` |

---

## 8. Escalation

| When | Do this |
|---|---|
| Backup exited non-zero (cron mail, or Task Scheduler `LastTaskResult ≠ 0`) | Read the named failed component in `<target>/<ts>.partial/backup.log`. Most common: a datastore container is not running → start it and re-run. **Re-run the same day** — the previous set is still there because retention never runs on failure |
| No new set for **2 nights** | Platform on-call. Two nights means the RPO is already 48 h+ |
| `gmepay-backup-check.sh` exits **2** | Read which section failed. `NO SCHEDULE INSTALLED` → §4.0. Stale newest set → the nightly job is failing, see the row above. PITR rows bad → §4a. This exit code means **the RPO is not what §4 claims on this host**; do not let it sit |
| PITR `SLOT-LAG` growing, or receiver `DOWN` | `gmepay-pitr.sh --status`, then `docker logs code-walrcv-<service>`. While a receiver is down the primary is **retaining WAL**: check free space on the data volume before anything else. If disk is the immediate risk, `gmepay-pitr.sh --drop-slots --i-understand-this-ends-pitr` trades PITR for the money path staying up — the correct trade, made explicitly |
| A base backup is missing for a PITR cluster | Its streamed WAL restores **nothing**. `gmepay-pitr.sh --basebackup` now, and treat the interval since the last base as un-recoverable-by-PITR (the nightly dump still covers it) |
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
| `scripts/backup/gmepay-pitr.sh` | **T3-8.** WAL streaming + base backups for the money-critical clusters: `--init` / `--basebackup` / `--start` / `--stop` / `--status` / `--drop-slots` / `--restore-plan`. Never writes to an application DB; `--restore-plan` prints, it does not execute |
| `scripts/backup/gmepay-backup-check.sh` | **T3-8.** Is the capability actually running? Schedule, newest good artifact + age, off-host copy, PITR state, drill history. Exit 0/1/2 is the contract |
| `scripts/backup/gmepay-schedule-install.sh` | **T3-8.** Installs the five-job schedule (systemd or cron), idempotent, refuses to double-install, `--print` / `--status` / `--uninstall` |
| `scripts/backup/gmepay-schedule-install.ps1` | **T3-8.** The same five jobs as Windows Task Scheduler tasks (`wsl -d …`), for hosts where the distro is not always up. `-Check` / `-Uninstall` |
| `scripts/backup/gmepay-backup.ps1` | Thin Windows wrapper (`wsl -d gmepay-docker`); no duplicated logic. `-Action check` and `-Action pitr` reach the two new scripts |
| `<target>/DRILL_LOG.md` | **Operator-written**, not in git: the record of every restore drill and real recovery, and the only source of a measured RTO |
| `Documentation/RUNBOOK_BACKUP_DR.md` | This runbook |
