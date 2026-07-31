#!/usr/bin/env bash
#
# gmepay-restore.sh — restore one, several, or all GMEPay+ databases from a
# backup set produced by gmepay-backup.sh.
#
# THIS SCRIPT DESTROYS DATA. Three independent guards must all be satisfied:
#   1. --set <timestamp>            an explicit backup set (never "the latest")
#   2. --db <svc|all>               an explicit scope
#   3. --i-understand-this-destroys-data   plus, for `--db all`, typing the
#                                   literal word RESTORE-ALL when prompted
# With no TTY (cron, CI) the typed prompt cannot be satisfied, so an unattended
# caller can never restore everything by accident. Set
# GMEPAY_RESTORE_CONFIRM=RESTORE-ALL only inside a deliberate DR runbook step.
#
# USAGE
#   ./gmepay-restore.sh --list
#   ./gmepay-restore.sh --set 20260728T020000Z --db postgres-txn --i-understand-this-destroys-data
#   ./gmepay-restore.sh --set 20260728T020000Z --db all           --i-understand-this-destroys-data
#   ./gmepay-restore.sh --set 20260728T020000Z --db postgres-txn --verify-only
#
# --verify-only is the NON-DESTRUCTIVE path used by the runbook's verification
# step: it restores the artifact into a scratch database (`<db>_verify_<ts>`) in
# the same cluster, prints the table and row counts, then drops the scratch DB.
# The live database is never touched.
#
# Mongo and MinIO are restored with --db mongo / --db minio.
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=inventory.env
. "$SCRIPT_DIR/inventory.env"

TARGET="${GMEPAY_BACKUP_TARGET:-/var/backups/gmepay}"
PROJECT="${GMEPAY_COMPOSE_PROJECT:-$COMPOSE_PROJECT_DEFAULT}"
MINIO_USER="${MINIO_ROOT_USER:-$MINIO_ROOT_USER_DEFAULT}"
MINIO_PASS="${MINIO_ROOT_PASSWORD:-$MINIO_ROOT_PASSWORD_DEFAULT}"
SET=""
SCOPE=""
CONFIRMED=0
VERIFY_ONLY=0
DO_LIST=0
JOBS="${GMEPAY_RESTORE_JOBS:-2}"

while [ $# -gt 0 ]; do
  case "$1" in
    --set)    SET="$2"; shift 2 ;;
    --db)     SCOPE="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    --jobs)   JOBS="$2"; shift 2 ;;
    --list)   DO_LIST=1; shift ;;
    --verify-only) VERIFY_ONLY=1; shift ;;
    --i-understand-this-destroys-data) CONFIRMED=1; shift ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "gmepay-restore: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

die()  { echo "gmepay-restore: $*" >&2; exit 1; }
log()  { printf '%s  %s\n' "$(date -u +%H:%M:%S)" "$*"; }
container() { echo "${PROJECT}-$1-1"; }
running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" = "true" ]; }

command -v docker >/dev/null 2>&1 || die "docker not on PATH"

