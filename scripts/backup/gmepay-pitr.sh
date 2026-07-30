#!/usr/bin/env bash
#
# gmepay-pitr.sh — continuous WAL streaming + base backups for the MONEY-CRITICAL
# PostgreSQL clusters, i.e. the thing that turns a 24 h RPO into a minutes-or-less
# one. Gap T3-8 (residual of T3-1).
#
# ============================================================================
# THE PROBLEM
# ============================================================================
# gmepay-backup.sh takes a nightly logical dump of all 15 databases. That bounds
# data loss at ONE FULL DAY of transactions, and there is nothing in between: if
# the host dies at 20:00 you have 02:00's dump, and every payment, top-up, hold
# and revenue posting since then exists only at the scheme. Those cannot be
# reconstructed from our side. 24 h is not an RPO for a payment platform; it is a
# statement that a day of money movement is disposable.
#
# ============================================================================
# THE CHOICE: pg_receivewal, NOT archive_mode/archive_command
# ============================================================================
# Both give PITR. This repo uses streaming for five reasons, in order of weight:
#
#  1. NO RESTART, NO COMPOSE CHANGE. `archive_mode` is a postmaster-level GUC, so
#     turning it on means restarting all five money clusters, and `archive_command`
#     needs a writable path INSIDE each container — a new bind mount per cluster in
#     docker-compose.yml. pg_receivewal needs neither: it connects over the normal
#     replication protocol, which postgres:16-alpine already permits
#     (wal_level=replica and max_wal_senders=10 are the PG16 DEFAULTS). Creating the
#     slot is an online operation. Nothing on the money path is bounced to enable it.
#  2. LOWER RPO. archive_command only ships a segment once it is FULL (16 MB) or
#     `archive_timeout` fires, so the RPO floor is "one segment's worth of traffic"
#     or the timeout, whichever comes first. pg_receivewal streams continuously and,
#     with --synchronous, fsyncs what it has received as it arrives — the residual
#     exposure is the in-flight record, not a partial segment.
#  3. THE ARCHIVE LANDS OFF-VOLUME BY CONSTRUCTION. archive_command writes wherever
#     the container can reach, which is usually the same disk as PGDATA — a copy
#     that dies with the thing it protects. Here the receiver container mounts a HOST
#     directory and nothing else.
#  4. IT MATCHES HOW THIS REPO ALREADY TALKS TO POSTGRES. gmepay-backup.sh uses
#     `docker exec` so pg_dump always version-matches the server; the receiver uses a
#     throwaway postgres:16-alpine container for exactly the same reason, and adds no
#     service definition anyone has to maintain.
#  5. IT IS REVERSIBLE. Stopping a receiver and dropping its slot leaves the cluster
#     byte-identical to before. A postgresql.conf change does not.
#
# ============================================================================
# THE COST — READ THIS BEFORE YOU RUN --init
# ============================================================================
# A physical replication slot makes the primary RETAIN WAL that the receiver has not
# consumed. If a receiver dies unnoticed, pg_wal grows until the disk fills and
# PostgreSQL SHUTS DOWN. That would make the backup mechanism the cause of a
# money-path outage — strictly worse than the gap it closes.
#
# Two guards, both mandatory, both applied here:
#   * --init sets max_slot_wal_keep_size (default 8GB, inventory.env) via ALTER
#     SYSTEM + pg_reload_conf() — a RELOAD, not a restart. Past that bound the
#     primary invalidates the slot and keeps serving. You lose PITR continuity, not
#     the database. That is the correct side of the trade.
#   * gmepay-backup-check.sh reports a dead receiver, slot lag and archive staleness
#     so "unnoticed" stops being possible. Wire it into the schedule
#     (gmepay-schedule-install.sh does).
#
# ============================================================================
# WAL ALONE IS NOT A BACKUP
# ============================================================================
# PITR = BASE BACKUP + the WAL written since it. A directory full of WAL segments
# with no base backup restores nothing. `--basebackup` takes the base (pg_basebackup
# -Ft -z, one tarball set per cluster); run it weekly at least, and ALWAYS after
# --init, before you believe you have PITR. --status refuses to report OK without one.
#
# USAGE
#   ./gmepay-pitr.sh --status                     # default: is PITR actually working?
#   ./gmepay-pitr.sh --init                       # create slots + set the WAL bound
#   ./gmepay-pitr.sh --basebackup                 # base backup for every PITR cluster
#   ./gmepay-pitr.sh --start                      # start the receiver containers
#   ./gmepay-pitr.sh --stop                       # stop them (WAL then piles up!)
#   ./gmepay-pitr.sh --drop-slots                 # full teardown; needs the confirm flag
#   ./gmepay-pitr.sh --restore-plan --target-time '2026-07-28 14:05:00+09'
#   ...any action with --service postgres-txn to scope it, or --dry-run to print only.
#
# ENV
#   GMEPAY_PITR_ARCHIVE       default from inventory.env (/var/backups/gmepay-wal)
#   GMEPAY_COMPOSE_PROJECT    default code   (container + network name prefix)
#   GMEPAY_LOCAL_PG_PASSWORD  default gmepay (docker-compose.yml's x-pg-password)
#   GMEPAY_PITR_IMAGE         default postgres:16-alpine
#
# This script NEVER writes to an application database and never restores anything.
# --restore-plan PRINTS commands; it does not run them.
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=inventory.env
. "$SCRIPT_DIR/inventory.env"

