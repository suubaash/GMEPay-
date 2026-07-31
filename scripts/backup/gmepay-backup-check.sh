#!/usr/bin/env bash
#
# gmepay-backup-check.sh — "is the backup capability ACTUALLY running, and how old is
# the newest thing we could restore from?"  Gap T3-8.
#
# ============================================================================
# WHY THIS EXISTS
# ============================================================================
# T3-1 built a backup capability. T3-8 recorded that nothing was scheduled and no
# drill had run. The failure mode those two facts create is the dangerous one: a repo
# full of backup scripts, a runbook that describes them, and an RPO of "however long
# ago someone last ran it by hand" — which nobody can state, so everyone assumes it is
# fine. A backup capability nobody can prove is running is not a capability; it is a
# document. This script is the proof, and it is designed to be run by a scheduler and
# judged by its EXIT CODE.
#
#   exit 0  OK       everything checked is present and fresh
#   exit 1  WARN     degraded but recoverable (e.g. no off-host copy, PITR partial)
#   exit 2  CRITICAL the RPO is not what the runbook claims (no schedule, stale or
#                    missing artifacts, no base backup) — treat as page-worthy
#   exit 3  cannot check (target unreadable)
#
# It reads only: the backup target directory, the off-host directory, the installed
# schedule, and (if docker is present) the PITR receiver/slot state. It NEVER runs a
# backup, touches a database, or starts a container.
#
# USAGE
#   ./gmepay-backup-check.sh [--target DIR] [--offhost DIR] [--max-age-hours N]
#                            [--verify-checksums] [--skip-pitr] [--quiet]
#
# ENV
#   GMEPAY_BACKUP_TARGET      default /var/backups/gmepay
#   GMEPAY_BACKUP_OFFHOST     default (unset -> the off-host check WARNs)
#   GMEPAY_PITR_ARCHIVE       default from inventory.env
#   GMEPAY_COMPOSE_PROJECT    default code
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=inventory.env
. "$SCRIPT_DIR/inventory.env"

TARGET="${GMEPAY_BACKUP_TARGET:-/var/backups/gmepay}"
OFFHOST="${GMEPAY_BACKUP_OFFHOST:-}"
MAX_AGE_HOURS="${GMEPAY_BACKUP_MAX_AGE_HOURS:-26}"   # nightly + 2h grace
VERIFY_SUMS=0
DO_PITR=1
QUIET=0

while [ $# -gt 0 ]; do
  case "$1" in
    --target)           TARGET="$2"; shift 2 ;;
    --offhost)          OFFHOST="$2"; shift 2 ;;
    --max-age-hours)    MAX_AGE_HOURS="$2"; shift 2 ;;
    --verify-checksums) VERIFY_SUMS=1; shift ;;
    --skip-pitr)        DO_PITR=0; shift ;;
    --quiet)            QUIET=1; shift ;;
    -h|--help)          sed -n '2,35p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "gmepay-backup-check: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

WORST=0                      # 0 ok, 1 warn, 2 critical
say()  { [ "$QUIET" -eq 1 ] || printf '%s\n' "$*"; }
ok()   { say "ok    $*"; }
warn() { say "WARN  $*"; [ "$WORST" -ge 1 ] || WORST=1; }
crit() { say "CRIT  $*"; WORST=2; }

age_hours() { echo $(( ( $(date +%s) - $1 ) / 3600 )); }
mtime()     { date -r "$1" +%s 2>/dev/null || echo 0; }

say "=============================================================================="
say "GMEPay+ backup capability check    $(date -u '+%Y-%m-%d %H:%M:%SZ')"
say "target=$TARGET  max-age=${MAX_AGE_HOURS}h  offhost=${OFFHOST:-<not configured>}"
say "=============================================================================="

# ---------------------------------------------------------------------------
# 1. Is a schedule actually installed?  (the T3-8 headline)
# ---------------------------------------------------------------------------
say
say "-- 1. schedule installed -----------------------------------------------------"
sched_found=0
if [ -f /etc/cron.d/gmepay-backup ]; then
  ok "cron: /etc/cron.d/gmepay-backup present"
  sched_found=1
