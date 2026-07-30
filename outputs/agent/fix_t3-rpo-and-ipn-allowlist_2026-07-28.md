> 작업: T3-8 RPO + T5-7 IPN allowlist / 출처: agent

# T3-8 (RPO / schedule / drill) + T5-7 (9Pay IPN IP allowlist)

Closes what is buildable of gaps **T3-8** and **T5-7** in `Documentation/GAP_REGISTER.md`. Both are
now `[~]`, and in both cases the residual is **not code** — it is a partner input (9Pay's IP ranges)
and two operator actions (install the schedule, run the drill). Those are named exactly in §C.

Modules touched: `scripts/**`, `Documentation/**`, `deploy/helm/gmepay/**`,
`services/scheme-adapter-ninepay`. **`docker-compose.yml` was NOT modified** (§A.1 explains why that
turned out to be a design input, not just a constraint). Nothing was started; no backup, restore,
database, container or server was executed against.

---

## 0. Summary

| Gap | Before | After |
|---|---|---|
| T3-8 RPO | nightly logical dump only ⇒ **24 h** for everything, incl. the money path | continuous WAL streaming + base backups for **5 money-critical clusters** ⇒ RPO = receiver lag (seconds–minutes) *once enabled*; the other 10 stay 24 h **deliberately, with the reasoning written down** |
| T3-8 schedule | cron/systemd/Task-Scheduler text in the runbook, **installed nowhere** | one idempotent installer per platform, 5 jobs, refuses to double-install; `--status`/`-Check`/`--uninstall` |
| T3-8 "is it running?" | unanswerable | `gmepay-backup-check.sh` — 6 checks, exit 0/1/**2 = the RPO is not what the runbook claims** |
| T3-8 consistency | "the 15 dumps are not mutually consistent" (true, and left there) | consistent-snapshot path **for the 5 PITR clusters** (one common `recovery_target_time`) + the reconcile procedure spelled out step-by-step for the other 10. Not overclaimed |
| T3-8 drill | "has not been done" | **§3a: a 12-step procedure an operator can execute**, ending in a measured RTO in `DRILL_LOG.md`. Still not run |
| T5-7 IPN edge | `application.yml` claimed "enforced at the network layer" — enforced **nowhere**; the chart had **no route to the adapter at all** | separate fail-closed Ingress (`Exact /scheme/ipn`) + in-process filter; both ship EMPTY and both log the unprotected state loudly |

Tests: `scheme-adapter-ninepay` **82 → 102, 0 failures**. `gradlew testClasses` BUILD SUCCESSFUL.
`check_helm_chart_wiring.py` **193/193** (was 166/166; +27 for the new §7).

---

# PART A — T3-8

## A.1 The RPO fix: `pg_receivewal`, and why not `archive_command`

Both give PITR. The choice was made on five grounds, in order of weight:

1. **No restart and no compose change.** `archive_mode` is a **postmaster-level** GUC: turning it on
   means bouncing all five money clusters, and `archive_command` needs a writable archive path
   *inside* each container — five new bind mounts in `docker-compose.yml`. `pg_receivewal` needs
   neither: `wal_level=replica` and `max_wal_senders=10` are already the **PostgreSQL 16 defaults**,
   and `pg_create_physical_replication_slot` is an online operation. Nothing on the money path is
   restarted to enable this.
   *This also settled a practical hazard:* two other workstreams are editing `docker-compose.yml`
   concurrently, and staging that file would have swept their in-progress edits into this commit. A
   design that needs no compose change is strictly better here, not merely convenient.
2. **Lower RPO.** `archive_command` only ships a segment once it is **full (16 MB)** or
   `archive_timeout` fires, so the RPO floor is a segment's worth of traffic. `pg_receivewal
   --synchronous` fsyncs as records arrive; the residual exposure is the in-flight record.
3. **The archive is off-volume by construction.** `archive_command` writes wherever the container can
   reach — usually the same disk as PGDATA, i.e. a copy that dies with the thing it protects. The
   receiver container mounts a host directory and nothing else.
4. **It matches the repo.** `gmepay-backup.sh` already uses `docker exec` so `pg_dump` version-matches
   the server; the receiver runs a throwaway `postgres:16-alpine` for the same reason and adds no
   service definition to maintain.
5. **It is reversible.** Stop the receiver, drop the slot, and the cluster is byte-identical. A
   `postgresql.conf` change is not.

### The cost, stated rather than hidden

A physical replication slot makes the primary **retain WAL the receiver has not consumed**. A
receiver that dies unnoticed grows `pg_wal` until the disk fills and **PostgreSQL shuts down** — the
backup mechanism causing a money-path outage, which is worse than the gap it closes. Three guards,
all shipped:

- `--init` sets `max_slot_wal_keep_size` (default `8GB`, `inventory.env`) via `ALTER SYSTEM` +
  `pg_reload_conf()` — a **reload, not a restart**. Past that bound the primary **invalidates the
  slot and keeps serving**: PITR continuity is sacrificed before the database is. That is the correct
  side of the trade and it is made explicitly.
- a **5-minute watchdog** job (`--start`, idempotent) restarts a dead receiver.
- `gmepay-backup-check.sh` §5 surfaces slot state, receiver state, **lag** and base-backup age hourly.

### WAL alone is not a backup

PITR = base backup + subsequent WAL. So `--basebackup` (`pg_basebackup -Ft -z -X none
--checkpoint=fast`, staged-then-promoted, newest 2 kept) is part of the capability, `--start`
**refuses to run** without a base backup present, and `--status` never reports OK without one. The
weekly base backup is job 3 of the installed schedule.

### The PITR set — five, and why

`PITR_SERVICES` in `scripts/backup/inventory.env`: `postgres-config`, `postgres-txn`,
`postgres-prefunding`, `postgres-ledger` (the four the gap names) **plus `postgres-executor`** — its
idempotency keys are what prevent double execution, so losing them is worse than losing the record of
a payment. The other ten stay dump-only *deliberately*: each streamed cluster costs a slot on a live
primary (above), and `qrdb`/`ratefx`/adapter state/`keycloak` are re-derivable, re-fetchable or
re-seedable. `reporting` and `settlement` are named in the runbook as the candidates if the line moves.

## A.2 The schedule: installable and idempotent

`scripts/backup/gmepay-schedule-install.sh` (systemd if the host has it, else cron) and
`scripts/backup/gmepay-schedule-install.ps1` (Windows Task Scheduler — cron inside a stopped WSL
distro never fires, and each task is a `wsl -d gmepay-docker -- bash …` call which also *starts* the
distro). Both install **five** jobs, because a nightly dump alone is not the capability:

| # | job | when |
|---|---|---|
| 1 | `gmepay-backup.sh` | daily 02:00 KST |
| 2 | off-host `rsync` | daily 02:15 KST (only with `--offhost`) |
| 3 | `gmepay-pitr.sh --basebackup` | Sunday 01:00 KST |
| 4 | `gmepay-pitr.sh --start` (watchdog) | every 5 min |
| 5 | `gmepay-backup-check.sh` | hourly |

**Refuses to double-install.** A stamp file (`/etc/gmepay-backup-schedule.conf`) records the exact
configuration; a re-run with the same arguments is a no-op that says so, a re-run with *different*
arguments is refused without `--force` — two schedules writing to two targets means neither one is
"the backup" and nobody can state the RPO. `--print` shows the units without touching anything,
`--status` shows what is live, `--uninstall` removes only what it created. Runbook §4.1–§4.3 are
demoted to *reference*: they were correct and never installed, which is precisely how T3-8 happened.

## A.3 Proving it runs: `gmepay-backup-check.sh`

Six checks, and the **exit code is the contract** (0 OK / 1 WARN / 2 CRITICAL / 3 cannot check):

1. is a schedule installed at all (cron file / enabled systemd timer) — CRIT if not;
2. newest `SUCCESS`-marked set **and its age in hours** (= the real RPO right now), plus whether it
   holds all 15 dumps, plus a WARN for leftover `.partial` dirs;
3. `--verify-checksums` (opt-in) verifies every artifact against `MANIFEST.txt`;
4. off-host copy exists, is current, and is the **same newest set** as local — so a mount that
   quietly unmounted or went read-only is caught within the hour;
5. live PITR state per cluster (slot / receiver / **lag** / newest base) by delegating to
   `gmepay-pitr.sh --status`;
6. drill history — WARNs when `DRILL_LOG.md` is absent or older than 90 days.

It works without docker for 1–4 (WARNs about 5), never writes anything, and starts nothing.

One subtlety worth recording: the freshness signal for a WAL directory **includes the in-progress
`.partial` segment**. `pg_receivewal` only closes a segment at 16 MB, so on a quiet cluster the newest
*complete* segment can be hours old while streaming is perfectly healthy — judging on complete
segments would have produced a false CRITICAL every quiet night.

## A.4 The consistency caveat — what is now true, and what is not

**Not fixed, because it cannot be:** the 15 logical dumps are sequential, so a restored set is 15
snapshots of 15 different moments. There is no coordinated snapshot across separate PostgreSQL
clusters and this repo does not pretend there is.

**What now exists** is a consistent-snapshot path *for the five clusters where inconsistency costs
money*: replaying each to the **same** `recovery_target_time` is by definition a common recovery
point, so the invariants between `txndb`, `executor`, `prefunding`, `ledger` and `configreg` hold at
that instant. `--restore-plan` deliberately emits **one** target time for all of them and says why
picking different times per cluster manufactures the very problem PITR solves.

**For the other ten** the runbook now states the reconcile-after-restore procedure precisely (§5.0,
8 steps): establish the window from the manifest, `txndb`↔`executor` (approved with no attempt =
a payment we believe we made and have no evidence of → resolve against the *scheme*), **check
idempotency keys before any retry**, prefunding holds, ledger re-posting, settlement re-run,
Compliance if a filing period moved, and write down what was changed.

## A.5 The drill (runbook §3a)

12 steps an operator can execute on a throwaway host: copy the set **from the off-host copy** over
the network, inventory check, clean slate, datastores up, `--db all` restore, fleet up, **Flyway
validate clean**, row counts on the four DBs that matter, then a **functional** check (log in, open a
restored transaction, run one reconciliation — a fleet that boots but cannot serve a page has not met
its RTO), stopwatch, teardown, and **write the number into `<target>/DRILL_LOG.md`**. A smaller PITR
drill (one cluster, target time 10 minutes ago) is described alongside it, because it proves something
the logical drill cannot: that base + streamed WAL actually replay to the requested point.

## A.6 Files (Part A)

| Path | State | Purpose |
|---|---|---|
| `scripts/backup/gmepay-pitr.sh` | new | `--init` / `--basebackup` / `--start` / `--stop` / `--status` / `--drop-slots` / `--restore-plan`; `--dry-run` and `--service` on all of them. Never writes to an application DB; `--restore-plan` prints, never executes |
| `scripts/backup/gmepay-backup-check.sh` | new | the proof-of-life above |
| `scripts/backup/gmepay-schedule-install.sh` | new | 5-job schedule, systemd or cron |
| `scripts/backup/gmepay-schedule-install.ps1` | new | the same 5 jobs as Task Scheduler tasks |
| `scripts/backup/inventory.env` | +PITR block | `PITR_SERVICES`, slot name, archive root, `max_slot_wal_keep_size`, image, network key — one place, with the reasoning per entry |
| `scripts/backup/gmepay-backup.ps1` | +2 actions | `-Action check` / `-Action pitr` (still a thin wrapper; zero duplicated logic) |
| `Documentation/RUNBOOK_BACKUP_DR.md` | rewritten §§ | new §1.5 (PITR set), §3a (drill), §4 RPO table split three ways, §4.0/§4.0.1 (installer + check), §4a (PITR + the retention hazard), §5.0 (which path + reconcile), §5.5, §6 step 4, §7, §8, §9 |

`--drop-slots` requires `--i-understand-this-ends-pitr` and prints what the RPO reverts to. That flag
is not decoration: dropping slots is the correct emergency response to disk pressure, and it must be
possible to do it knowingly at 3 a.m.

---

# PART B — T5-7 (9Pay IPN IP allowlist)

## B.1 Why this is a security control, not hardening

**T5-6 cannot be closed in code we own.** 9Pay leaves the IPN `code` field **outside** its signed
string, and a bank reversal (`code=009`) reuses the original transfer's `created_at`. A captured
success IPN with `code` rewritten to `009` therefore still verifies cryptographically, and
`NinepayIpnReplayGuard` cannot distinguish it from a genuine reversal. Until 9Pay signs `code`
(external ask, O-item), **proving the packet came from 9Pay's network is the only thing left**.

## B.2 A finding that came out of building it

The main Ingress fronts `api-gateway` + the two UIs and **nothing else** — there was **no route to
`scheme-adapter-ninepay` in the chart at all**. So in a Helm deployment 9Pay could never have reached
`/scheme/ipn`, and every VND payout would have sat waiting for a status push that could not arrive.
Opening that route and restricting it are therefore the same task, which is why the new template does
both or neither.

## B.3 Ingress layer (primary) — `deploy/helm/gmepay/templates/ingress-ipn.yaml`

- **Its own Ingress object.** Folding the IPN path into the main one would apply 9Pay's ranges to
  `api-gateway` as well (locking out every partner) or — far more likely — apply nothing to either.
- **`pathType: Exact` on `/scheme/ipn`.** The same Service also serves `/scheme/payout`,
  `/scheme/balance`, `/scheme/decode-qr`. A `/` Prefix rule would have published a
  **payout-submission endpoint** on the public internet.
- **Fails closed by construction.** `enabled: true` + empty `sourceRanges` ⇒ `helm template`/`install`
  **fails** with an explanation. The edge cannot be opened to everyone by forgetting a value; it can
  only be opened deliberately. Empty `host` fails the same way.
- **The annotation key is chosen by the template** from `ipnIngress.controller`: nginx →
  `whitelist-source-range`, ALB → `inbound-cidrs`. Hand-writing the ranges per environment invites the
  worst failure mode available here — CIDRs on the *wrong* controller's key are a **silent no-op** on
  an Ingress that looks configured. `appgw` is **rejected with a message**, because AGIC has no
  per-Ingress source allowlist: on Azure it must be an App Gateway WAF custom rule + subnet NSG, and
  `values-azure.yaml` says exactly that rather than rendering something that filters nothing.
- Shipped `enabled: false` with `sourceRanges: []` in **all four** values files.

## B.4 Service layer (defence in depth) — `services/scheme-adapter-ninepay`

`NinepayIpnSourceFilter` (a `OncePerRequestFilter`; no spring-security on this module) + a
self-contained `IpnSourceAllowlist`:

- rejects with **403 before the body is parsed**, so a forged IPN never reaches `handleIpn` and never
  lands in `np_ipn_events` — a rejected source is not an IPN. The 403 body is the canonical `ApiError`
  and deliberately discloses nothing about the allowlist.
- **only** `/scheme/ipn` is filtered (context path stripped). The hub endpoints are gated by
  internal-auth, not by a partner's egress ranges.
- CIDR matching is IPv4 + IPv6 with **no name resolution at all**: IPv4 is parsed by hand
  (`InetAddress.getByName` would DNS-resolve anything non-IPv6-shaped) and a hostname is refused. An
  ACL that resolves names changes meaning when someone else's DNS does, and a lookup in the request
  path is a latency/DoS surface. Families never cross; `::ffff:a.b.c.d` normalises to the IPv4 range
  (some container stacks report the peer that way, and treating it as unmatched would deny a
  legitimate source); `host:port` and `[v6]:port` are handled.
- **`X-Forwarded-For` is ignored by default.** `trusted-proxy-count: 0` ⇒ socket peer only, because the
  header is attacker-controlled unless a trusted hop overwrote it. With N trusted hops the client is
  the entry N-from-the-right; a header too short for N is "undeterminable" and therefore **denied**.

### The two states, both explicit and both logged

| state | behaviour | signal |
|---|---|---|
| **EMPTY** (shipped default everywhere) | admits every source — local/dev and `sim-ninepay` keep working | WARN on **every boot** naming T5-7/T5-6 and saying the compensating control is INACTIVE, **plus** one WARN on the first IPN admitted (then DEBUG). An unprotected edge is loud, not silent |
| **NON-EMPTY** | fail closed: 403, source + XFF + hop count logged | INFO at boot: ranges, hop count, and which address is being compared |
| **MALFORMED** | neither — the bean throws, the context **refuses to start** | nobody boots "protected" by a typo'd CIDR |

## B.5 Guard + tests

`scripts/check_helm_chart_wiring.py` **§7** (new, 193/193 total) asserts the posture cannot drift: the
template exists, carries the `fail` guard, stays `Exact` (and has no `Prefix`), renders both
controllers' annotation keys; all four values files declare `ipnIngress`, ship `enabled: false` and
**empty** `sourceRanges`; the filter exists; the adapter's shipped default is empty; the chart declares
both env vars and ships the allowlist empty; and **no partner IPv4 literal appears** in the chart, the
template or the adapter's `application.yml`.

`scheme-adapter-ninepay`: **102 tests, 0 failures** (was 82). The new ones cover the two states, the
spoofed-XFF case, the trusted-proxy topology, path scoping, context path, malformed-range fail-fast,
and a `YamlPropertySourceLoader` assertion that the **shipped** `application.yml` still ships the
allowlist empty — so nobody can "helpfully" bake 9Pay's addresses into the image.

## B.6 Two corrections made in passing

- `services/scheme-adapter-ninepay`'s `application.yml` comment claimed IP whitelisting was "enforced
  at the network layer, not here" while it was enforced **nowhere**. Replaced with what is true.
- The chart pointed `scheme-adapter-ninepay` at `postgres-scheme:5432/npadapter` and
  `scheme-adapter-sendmn` at `postgres-scheme:5432/smnadapter` — the **zeropay** cluster's host with
  another service's database name. `docker-compose.yml` and `scripts/backup/inventory.env` both give
  `npadapter`/`smnadapter` their own clusters. Corrected to `postgres-ninepay` / `postgres-sendmn`.

---

# C. What is NOT done — exactly who must do what

### C.1 The operator must **supply** (T5-7)

**9Pay's current IPN egress IP ranges, in writing from 9Pay.** This repo does not carry them and the
guard forbids committing them. `Documentation/schemes/digest_9pay-payout-api_2026-07-27.md` records
what 9Pay's API document listed at digest time — that is a reference to *confirm with the partner*,
never a default to ship: a stale hardcoded range fails closed against live traffic, which is a
production incident, not a safe default. They differ between 9Pay's test and production estates.

Set them in **both** places, per environment:

```yaml
ipnIngress:
  enabled: true
  host: ipn.<your-domain>
  sourceRanges: ["<from 9Pay>", "…"]
services:
  scheme-adapter-ninepay:
    env:
      GMEPAY_SCHEME_NINEPAY_IPN_ALLOWED_SOURCE_RANGES: "<from 9Pay>,…"
      GMEPAY_SCHEME_NINEPAY_IPN_TRUSTED_PROXY_COUNT: "1"     # behind the ingress
```

Also partner/infra work, not code: **register our egress address with 9Pay** (they whitelist us too —
needs a stable NAT/SNAT address), and on Azure implement the WAF custom rule + subnet NSG instead of
the Ingress.

### C.2 The operator must **execute** (T3-8)

```bash
# 1. install the schedule (idempotent; refuses to double-install)
sudo bash scripts/backup/gmepay-schedule-install.sh \
     --target /var/backups/gmepay --offhost /mnt/backup-disk/gmepay
#    Windows host (WSL distro not always up), elevated:
#      .\scripts\backup\gmepay-schedule-install.ps1 -Target … -Offhost …

# 2. enable PITR once — the schedule only KEEPS it running
bash scripts/backup/gmepay-pitr.sh --init
bash scripts/backup/gmepay-pitr.sh --basebackup
bash scripts/backup/gmepay-pitr.sh --start

# 3. prove it
bash scripts/backup/gmepay-backup-check.sh --target /var/backups/gmepay \
     --offhost /mnt/backup-disk/gmepay          # must exit 0

# 4. run ONE full restore drill: Documentation/RUNBOOK_BACKUP_DR.md §3a
#    -> write the measured RTO into /var/backups/gmepay/DRILL_LOG.md
```

Until (1) and (2), **the money DBs' RPO is still 24 h** and `gmepay-backup-check.sh` exits 2 saying
so. Until (4), the ~4 h fleet RTO remains an estimate. Neither can be closed from a repository.

### C.3 Residual, recorded honestly

- **T5-6 is untouched.** This bounds *who* can send a forged reversal; it does not make the forgery
  detectable. Only 9Pay signing `code` does.
- The **off-host location** is an org input; `$GMEPAY_PITR_ARCHIVE` must be mirrored off-host
  **separately** (it is not inside a backup set).
- WAL retention past `max_slot_wal_keep_size` **silently ends PITR continuity** — only the hourly
  check reveals it.
- Nothing **pages** on `check` exit 2 beyond cron mail / `LastTaskResult` (gap **T3-3**).
- A **Helm-deployed** platform inherits none of this: `scripts/backup/` is `docker exec`-based
  (T3-9), and the new Ingress has never been `helm template`d against a cluster (T3-10).
- Kafka/Zookeeper, Mongo and MinIO have **no PITR** — dump/mirror only.
- No NetworkPolicy limits in-cluster callers of `scheme-adapter-ninepay`; that is a *different*
  control (external source IPs are SNAT'd before a NetworkPolicy sees them, so 9Pay's CIDRs would be
  meaningless there).

---

# D. Verification performed

| Check | Result |
|---|---|
| `gradlew.bat testClasses` | **BUILD SUCCESSFUL** |
| `scheme-adapter-ninepay:test` | **102 tests, 0 failures** (was 82) |
| `bash -n` on all 6 `scripts/backup/*.sh` | PASS |
| PowerShell `Parser::ParseFile` on both `.ps1` | PASS, 0 errors |
| `check-inventory.sh` executed | **OK — 15/15 exact** (the PITR additions do not disturb it) |
| `gmepay-schedule-install.sh --print/--status` executed | renders all 5 jobs; correctly reports "not installed on this host" |
| `gmepay-pitr.sh --restore-plan` executed | full per-cluster plan, correct volume/owner/DB names; prints only |
| PyYAML parse: `docker-compose.yml` + all 4 Helm values | PASS |
| `check_helm_chart_wiring.py` | **193/193** |
| `check_internal_auth_wiring.py` | 94/94 |
| `check_monitoring_wiring.py` | 37/37 |
| `check_gitleaks_config.py` | findings=0, missed=0, false-positives=0 |
| `docker/keycloak/check-topology.mjs` | 101/101 |

Nothing was started: no docker, no postgres, no server. No backup and no restore was executed
against anything. Only this task's files were staged — `services/ops-partner-bff`,
`services/payment-executor` and `services/settlement-reconciliation` were modified in the working
tree by concurrent agents and were **not** included.
