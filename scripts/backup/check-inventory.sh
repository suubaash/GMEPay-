#!/usr/bin/env bash
#
# check-inventory.sh — static guard: every postgres service in docker-compose.yml
# is present in scripts/backup/inventory.env with the right database name, and
# vice versa. Connects to nothing and starts nothing; safe in CI.
#
# Exit 0 = in sync. Exit 1 = drift (a DB would be silently missed by the backup).
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
COMPOSE="$REPO_ROOT/docker-compose.yml"
# shellcheck source=inventory.env
. "$SCRIPT_DIR/inventory.env"

[ -f "$COMPOSE" ] || { echo "check-inventory: $COMPOSE not found" >&2; exit 2; }

# compose truth: "<service> <db>" for each `postgres-*:` block followed by POSTGRES_DB
compose_pairs="$(awk '
  /^  postgres-[a-z0-9-]+:[[:space:]]*$/ { svc=$1; sub(/:$/,"",svc); next }
  svc != "" && /POSTGRES_DB:[[:space:]]*[a-z0-9_]+/ {
    match($0, /POSTGRES_DB:[[:space:]]*[a-z0-9_]+/)
    s = substr($0, RSTART, RLENGTH); sub(/POSTGRES_DB:[[:space:]]*/, "", s)
    print svc, s; svc=""
  }
' "$COMPOSE" | sort)"

inv_pairs="$(printf '%s\n' "$PG_INVENTORY" \
  | awk -F'|' 'NF>=2 { print $1, $2 }' | sort)"

nc=$(printf '%s\n' "$compose_pairs" | grep -c . || true)
ni=$(printf '%s\n' "$inv_pairs" | grep -c . || true)
echo "check-inventory: docker-compose.yml has $nc postgres services; inventory.env has $ni"

if [ "$compose_pairs" = "$inv_pairs" ]; then
  echo "check-inventory: OK — inventory matches docker-compose.yml exactly"
  exit 0
fi

echo "check-inventory: DRIFT DETECTED" >&2
echo "--- in docker-compose.yml but NOT backed up (or db name differs) ---" >&2
comm -23 <(printf '%s\n' "$compose_pairs") <(printf '%s\n' "$inv_pairs") >&2
echo "--- in inventory.env but NOT in docker-compose.yml ---" >&2
comm -13 <(printf '%s\n' "$compose_pairs") <(printf '%s\n' "$inv_pairs") >&2
echo "check-inventory: fix scripts/backup/inventory.env" >&2
exit 1