if [ "$DO_LIST" = 1 ]; then
  echo "backup sets under $TARGET:"
  found=0
  for d in "$TARGET"/20*T*Z; do
    [ -d "$d" ] || continue
    found=1
    if [ -f "$d/SUCCESS" ]; then st="OK      "; else st="INCOMPLETE"; fi
    n=$(find "$d/postgres" -name '*.dump' 2>/dev/null | wc -l)
    printf '  %s  %s  %s postgres dumps  %s\n' "$st" "$(basename "$d")" "$n" \
      "$(du -sh "$d" 2>/dev/null | cut -f1)"
  done
  for d in "$TARGET"/*.partial; do
    [ -d "$d" ] || continue
    found=1
    printf '  FAILED    %s  (partial — triage, do not restore from this)\n' "$(basename "$d")"
  done
  [ "$found" = 1 ] || echo "  (none)"
  exit 0
fi

[ -n "$SET" ]   || die "--set <timestamp> is required (see --list). There is deliberately no 'latest' shortcut."
[ -n "$SCOPE" ] || die "--db <compose-service|all|mongo|minio> is required"
SRC="$TARGET/$SET"
[ -d "$SRC" ]   || die "backup set not found: $SRC"

if [ "$VERIFY_ONLY" = 0 ]; then
  [ -f "$SRC/SUCCESS" ] || die "$SET has no SUCCESS marker — it is an incomplete backup. Refusing to restore. Use --verify-only to inspect it."
  [ "$CONFIRMED" = 1 ]  || die "refusing to overwrite live data without --i-understand-this-destroys-data"
fi

# ── guard 3: typed confirmation for a full-fleet restore ─────────────────────
if [ "$VERIFY_ONLY" = 0 ] && [ "$SCOPE" = "all" ]; then
  want="RESTORE-ALL"
  got="${GMEPAY_RESTORE_CONFIRM:-}"
  if [ -z "$got" ]; then
    [ -t 0 ] || die "--db all needs an interactive TTY (or GMEPAY_RESTORE_CONFIRM=$want). Refusing."
    echo "About to OVERWRITE ALL $(printf '%s\n' "$PG_INVENTORY" | grep -c '^postgres-') GMEPay+ databases from $SET."
    printf 'Type %s to proceed: ' "$want"
    read -r got
  fi
  [ "$got" = "$want" ] || die "confirmation mismatch — nothing was changed"
fi

pg_row() {  # $1 = compose service -> prints "db|user"
  printf '%s\n' "$PG_INVENTORY" | while IFS='|' read -r svc db user port owner; do
    [ "${svc:-}" = "$1" ] && echo "$db|$user"
  done
}

restore_pg() {
  local svc="$1" db user c dump
  IFS='|' read -r db user <<<"$(pg_row "$svc")"
  [ -n "${db:-}" ] || die "'$svc' is not in scripts/backup/inventory.env"
  c="$(container "$svc")"
  running "$c" || die "container $c is not running"
  dump="$SRC/postgres/${svc}__${db}.dump"
  [ -f "$dump" ] || die "no dump for $svc in $SET (expected $dump)"

  if [ "$VERIFY_ONLY" = 1 ]; then
    local scratch="${db}_verify_$(date -u +%H%M%S)"
    log "VERIFY restoring $svc/$db into scratch DB $scratch (live DB untouched)"
    docker exec -i "$c" createdb -U "$user" "$scratch"
    # shellcheck disable=SC2002
    if cat "$dump" | docker exec -i "$c" pg_restore -U "$user" -d "$scratch" \
         --no-owner --no-acl -j "$JOBS" 2>&1 | grep -v '^$' | tail -5; then :; fi
    echo "--- tables + row counts in $scratch ---"
    docker exec -i "$c" psql -U "$user" -d "$scratch" -Atc "
      SELECT c.relname || ' = ' || c.reltuples::bigint
      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE c.relkind='r' AND n.nspname='public' ORDER BY c.relname;"
    local tables
    tables=$(docker exec -i "$c" psql -U "$user" -d "$scratch" -Atc \
      "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
    docker exec -i "$c" dropdb -U "$user" "$scratch"
    log "VERIFY $svc/$db OK — $tables tables restored, scratch DB dropped"
    [ "$tables" -gt 0 ] || die "verify FAILED: $svc/$db restored 0 tables"
    return 0
  fi

  log "RESTORE $svc/$db — dropping and recreating the live database"
  # --clean --if-exists inside a single pg_restore is not enough when objects were
  # added since the dump; recreating the database is the only deterministic reset.
  docker exec -i "$c" psql -U "$user" -d postgres -c \
    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$db' AND pid<>pg_backend_pid();" >/dev/null
  docker exec -i "$c" dropdb -U "$user" --if-exists "$db"
  docker exec -i "$c" createdb -U "$user" "$db"
  # shellcheck disable=SC2002
  cat "$dump" | docker exec -i "$c" pg_restore -U "$user" -d "$db" \
    --no-owner --no-acl -j "$JOBS"
  log "RESTORE $svc/$db done — restart the owning service so Flyway re-validates"
}

restore_mongo() {
  local c="$(container "$MONGO_SERVICE")" db a
  running "$c" || die "container $c is not running"
  for db in $MONGO_DBS; do
    a="$SRC/mongo/${db}.archive.gz"
    [ -f "$a" ] || die "no mongo archive for '$db' in $SET"
    if [ "$VERIFY_ONLY" = 1 ]; then
      log "VERIFY mongo $db — listing archive contents only"
      # shellcheck disable=SC2002
      cat "$a" | docker exec -i "$c" mongorestore --archive --gzip --dryRun \
        --nsInclude "$db.*" 2>&1 | tail -10
      continue
    fi
    log "RESTORE mongo $db (--drop)"
    # shellcheck disable=SC2002
    cat "$a" | docker exec -i "$c" mongorestore --archive --gzip --drop \
      --nsInclude "$db.*"
  done
}

restore_minio() {
  local c="$(container "$MINIO_SERVICE")" net b
  running "$c" || die "container $c is not running"
  net="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$c" | head -1)"
  for b in $MINIO_BUCKETS; do
    [ -d "$SRC/minio/$b" ] || die "no minio mirror for bucket '$b' in $SET"
    if [ "$VERIFY_ONLY" = 1 ]; then
      log "VERIFY minio $b — $(find "$SRC/minio/$b" -type f | wc -l) objects in the mirror"
      continue
    fi
    log "RESTORE minio bucket $b (mirror back; existing objects under object-lock retention CANNOT be overwritten)"
    docker run --rm --network "$net" \
      -e "MC_HOST_dst=http://${MINIO_USER}:${MINIO_PASS}@${MINIO_SERVICE}:9000" \
      -v "$SRC/minio:/in:ro" --entrypoint sh minio/mc:latest \
      -c "mc mb --ignore-existing dst/$b && mc mirror --preserve /in/$b dst/$b"
  done
}

case "$SCOPE" in
  all)
    while IFS='|' read -r svc db user port owner; do
      [ -n "${svc:-}" ] || continue
      restore_pg "$svc"
    done <<EOF
$(printf '%s\n' "$PG_INVENTORY")
EOF
    [ -d "$SRC/mongo" ] && restore_mongo
    [ -d "$SRC/minio" ] && restore_minio
    ;;
  mongo) restore_mongo ;;
  minio) restore_minio ;;
  *)     restore_pg "$SCOPE" ;;
esac

if [ "$VERIFY_ONLY" = 1 ]; then
  echo "gmepay-restore: VERIFY complete for $SET scope=$SCOPE — nothing was modified"
else
  echo "gmepay-restore: RESTORE complete for $SET scope=$SCOPE"
  echo "gmepay-restore: NEXT — restart the affected services (docker compose restart <svc>) and"
  echo "                 re-run reconciliation; Kafka offsets were NOT restored (see runbook §7)."
fi
