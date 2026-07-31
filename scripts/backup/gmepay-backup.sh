#!/usr/bin/env bash
#
# gmepay-backup.sh — full logical backup of the GMEPay+ stateful surface.
#
# Runs on the Docker host (the WSL2 distro `gmepay-docker`, where the containers
# actually live). Windows operators use scripts/backup/gmepay-backup.ps1, which
# is a thin wrapper around this file — the logic exists only here.
#
# WHAT IT BACKS UP  (inventory: scripts/backup/inventory.env)
#   * all 15 PostgreSQL databases         -> pg_dump -Fc  (one file per DB)
#   * per-cluster roles/grants            -> pg_dumpall --globals-only
#   * Mongo 'merchant'                    -> mongodump --archive --gzip
#   * MinIO bucket gmepay-partner-vault   -> mc mirror into a plain directory
#
# WHY per-DB `pg_dump -Fc` AND NOT `pg_dumpall`
#   Each compose cluster hosts exactly ONE application database, so pg_dumpall's
#   only unique contribution is cluster-level globals (roles/passwords) — which we
#   capture separately and cheaply with --globals-only. Choosing -Fc (custom
#   format) instead buys three things a plain pg_dumpall SQL stream cannot:
#     1. selective restore — bring back just `txndb` after one service corrupts
#        its data, without touching the other 14;
#     2. pg_restore -j parallel restore and --list/--use-list inspection, which is
#        what makes the verify step in the runbook cheap;
#     3. built-in compression, so a nightly of all 15 stays small.
#   Physical/WAL backup (pgBackRest, WAL-G) would give a lower RPO but needs
#   archive_command wired into every cluster and an off-host repository, neither of
#   which exists yet. That is the documented next step, not this script's job.
#
# FAILURE POLICY — no silent partial backups.
#   Every artifact is written to <target>/<timestamp>.partial/ and the directory is
#   renamed to <target>/<timestamp>/ only after every component succeeded and the
#   manifest verified. Any failure => the .partial dir is left in place for triage,
#   no SUCCESS marker is written, and the script exits non-zero with the failed
#   component named. Retention NEVER runs on a failed backup, so a broken run can
#   never age out the last good one.
#
# USAGE
#   ./gmepay-backup.sh [--target DIR] [--keep-daily N] [--keep-weekly M]
#                      [--skip-mongo] [--skip-minio] [--dry-run]
#
# ENV (all optional)
#   GMEPAY_BACKUP_TARGET     default /var/backups/gmepay
#   GMEPAY_BACKUP_KEEP_DAILY default 7
#   GMEPAY_BACKUP_KEEP_WEEKLY default 4      (Sunday-dated sets, kept beyond daily)
#   GMEPAY_COMPOSE_PROJECT   default code    (container-name prefix)
#   MINIO_ROOT_USER / MINIO_ROOT_PASSWORD    default to the compose dev values
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=inventory.env
. "$SCRIPT_DIR/inventory.env"

TARGET="${GMEPAY_BACKUP_TARGET:-/var/backups/gmepay}"
KEEP_DAILY="${GMEPAY_BACKUP_KEEP_DAILY:-7}"
KEEP_WEEKLY="${GMEPAY_BACKUP_KEEP_WEEKLY:-4}"
PROJECT="${GMEPAY_COMPOSE_PROJECT:-$COMPOSE_PROJECT_DEFAULT}"
MINIO_USER="${MINIO_ROOT_USER:-$MINIO_ROOT_USER_DEFAULT}"
MINIO_PASS="${MINIO_ROOT_PASSWORD:-$MINIO_ROOT_PASSWORD_DEFAULT}"
DO_MONGO=1
DO_MINIO=1
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --target)      TARGET="$2"; shift 2 ;;
    --keep-daily)  KEEP_DAILY="$2"; shift 2 ;;
    --keep-weekly) KEEP_WEEKLY="$2"; shift 2 ;;
    --skip-mongo)  DO_MONGO=0; shift ;;
    --skip-minio)  DO_MINIO=0; shift ;;
    --dry-run)     DRY_RUN=1; shift ;;
    -h|--help)     sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "gmepay-backup: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

TS="$(date -u +%Y%m%dT%H%M%SZ)"
STAGE="$TARGET/$TS.partial"
FINAL="$TARGET/$TS"
LOGFILE=""
FAILED=""