fi
if command -v systemctl >/dev/null 2>&1 \
   && systemctl list-unit-files 'gmepay-backup.timer' 2>/dev/null | grep -q gmepay-backup.timer; then
  if systemctl is-enabled gmepay-backup.timer >/dev/null 2>&1; then
    ok "systemd: gmepay-backup.timer is enabled"
    systemctl list-timers --all gmepay-backup.timer 2>/dev/null | sed -n '2p' | while read -r l; do
      [ -n "$l" ] && say "      next run: $l"
    done
    sched_found=1
  else
    warn "systemd: gmepay-backup.timer exists but is NOT enabled — it will never fire"
  fi
fi
if [ "$sched_found" -eq 0 ]; then
  crit "NO SCHEDULE INSTALLED on this host. Backups run only when a human remembers,"
  say  "      so the RPO is unbounded and unmeasurable regardless of what the runbook says."
  say  "      Fix: sudo bash scripts/backup/gmepay-schedule-install.sh --target $TARGET"
  say  "      (On a Windows host where the WSL distro is not always up, the schedule lives"
  say  "       in Task Scheduler instead: scripts/backup/gmepay-schedule-install.ps1 -Check)"
fi

# ---------------------------------------------------------------------------
# 2. Newest GOOD artifact, and its age
# ---------------------------------------------------------------------------
say
say "-- 2. newest good backup set -------------------------------------------------"
if [ ! -d "$TARGET" ]; then
  crit "target directory $TARGET does not exist — nothing has ever been written here"
  newest=""