ARCHIVE="${GMEPAY_PITR_ARCHIVE:-$PITR_ARCHIVE_ROOT_DEFAULT}"
PROJECT="${GMEPAY_COMPOSE_PROJECT:-$COMPOSE_PROJECT_DEFAULT}"
NETWORK="${GMEPAY_PITR_NETWORK:-${PROJECT}_${PITR_NETWORK_KEY}}"
IMAGE="${GMEPAY_PITR_IMAGE:-$PITR_IMAGE_DEFAULT}"
PGPASS="${GMEPAY_LOCAL_PG_PASSWORD:-gmepay}"
SLOT="$PITR_SLOT_NAME"
KEEP_SIZE="${GMEPAY_PITR_MAX_SLOT_WAL_KEEP_SIZE:-$PITR_MAX_SLOT_WAL_KEEP_SIZE_DEFAULT}"
KEEP_BASES="${GMEPAY_PITR_KEEP_BASEBACKUPS:-2}"

ACTION="status"
ONLY_SERVICE=""
TARGET_TIME=""
DRY_RUN=0
CONFIRM_DROP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --status)       ACTION="status"; shift ;;
    --init)         ACTION="init"; shift ;;
    --basebackup)   ACTION="basebackup"; shift ;;
    --start)        ACTION="start"; shift ;;
    --stop)         ACTION="stop"; shift ;;
    --drop-slots)   ACTION="drop-slots"; shift ;;
    --restore-plan) ACTION="restore-plan"; shift ;;
    --service)      ONLY_SERVICE="$2"; shift 2 ;;
    --target-time)  TARGET_TIME="$2"; shift 2 ;;
    --archive)      ARCHIVE="$2"; shift 2 ;;
    --dry-run)      DRY_RUN=1; shift ;;
    --i-understand-this-ends-pitr) CONFIRM_DROP=1; shift ;;
    -h|--help)      sed -n '2,87p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "gmepay-pitr: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