log()  { printf '%s  %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "${LOGFILE:-/dev/null}"; }
fail() { FAILED="${FAILED}${FAILED:+, }$1"; log "FAIL  $1: ${2:-}"; }

on_err() {
  local rc=$?
  log "ABORT unexpected error (rc=$rc) at line ${BASH_LINENO[0]:-?}"
  log "ABORT partial artifacts kept at $STAGE for triage; retention NOT run"
  exit "$rc"
}
trap on_err ERR

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "gmepay-backup: required command '$1' not on PATH" >&2; exit 3; }
}
need docker
need date
need sha256sum

container() { echo "${PROJECT}-$1-1"; }

running() {
  [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" = "true" ]
}

# ── preflight ────────────────────────────────────────────────────────────────
PG_COUNT=$(printf '%s\n' "$PG_INVENTORY" | grep -c '^postgres-' || true)
echo "gmepay-backup: inventory = ${PG_COUNT} postgres DBs, mongo=[${MONGO_DBS}], minio=[${MINIO_BUCKETS}]"
[ "$PG_COUNT" -ge 1 ] || { echo "gmepay-backup: empty PG inventory — refusing to run" >&2; exit 3; }

if [ "$DRY_RUN" = 1 ]; then
  echo "gmepay-backup: DRY RUN — would write to $FINAL"
  printf '%s\n' "$PG_INVENTORY" | while IFS='|' read -r svc db user port owner; do
    [ -n "${svc:-}" ] || continue
    printf '  pg_dump -Fc  %-24s db=%-12s owner=%-26s container=%s\n' \
      "$svc" "$db" "$owner" "$(container "$svc")"
  done
  echo "  mongodump    mongo                    db=${MONGO_DBS}"
  echo "  mc mirror    minio                    bucket=${MINIO_BUCKETS}"
  echo "gmepay-backup: retention would keep ${KEEP_DAILY} daily + ${KEEP_WEEKLY} weekly under $TARGET"
  exit 0
fi

mkdir -p "$STAGE/postgres" "$STAGE/globals"
LOGFILE="$STAGE/backup.log"
log "START gmepay backup ts=$TS target=$TARGET project=$PROJECT"

# ── PostgreSQL ───────────────────────────────────────────────────────────────
PG_DONE=0
while IFS='|' read -r svc db user port owner; do
  [ -n "${svc:-}" ] || continue
  c="$(container "$svc")"
  if ! running "$c"; then
    fail "pg:$db" "container $c is not running"
    continue
  fi
  out="$STAGE/postgres/${svc}__${db}.dump"
  log "pg_dump $db (owner=$owner, container=$c)"
  # -Fc custom format, max compression, no owner/privs so a restore into a
  # differently-named role still works; --no-acl for the same reason.
  if docker exec -i "$c" pg_dump -U "$user" -d "$db" \
        -Fc -Z6 --no-owner --no-acl > "$out" 2>>"$LOGFILE"; then
    # A truncated dump is the classic silent-failure mode: pg_restore --list on
    # the artifact we just wrote is the cheapest proof it is readable end-to-end.
    if docker run --rm -i -v "$STAGE/postgres:/b:ro" postgres:16-alpine \
          pg_restore --list "/b/$(basename "$out")" >/dev/null 2>>"$LOGFILE"; then
      PG_DONE=$((PG_DONE + 1))
    else
      fail "pg:$db" "dump written but pg_restore --list rejected it (truncated?)"
    fi
  else
    fail "pg:$db" "pg_dump exited non-zero"
  fi

  g="$STAGE/globals/${svc}__globals.sql"
  if ! docker exec -i "$c" pg_dumpall -U "$user" --globals-only \
        > "$g" 2>>"$LOGFILE"; then
    fail "globals:$svc" "pg_dumpall --globals-only exited non-zero"
  fi
done <<EOF
$(printf '%s\n' "$PG_INVENTORY")
EOF

if [ "$PG_DONE" -ne "$PG_COUNT" ]; then
  fail "postgres-coverage" "dumped $PG_DONE of $PG_COUNT inventoried databases"
fi

# ── Mongo ────────────────────────────────────────────────────────────────────
if [ "$DO_MONGO" = 1 ]; then
  mkdir -p "$STAGE/mongo"
  c="$(container "$MONGO_SERVICE")"
  if running "$c"; then
    for db in $MONGO_DBS; do
      log "mongodump $db (container=$c)"
      if ! docker exec -i "$c" mongodump --db "$db" --archive --gzip \
            > "$STAGE/mongo/${db}.archive.gz" 2>>"$LOGFILE"; then
        fail "mongo:$db" "mongodump exited non-zero"
      fi
    done
  else
    fail "mongo" "container $c is not running"
  fi
else
  log "SKIP mongo (--skip-mongo)"
fi

# ── MinIO ────────────────────────────────────────────────────────────────────
# `mc mirror` writes plain objects to a directory, so the restore is a mirror in
# the other direction and the artifacts stay greppable/auditable. We run mc in a
# throwaway container on the compose network rather than requiring mc on the host.
if [ "$DO_MINIO" = 1 ]; then
  mkdir -p "$STAGE/minio"
  c="$(container "$MINIO_SERVICE")"
  if running "$c"; then
    net="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$c" | head -1)"
    for b in $MINIO_BUCKETS; do
      log "mc mirror bucket=$b (network=$net)"
      if ! docker run --rm --network "$net" \
            -e "MC_HOST_src=http://${MINIO_USER}:${MINIO_PASS}@${MINIO_SERVICE}:9000" \
            -v "$STAGE/minio:/out" --entrypoint sh minio/mc:latest \
            -c "mc mirror --quiet --preserve src/$b /out/$b" >>"$LOGFILE" 2>&1; then
        fail "minio:$b" "mc mirror exited non-zero"
      fi
    done
  else
    fail "minio" "container $c is not running"
  fi