else
  newest=""
  for d in $(ls -1d "$TARGET"/*Z 2>/dev/null | sort || true); do
    [ -f "$d/SUCCESS" ] && newest="$d"
  done
  partials="$(ls -1d "$TARGET"/*.partial 2>/dev/null | wc -l || echo 0)"
  if [ -z "$newest" ]; then
    crit "no SUCCESS-marked backup set in $TARGET (sets without SUCCESS are refused on restore)"
  else
    ts="$(basename "$newest")"
    age="$(age_hours "$(mtime "$newest/SUCCESS")")"
    dumps="$(ls -1 "$newest/postgres" 2>/dev/null | wc -l || echo 0)"
    expected="$(printf '%s\n' "$PG_INVENTORY" | grep -c '|' || echo 0)"
    if [ "$age" -gt "$MAX_AGE_HOURS" ]; then
      crit "newest good set $ts is ${age}h old (> ${MAX_AGE_HOURS}h) — the real RPO is ${age}h, not 24h"
    else
      ok "newest good set $ts, ${age}h old"
    fi
    if [ "$dumps" -ne "$expected" ]; then
      crit "set $ts holds $dumps postgres dumps but the inventory has $expected databases"
    else
      ok "set $ts holds all $expected postgres dumps"
    fi
    say "      total sets with SUCCESS: $(ls -1d "$TARGET"/*Z 2>/dev/null | while read -r d; do [ -f "$d/SUCCESS" ] && echo x; done | wc -l)"
  fi
  if [ "$partials" -gt 0 ]; then
    warn "$partials .partial directory(ies) in $TARGET — a backup failed and was kept for triage"
    say  "      read <set>.partial/backup.log; retention is skipped while a run fails"
  fi
fi

# ---------------------------------------------------------------------------
# 3. Checksums (opt-in: reads every artifact)
# ---------------------------------------------------------------------------
if [ "$VERIFY_SUMS" -eq 1 ] && [ -n "${newest:-}" ]; then
  say
  say "-- 3. manifest checksums -----------------------------------------------------"
  if [ ! -f "$newest/MANIFEST.txt" ]; then
    crit "$(basename "$newest")/MANIFEST.txt is missing — the set cannot be verified"
  elif ( cd "$newest" && sha256sum -c <(awk '/^[0-9a-f]{64}/{print $1"  "$3}' MANIFEST.txt) >/dev/null 2>&1 ); then
    ok "all artifacts in $(basename "$newest") match MANIFEST.txt"
  else
    crit "CHECKSUM MISMATCH in $(basename "$newest") — treat this set as unusable"
  fi
fi

# ---------------------------------------------------------------------------
# 4. Off-host copy — a backup on the data's own disk is not a backup
# ---------------------------------------------------------------------------
say
say "-- 4. off-host copy ----------------------------------------------------------"
if [ -z "$OFFHOST" ]; then
  warn "no off-host directory configured (GMEPAY_BACKUP_OFFHOST / --offhost)"
  say  "      the backups sit on the same host as the data they protect; one disk or one"
  say  "      ransomware event takes both"
elif [ ! -d "$OFFHOST" ]; then
  crit "off-host directory $OFFHOST is not present/mounted — the copy is NOT happening"
else
  onewest=""
  for d in $(ls -1d "$OFFHOST"/*Z 2>/dev/null | sort || true); do
    [ -f "$d/SUCCESS" ] && onewest="$d"
  done
  if [ -z "$onewest" ]; then
    crit "off-host $OFFHOST holds no SUCCESS-marked set"
  else
    oage="$(age_hours "$(mtime "$onewest/SUCCESS")")"
    if [ "$oage" -gt "$MAX_AGE_HOURS" ]; then
      crit "newest off-host set $(basename "$onewest") is ${oage}h old — the mirror has stopped"
    else
      ok "newest off-host set $(basename "$onewest"), ${oage}h old"
    fi
    if [ -n "${newest:-}" ] && [ "$(basename "$onewest")" != "$(basename "$newest")" ]; then
      warn "off-host newest ($(basename "$onewest")) != local newest ($(basename "$newest"))"
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 5. PITR — what actually bounds the RPO for the money DBs
# ---------------------------------------------------------------------------
say
say "-- 5. PITR / WAL streaming (money-critical clusters) -------------------------"
if [ "$DO_PITR" -eq 0 ]; then
  say "      skipped (--skip-pitr)"
elif ! command -v docker >/dev/null 2>&1; then
  warn "docker not on PATH — cannot check the WAL receivers from here"
  say  "      run this on the Docker host (WSL2 distro 'gmepay-docker')"
else
  if bash "$SCRIPT_DIR/gmepay-pitr.sh" --status; then
    ok "PITR is working for: $PITR_SERVICES"
  else
    crit "PITR is NOT fully working (rows above). The money DBs' RPO is back to the"
    say  "      nightly dump, i.e. up to ${MAX_AGE_HOURS}h, whatever the runbook says."
  fi
fi

# ---------------------------------------------------------------------------
# 6. Verification history — an unverified backup is a rumour
# ---------------------------------------------------------------------------
say
say "-- 6. verification + drill history -------------------------------------------"
DRILL_LOG="${GMEPAY_DRILL_LOG:-$TARGET/DRILL_LOG.md}"
if [ -f "$DRILL_LOG" ]; then
  dage="$(age_hours "$(mtime "$DRILL_LOG")")"
  ok "drill log $DRILL_LOG last updated ${dage}h ago"
  [ "$dage" -gt 2160 ] && warn "no drill recorded in 90 days — the RTO is drifting back to an estimate"
else
  warn "no drill log at $DRILL_LOG"
  say  "      --verify-only proves an artifact is READABLE; only a full restore drill proves"
  say  "      the fleet comes up against it. Runbook §3a is the procedure; record the result"
  say  "      in $DRILL_LOG so the RTO is a measurement and not a guess."
fi

say
say "=============================================================================="
case "$WORST" in
  0) say "RESULT: OK — a schedule is installed, the newest good set is fresh, and PITR is up." ;;
  1) say "RESULT: WARN — recoverable, but something claimed by the runbook is not true here." ;;
  2) say "RESULT: CRITICAL — the RPO is not what the runbook claims. Escalate (runbook §8)." ;;
esac
say "=============================================================================="
exit "$WORST"