log()  { printf '%s  %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die()  { echo "gmepay-pitr: $*" >&2; exit 1; }
run()  { if [ "$DRY_RUN" -eq 1 ]; then echo "DRY-RUN  $*"; else "$@"; fi }

container_for() { printf '%s-%s-1' "$PROJECT" "$1"; }
receiver_for()  { printf '%s-walrcv-%s' "$PROJECT" "$1"; }

# The PITR services, filtered by --service, validated against PG_INVENTORY so a
# typo cannot silently protect nothing.
pitr_list() {
  local svc found
  for svc in $PITR_SERVICES; do
    printf '%s\n' "$PG_INVENTORY" | grep -q "^$svc|" \
      || die "inventory.env: PITR_SERVICES names '$svc', which is not in PG_INVENTORY"
    if [ -n "$ONLY_SERVICE" ] && [ "$svc" != "$ONLY_SERVICE" ]; then continue; fi
    printf '%s\n' "$svc"
  done
}

db_of()    { printf '%s\n' "$PG_INVENTORY" | awk -F'|' -v s="$1" '$1==s{print $2}'; }
user_of()  { printf '%s\n' "$PG_INVENTORY" | awk -F'|' -v s="$1" '$1==s{print $3}'; }
owner_of() { printf '%s\n' "$PG_INVENTORY" | awk -F'|' -v s="$1" '$1==s{print $5}'; }
# docker-compose.yml names the data volume pg-<suffix> for postgres-<suffix>.
volume_of() { printf 'pg-%s' "${1#postgres-}"; }

# psql inside the target container. Read-only unless the SQL says otherwise.
psql_q() {
  local svc="$1" sql="$2"
  docker exec "$(container_for "$svc")" \
    psql -U "$(user_of "$svc")" -d "$(db_of "$svc")" -Atc "$sql" 2>/dev/null
}

require_docker() {
  command -v docker >/dev/null 2>&1 || { echo "gmepay-pitr: docker not found on PATH" >&2; exit 3; }
}

container_running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" = "true" ]; }

selected="$(pitr_list)"
[ -n "$selected" ] || die "no PITR services selected (--service '$ONLY_SERVICE' matched nothing)"

# ---------------------------------------------------------------------------
# init — one-time, online: verify the server can stream, create the slot, bound it
# ---------------------------------------------------------------------------
action_init() {
  require_docker
  local svc rc=0
  for svc in $selected; do
    local c; c="$(container_for "$svc")"
    container_running "$c" || { log "SKIP  $svc — container $c is not running"; rc=1; continue; }

    local wal_level senders
    wal_level="$(psql_q "$svc" "show wal_level" || true)"
    senders="$(psql_q "$svc" "show max_wal_senders" || true)"
    if [ "$wal_level" != "replica" ] && [ "$wal_level" != "logical" ]; then
      log "FAIL  $svc — wal_level=$wal_level cannot stream WAL (needs replica or logical)"
      rc=1; continue
    fi
    if [ "${senders:-0}" -lt 1 ]; then
      log "FAIL  $svc — max_wal_senders=$senders, no replication connection is possible"
      rc=1; continue
    fi

    # Bound the retention FIRST. Doing it after creating the slot leaves a window in
    # which an unbounded slot exists on a money-path cluster.
    log "$svc  set max_slot_wal_keep_size=$KEEP_SIZE (ALTER SYSTEM + reload, no restart)"
    run docker exec "$(container_for "$svc")" psql -U "$(user_of "$svc")" -d "$(db_of "$svc")" \
      -c "ALTER SYSTEM SET max_slot_wal_keep_size = '$KEEP_SIZE'" \
      -c "SELECT pg_reload_conf()" >/dev/null

    if [ "$(psql_q "$svc" "select count(*) from pg_replication_slots where slot_name='$SLOT'")" = "1" ]; then
      log "$svc  slot '$SLOT' already exists — nothing to do (idempotent)"
    else
      log "$svc  create physical replication slot '$SLOT'"
      run docker exec "$(container_for "$svc")" psql -U "$(user_of "$svc")" -d "$(db_of "$svc")" \
        -c "SELECT pg_create_physical_replication_slot('$SLOT', true)" >/dev/null
    fi
    run mkdir -p "$ARCHIVE/$svc/wal" "$ARCHIVE/$svc/base"
  done
  echo
  log "NEXT  run --basebackup, then --start. WAL without a base backup restores NOTHING."
  return $rc
}

# ---------------------------------------------------------------------------
# basebackup — the other half of PITR
# ---------------------------------------------------------------------------
action_basebackup() {
  require_docker
  local svc rc=0 ts; ts="$(date -u +%Y%m%dT%H%M%SZ)"
  for svc in $selected; do
    local c; c="$(container_for "$svc")"
    container_running "$c" || { log "SKIP  $svc — container $c is not running"; rc=1; continue; }
    local stage="$ARCHIVE/$svc/base/$ts.partial" final="$ARCHIVE/$svc/base/$ts"
    log "$svc  pg_basebackup -Ft -z -> $final"
    run mkdir -p "$stage"
    # -X none: the WAL needed to make this base consistent comes from the STREAM, which
    # is the whole point; -X stream here would also work but would duplicate segments.
    # --checkpoint=fast so an operator-triggered base backup does not wait for the next
    # scheduled checkpoint on a busy money DB.
    if run docker run --rm --network "$NETWORK" \
        -e PGPASSWORD="$PGPASS" \
        -v "$stage:/base" \
        "$IMAGE" \
        pg_basebackup -h "$svc" -p 5432 -U "$(user_of "$svc")" \
          -D /base -Ft -z -X none --checkpoint=fast --no-password --progress
    then
      run mv "$stage" "$final"
      run sh -c "printf 'base_backup_utc=%s\nservice=%s\ndatabase=%s\nslot=%s\n' \
        '$ts' '$svc' '$(db_of "$svc")' '$SLOT' > '$final/BASE_INFO.txt'"
      log "$svc  base backup OK ($ts)"
    else
      log "FAIL  $svc base backup — .partial kept at $stage"
      rc=1; continue
    fi
    # Retention: keep the newest N complete bases. Never prune .partial, and never
    # prune when this run failed (the `continue` above skips this).
    local n
    n="$(ls -1d "$ARCHIVE/$svc/base"/*Z 2>/dev/null | wc -l || echo 0)"
    if [ "$n" -gt "$KEEP_BASES" ]; then
      ls -1d "$ARCHIVE/$svc/base"/*Z | head -n "$((n - KEEP_BASES))" | while read -r old; do
        log "$svc  prune old base $(basename "$old")"
        run rm -rf "$old"
      done
    fi
  done
  echo
  log "NOTE  a base backup is only restorable together with the WAL from its moment"
  log "      onward. Do NOT prune $ARCHIVE/<svc>/wal past the OLDEST base you keep."
  return $rc
}

# ---------------------------------------------------------------------------
# start / stop the receivers
# ---------------------------------------------------------------------------
action_start() {
  require_docker
  local svc rc=0
  for svc in $selected; do
    local c r; c="$(container_for "$svc")"; r="$(receiver_for "$svc")"
    container_running "$c" || { log "SKIP  $svc — container $c is not running"; rc=1; continue; }
    if container_running "$r"; then
      log "$svc  receiver $r already running — nothing to do (idempotent)"
      continue
    fi
    if [ "$(psql_q "$svc" "select count(*) from pg_replication_slots where slot_name='$SLOT'")" != "1" ]; then
      log "FAIL  $svc — slot '$SLOT' does not exist. Run --init first."
      rc=1; continue
    fi
    if ! ls -1d "$ARCHIVE/$svc/base"/*Z >/dev/null 2>&1; then
      log "FAIL  $svc — no base backup in $ARCHIVE/$svc/base. Streaming WAL with no base"
      log "      would give you a directory that restores nothing. Run --basebackup first."
      rc=1; continue
    fi
    run docker rm -f "$r" >/dev/null 2>&1 || true
    run mkdir -p "$ARCHIVE/$svc/wal"
    log "$svc  start receiver $r -> $ARCHIVE/$svc/wal"
    # --synchronous: fsync each received chunk immediately, so the exposure is the
    #   in-flight record rather than a buffered segment.
    # -Z 6: compress completed segments (16 MB -> typically ~1-2 MB).
    # --no-loop is deliberately NOT used: on a transient disconnect the receiver must
    #   keep retrying, not exit and leave the slot unconsumed.
    # restart=unless-stopped: survives a Docker daemon restart. It does NOT survive
    #   `docker rm`; that is what gmepay-backup-check.sh watches for.
    run docker run -d --name "$r" --network "$NETWORK" \
      --restart unless-stopped \
      -e PGPASSWORD="$PGPASS" \
      -v "$ARCHIVE/$svc/wal:/wal" \
      "$IMAGE" \
      pg_receivewal -h "$svc" -p 5432 -U "$(user_of "$svc")" \
        -D /wal --slot "$SLOT" --synchronous -Z 6 --no-password >/dev/null
  done
  return $rc
}

action_stop() {
  require_docker
  local svc
  for svc in $selected; do
    local r; r="$(receiver_for "$svc")"
    if container_running "$r"; then
      log "$svc  stop + remove receiver $r"
      run docker rm -f "$r" >/dev/null
    else
      log "$svc  no running receiver"
    fi
  done
  echo
  log "WARNING  the replication slots still exist, so each primary now RETAINS WAL up to"
  log "         max_slot_wal_keep_size ($KEEP_SIZE) waiting for a receiver that is gone."
  log "         Restart the receivers (--start) or drop the slots (--drop-slots)."
}

action_drop_slots() {
  require_docker
  [ "$CONFIRM_DROP" -eq 1 ] || die "--drop-slots ENDS point-in-time recovery for $(echo "$selected" | tr '\n' ' ')
and makes the WAL already in $ARCHIVE a dead end (no new segments arrive). RPO returns to
24 h — the nightly dump. Re-run with --i-understand-this-ends-pitr if that is what you want."
  action_stop
  local svc
  for svc in $selected; do
    log "$svc  drop slot '$SLOT'"
    run docker exec "$(container_for "$svc")" psql -U "$(user_of "$svc")" -d "$(db_of "$svc")" \
      -c "SELECT pg_drop_replication_slot('$SLOT') WHERE EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name='$SLOT')" >/dev/null || true
  done
  log "RPO for these clusters is now 24 h again (nightly logical dump only)."
}

# ---------------------------------------------------------------------------
# status — the honest answer to "do we have PITR right now?"
# ---------------------------------------------------------------------------
# Age of the most recently WRITTEN file in the WAL directory, INCLUDING the in-progress
# `.partial` segment. That inclusion matters: pg_receivewal only closes a segment when
# 16 MB have been produced, so on a quiet cluster the newest COMPLETE segment can be
# hours old while streaming is perfectly healthy. The .partial's mtime is the liveness
# signal; a stale one means the stream has actually stopped.
newest_wal_age_secs() {
  local dir="$1" newest
  newest="$(ls -1t "$dir" 2>/dev/null | head -1 || true)"
  [ -n "$newest" ] || { echo ""; return; }
  echo $(( $(date +%s) - $(date -r "$dir/$newest" +%s) ))
}

action_status() {
  require_docker
  local svc rc=0
  printf '%-22s %-9s %-9s %-11s %-13s %s\n' SERVICE SLOT RECEIVER "NEWEST-WAL" "SLOT-LAG" "NEWEST-BASE"
  for svc in $selected; do
    local c r slot_state recv base_dir base newest_age lag base_age
    c="$(container_for "$svc")"; r="$(receiver_for "$svc")"

    if container_running "$c"; then
      case "$(psql_q "$svc" "select coalesce((select case when active then 'active' else 'INACTIVE' end from pg_replication_slots where slot_name='$SLOT'),'MISSING')" || echo '?')" in
        active)   slot_state="active" ;;
        INACTIVE) slot_state="INACTIVE"; rc=1 ;;
        MISSING)  slot_state="MISSING"; rc=1 ;;
        *)        slot_state="?"; rc=1 ;;
      esac
      lag="$(psql_q "$svc" "select coalesce(pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)),'-') from pg_replication_slots where slot_name='$SLOT'" || true)"
      [ -n "$lag" ] || lag="-"
    else
      slot_state="db-down"; lag="-"; rc=1
    fi

    if container_running "$r"; then recv="up"; else recv="DOWN"; rc=1; fi

    newest_age="$(newest_wal_age_secs "$ARCHIVE/$svc/wal")"
    if [ -z "$newest_age" ]; then newest_age="none"; rc=1; else newest_age="${newest_age}s"; fi

    base_dir="$ARCHIVE/$svc/base"
    base="$(ls -1d "$base_dir"/*Z 2>/dev/null | tail -1 || true)"
    if [ -n "$base" ]; then base_age="$(basename "$base")"; else base_age="NONE"; rc=1; fi

    printf '%-22s %-9s %-9s %-11s %-13s %s\n' "$svc" "$slot_state" "$recv" "$newest_age" "$lag" "$base_age"
  done
  echo
  if [ $rc -eq 0 ]; then
    log "PITR OK for: $(echo "$selected" | tr '\n' ' ')"
    log "RPO for these clusters is bounded by SLOT-LAG above, not by the nightly dump."
  else
    log "PITR is NOT fully working (see the rows above)."
    log "  MISSING slot      -> --init"
    log "  NONE base         -> --basebackup   (WAL alone restores nothing)"
    log "  DOWN receiver     -> --start        (and the primary is retaining WAL meanwhile)"
    log "  INACTIVE slot     -> the receiver is not connected; check the receiver's logs:"
    log "                       docker logs $(receiver_for '<service>')"
    log "  growing SLOT-LAG  -> the primary cannot free WAL. Past $KEEP_SIZE it will"
    log "                       INVALIDATE the slot to protect itself; PITR continuity ends there."
  fi
  return $rc
}

# ---------------------------------------------------------------------------
# restore-plan — prints, never executes
# ---------------------------------------------------------------------------
action_restore_plan() {
  [ -n "$TARGET_TIME" ] || die "--restore-plan needs --target-time '<timestamp with tz>', e.g. '2026-07-28 14:05:00+09'"
  cat <<PLAN
============================================================================
PITR RESTORE PLAN — recovery target time: $TARGET_TIME
============================================================================
This is a PLAN. Nothing below has been executed. Read it, then run it by hand
with an owner on the call (Documentation/RUNBOOK_BACKUP_DR.md §5).

THIS IS ALSO THE CONSISTENT-SNAPSHOT PATH. The nightly logical dumps are taken
sequentially and are NOT mutually consistent; restoring the same target time on
every cluster below IS a common recovery point, so cross-DB invariants (a payment
in txndb has its execution attempt in executor, its hold in prefunding and its
posting in ledger) hold at $TARGET_TIME. That guarantee covers ONLY the clusters
listed here. The other ten remain dump-only and need the reconcile procedure in
runbook §5.5.

Pick the target time BEFORE the damage (a bad deploy, a wrong batch, a
DROP TABLE), not after. Recovery cannot skip past an event and keep what came
after it.

PLAN
  local svc
  for svc in $selected; do
    local base; base="$(ls -1d "$ARCHIVE/$svc/base"/*Z 2>/dev/null | tail -1 || true)"
    cat <<PLAN
----------------------------------------------------------------------------
$svc  (database $(db_of "$svc"), owner $(owner_of "$svc"))
----------------------------------------------------------------------------
  base backup to use : ${base:-*** NONE PRESENT — THIS CLUSTER CANNOT BE PITR-RESTORED ***}
  WAL directory      : $ARCHIVE/$svc/wal

  # 1. Stop the owning service so nothing writes during recovery.
  docker compose stop $(owner_of "$svc")

  # 2. Stop the receiver for this cluster (it must not stream into a cluster you
  #    are about to replace) but leave the slot alone until recovery succeeds.
  docker rm -f $(receiver_for "$svc")

  # 3. Stop the database and move PGDATA aside — do NOT delete it. It is the only
  #    copy of anything written after the last streamed segment.
  docker compose stop $svc
  # PGDATA lives in the compose volume ${PROJECT}_$(volume_of "$svc"). Rename it, do not
  # remove it — if recovery goes wrong this is the only copy of the tail of the WAL.
  docker run --rm -v ${PROJECT}_$(volume_of "$svc"):/pgdata "$IMAGE" \\
    sh -c 'mkdir -p /pgdata.broken && mv /pgdata/* /pgdata.broken/'

  # 4. Restore the base tarball into an empty PGDATA (base.tar.gz -> PGDATA root;
  #    tablespace tarballs, if any, by OID). Mount $ARCHIVE/$svc/wal read-only into the
  #    container as /wal. Then in postgresql.auto.conf:
  #
  #      # THE SEGMENTS ARE GZIPPED (pg_receivewal -Z 6), so a plain cp finds nothing:
  #      restore_command = 'gunzip -c /wal/%f.gz > %p'
  #      recovery_target_time = '$TARGET_TIME'
  #      recovery_target_action = 'promote'
  #      recovery_target_inclusive = on
  #
  #    and create the marker file  \$PGDATA/recovery.signal  (empty).
  #    (PG12+: recovery settings live in postgresql.conf / .auto.conf. A recovery.conf
  #     makes PG16 REFUSE TO START — it is an error, not a legacy fallback.)
  #
  #    THE TAIL OF THE STREAM: the newest segment is still open as
  #    <segment>.gz.partial. Recovery will not read a .partial. To recover the last
  #    few minutes, copy it aside and drop the .partial suffix:
  #      cp $ARCHIVE/$svc/wal/*.gz.partial /tmp/wal/  &&  rename 's/\.partial\$//' /tmp/wal/*
  #    A truncated final record is harmless — recovery simply stops at the last
  #    complete one, which is exactly the RPO boundary.

  # 5. Start the cluster and WATCH the log until it reports the recovery target
  #    reached and the promotion. Do not let the application connect before then.
  docker compose up -d $svc
  docker compose logs -f $svc

  # 6. Verify: the newest row in the main table predates $TARGET_TIME and the
  #    business invariants hold. THEN start the service and re-run --init/--start
  #    (a promoted cluster has a new timeline; the old slot's WAL does not apply).
PLAN
  done
  cat <<'PLAN'
============================================================================
AFTER a PITR restore, ALWAYS:
  * re-run gmepay-pitr.sh --init --basebackup --start for the restored clusters.
    A promotion starts a NEW TIMELINE; the pre-restore WAL cannot be replayed onto
    it, so the old archive is history and a fresh base backup is mandatory.
  * work through runbook §5.5 (reconcile against the scheme, check executor
    idempotency keys BEFORE any retry, notify Compliance if a filing period moved).
  * record the measured duration in the runbook's drill log — that is what turns an
    estimated RTO into a real one.
============================================================================
PLAN
}

case "$ACTION" in
  status)       action_status ;;
  init)         action_init ;;
  basebackup)   action_basebackup ;;
  start)        action_start ;;
  stop)         action_stop ;;
  drop-slots)   action_drop_slots ;;
  restore-plan) action_restore_plan ;;
  *) die "unhandled action '$ACTION'" ;;
esac