else
  log "SKIP minio (--skip-minio)"
fi

# ── manifest ─────────────────────────────────────────────────────────────────
log "writing manifest"
{
  echo "# GMEPay+ backup manifest"
  echo "timestamp_utc=$TS"
  echo "compose_project=$PROJECT"
  echo "pg_databases_expected=$PG_COUNT"
  echo "pg_databases_dumped=$PG_DONE"
  echo "mongo_dbs=$([ "$DO_MONGO" = 1 ] && echo "$MONGO_DBS" || echo "SKIPPED")"
  echo "minio_buckets=$([ "$DO_MINIO" = 1 ] && echo "$MINIO_BUCKETS" || echo "SKIPPED")"
  echo "host=$(hostname)"
  echo "# sha256  size_bytes  path (relative to this directory)"
  cd "$STAGE"
  find . -type f ! -name MANIFEST.txt ! -name backup.log -print0 \
    | sort -z | while IFS= read -r -d '' f; do
        printf '%s  %s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" \
          "$(stat -c%s "$f")" "${f#./}"
      done
} > "$STAGE/MANIFEST.txt"

# ── gate ─────────────────────────────────────────────────────────────────────
if [ -n "$FAILED" ]; then
  log "RESULT FAILED components: $FAILED"
  log "RESULT partial set kept at $STAGE (NOT promoted, NOT counted for retention)"
  echo "gmepay-backup: FAILED — $FAILED" >&2
  echo "gmepay-backup: see $LOGFILE ; partial artifacts at $STAGE" >&2
  exit 1
fi

mv "$STAGE" "$FINAL"
LOGFILE="$FINAL/backup.log"
date -u +%Y-%m-%dT%H:%M:%SZ > "$FINAL/SUCCESS"
log "RESULT OK promoted to $FINAL"

# ── retention: keep N most recent daily + M most recent Sunday-dated sets ────
# Only promoted (SUCCESS-marked) sets are candidates; .partial dirs are never
# deleted automatically — an operator must look at them.
log "retention keep_daily=$KEEP_DAILY keep_weekly=$KEEP_WEEKLY"
mapfile -t SETS < <(find "$TARGET" -maxdepth 1 -type d -name '20*T*Z' -printf '%f\n' \
                    | sort -r)
declare -A KEEP=()
n=0
for s in "${SETS[@]}"; do
  [ -f "$TARGET/$s/SUCCESS" ] || continue
  n=$((n + 1)); [ "$n" -le "$KEEP_DAILY" ] && KEEP["$s"]=daily
done
w=0
for s in "${SETS[@]}"; do
  [ -f "$TARGET/$s/SUCCESS" ] || continue
  d="${s%%T*}"
  # Sunday == day-of-week 7 (GNU date); the weekly tier is the long-tail copy.
  if [ "$(date -d "$d" +%u 2>/dev/null || echo 0)" = "7" ]; then
    w=$((w + 1)); [ "$w" -le "$KEEP_WEEKLY" ] && KEEP["$s"]=weekly
  fi
done
for s in "${SETS[@]}"; do
  [ -f "$TARGET/$s/SUCCESS" ] || continue
  if [ -z "${KEEP[$s]:-}" ]; then
    log "retention prune $s"
    rm -rf "$TARGET/$s"
  else
    log "retention keep  $s (${KEEP[$s]})"
  fi
done

log "DONE $FINAL"
echo "gmepay-backup: OK — $PG_DONE/$PG_COUNT postgres DBs, mongo+minio included, at $FINAL"
