#!/usr/bin/env bash
#
# gmepay-schedule-install.sh — installs the backup/PITR schedule. Gap T3-8.
#
# ============================================================================
# WHY A SCRIPT AND NOT A RUNBOOK SECTION
# ============================================================================
# Documentation/RUNBOOK_BACKUP_DR.md §4 has carried a correct cron file, a correct
# systemd unit pair and a correct Task Scheduler snippet since T3-1. None of them
# were installed anywhere, so for T3-8's whole lifetime the platform had a backup
# capability that ran only when a human remembered — which is the same as not having
# one, except that it reads like having one. Copy-paste from a document is a step that
# gets skipped, done partially, or done differently on two hosts. This installs the
# whole set, or refuses and changes nothing.
#
# WHAT GETS INSTALLED (five jobs, not one — a nightly dump alone is not the capability)
#   1. nightly full logical backup      02:00 KST   gmepay-backup.sh
#   2. off-host mirror                  02:15 KST   rsync (only if --offhost is given)
#   3. weekly base backup               Sun 01:00   gmepay-pitr.sh --basebackup
#   4. WAL-receiver watchdog            every 5 min gmepay-pitr.sh --start  (idempotent:
#      a running receiver is left alone, a dead one is restarted. Without this a
#      receiver that dies at 3am silently stops PITR *and* makes the primary retain WAL)
#   5. capability check                 hourly      gmepay-backup-check.sh (exit code is
#      the signal: 2 = the RPO is not what the runbook claims)
#
# IDEMPOTENT + REFUSES TO DOUBLE-INSTALL
#   A second run with the same arguments is a no-op that says so. A second run with
#   DIFFERENT arguments is refused unless --force, because two schedules writing to two
#   targets means neither is the backup and nobody knows which. --uninstall removes
#   exactly what this script created and nothing else.
#
# USAGE
#   sudo bash gmepay-schedule-install.sh --target /var/backups/gmepay \
#                                        --offhost /mnt/backup-disk/gmepay
#   bash gmepay-schedule-install.sh --print          # show the units, install nothing
#   bash gmepay-schedule-install.sh --status         # what is installed right now
#   sudo bash gmepay-schedule-install.sh --uninstall
#   ...--flavour cron|systemd to force one (default: systemd if available, else cron)
#
# It installs a SCHEDULE. It does not run a backup, touch a database, or start a
# container. The first backup happens when the timer fires (or run gmepay-backup.sh
# by hand once to confirm it works before trusting the schedule).
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=inventory.env
. "$SCRIPT_DIR/inventory.env"

TARGET="/var/backups/gmepay"
OFFHOST=""
ARCHIVE="$PITR_ARCHIVE_ROOT_DEFAULT"
MAILTO="platform-oncall@gmeremit.com"
FLAVOUR=""
ACTION="install"
FORCE=0
DRY_RUN=0

CRON_FILE="/etc/cron.d/gmepay-backup"
UNIT_DIR="/etc/systemd/system"
STAMP="/etc/gmepay-backup-schedule.conf"     # what we installed, for idempotency
UNITS="gmepay-backup gmepay-offhost gmepay-basebackup gmepay-walwatch gmepay-backupcheck"

while [ $# -gt 0 ]; do
  case "$1" in
    --target)    TARGET="$2"; shift 2 ;;
    --offhost)   OFFHOST="$2"; shift 2 ;;
    --archive)   ARCHIVE="$2"; shift 2 ;;
    --mailto)    MAILTO="$2"; shift 2 ;;
    --flavour)   FLAVOUR="$2"; shift 2 ;;
    --print)     ACTION="print"; shift ;;
    --status)    ACTION="status"; shift ;;
    --uninstall) ACTION="uninstall"; shift ;;
    --force)     FORCE=1; shift ;;
    --dry-run)   DRY_RUN=1; shift ;;
    -h|--help)   sed -n '2,42p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "gmepay-schedule-install: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

log() { printf '%s\n' "$*"; }
die() { echo "gmepay-schedule-install: $*" >&2; exit 1; }
run() { if [ "$DRY_RUN" -eq 1 ]; then echo "DRY-RUN  $*"; else "$@"; fi }

if [ -z "$FLAVOUR" ]; then
  if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
    FLAVOUR="systemd"
  else
    FLAVOUR="cron"
  fi
fi
[ "$FLAVOUR" = "systemd" ] || [ "$FLAVOUR" = "cron" ] || die "--flavour must be systemd or cron"

BACKUP_SH="$SCRIPT_DIR/gmepay-backup.sh"
PITR_SH="$SCRIPT_DIR/gmepay-pitr.sh"
CHECK_SH="$SCRIPT_DIR/gmepay-backup-check.sh"
for f in "$BACKUP_SH" "$PITR_SH" "$CHECK_SH"; do
  [ -f "$f" ] || die "$f not found — run this from a full checkout"
done

# The desired configuration, hashed into the stamp file so a re-run can tell
# "already installed identically" from "installed differently".
config_signature() {
  printf 'flavour=%s\ntarget=%s\noffhost=%s\narchive=%s\nrepo=%s\nmailto=%s\n' \
    "$FLAVOUR" "$TARGET" "$OFFHOST" "$ARCHIVE" "$REPO_ROOT" "$MAILTO"
}

# ---------------------------------------------------------------------------
# Renderers
# ---------------------------------------------------------------------------
render_cron() {
  cat <<EOF
# /etc/cron.d/gmepay-backup — installed by scripts/backup/gmepay-schedule-install.sh
# Gap T3-8. Do not hand-edit: re-run the installer with --force instead, or the stamp
# file $STAMP stops matching reality.
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
MAILTO=$MAILTO
GMEPAY_PITR_ARCHIVE=$ARCHIVE

# 1. Nightly full logical backup, 02:00 KST (17:00 UTC the previous day).
#    A non-zero exit is what makes MAILTO fire — never wrap this so the code is swallowed.
0 17 * * *   root  /bin/bash $BACKUP_SH --target $TARGET >> /var/log/gmepay-backup.log 2>&1
EOF
  if [ -n "$OFFHOST" ]; then
    cat <<EOF
# 2. Off-host mirror, 15 min after the backup. A copy on the data's own disk is not a backup.
15 17 * * *  root  /usr/bin/rsync -a --delete $TARGET/ $OFFHOST/ >> /var/log/gmepay-backup.log 2>&1
EOF
  else
    cat <<'EOF'
# 2. Off-host mirror: NOT INSTALLED (no --offhost given). The backups sit on the same
#    host as the data they protect. Re-run the installer with --offhost <dir> --force.
EOF
  fi
  cat <<EOF
# 3. Weekly base backup for the PITR clusters, Sunday 01:00 KST. WAL without a base
#    backup restores NOTHING, so this is not optional once the receivers are running.
0 16 * * 6   root  /bin/bash $PITR_SH --basebackup >> /var/log/gmepay-pitr.log 2>&1
# 4. WAL-receiver watchdog every 5 minutes. --start is idempotent: running receivers are
#    left alone, dead ones restarted. A receiver that dies unnoticed both stops PITR and
#    makes the primary retain WAL until max_slot_wal_keep_size.
*/5 * * * *  root  /bin/bash $PITR_SH --start >> /var/log/gmepay-pitr.log 2>&1
# 5. Hourly capability check. Exit 2 = the RPO is not what the runbook claims.
7 * * * *    root  /bin/bash $CHECK_SH --target $TARGET${OFFHOST:+ --offhost $OFFHOST} >> /var/log/gmepay-backup-check.log 2>&1
EOF
}

render_unit() {   # $1 = unit base name
  case "$1" in
    gmepay-backup)
      cat <<EOF
[Unit]
Description=GMEPay+ full logical backup (T3-1/T3-8)
Documentation=file://$REPO_ROOT/Documentation/RUNBOOK_BACKUP_DR.md
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
Environment=GMEPAY_PITR_ARCHIVE=$ARCHIVE
ExecStart=/bin/bash $BACKUP_SH --target $TARGET
EOF
      ;;
    gmepay-offhost)
      cat <<EOF
[Unit]
Description=GMEPay+ off-host backup mirror (T3-8)
After=gmepay-backup.service

[Service]
Type=oneshot
ExecStart=/usr/bin/rsync -a --delete $TARGET/ $OFFHOST/
EOF
      ;;
    gmepay-basebackup)
      cat <<EOF
[Unit]
Description=GMEPay+ PITR base backup for the money-critical clusters (T3-8)
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
Environment=GMEPAY_PITR_ARCHIVE=$ARCHIVE
ExecStart=/bin/bash $PITR_SH --basebackup
EOF
      ;;
    gmepay-walwatch)
      cat <<EOF
[Unit]
Description=GMEPay+ WAL receiver watchdog - restarts a dead pg_receivewal (T3-8)
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
Environment=GMEPAY_PITR_ARCHIVE=$ARCHIVE
# --start is idempotent; a running receiver is left untouched.
ExecStart=/bin/bash $PITR_SH --start
EOF
      ;;
    gmepay-backupcheck)
      cat <<EOF
[Unit]
Description=GMEPay+ backup capability check - exit 2 means the RPO is not what we claim (T3-8)

[Service]
Type=oneshot
Environment=GMEPAY_PITR_ARCHIVE=$ARCHIVE
ExecStart=/bin/bash $CHECK_SH --target $TARGET${OFFHOST:+ --offhost $OFFHOST}
EOF
      ;;
  esac
}

render_timer() {  # $1 = unit base name
  case "$1" in
    gmepay-backup)      cat <<'EOF'
[Unit]
Description=Nightly GMEPay+ backup (02:00 KST)

[Timer]
OnCalendar=*-*-* 17:00:00 UTC
Persistent=true

[Install]
WantedBy=timers.target
EOF
      ;;
    gmepay-offhost)     cat <<'EOF'
[Unit]
Description=GMEPay+ off-host mirror (02:15 KST)

[Timer]
OnCalendar=*-*-* 17:15:00 UTC
Persistent=true

[Install]
WantedBy=timers.target
EOF
      ;;
    gmepay-basebackup)  cat <<'EOF'
[Unit]
Description=Weekly GMEPay+ PITR base backup (Sunday 01:00 KST)

[Timer]
OnCalendar=Sat *-*-* 16:00:00 UTC
Persistent=true

[Install]
WantedBy=timers.target
EOF
      ;;
    gmepay-walwatch)    cat <<'EOF'
[Unit]
Description=GMEPay+ WAL receiver watchdog (every 5 min)

[Timer]
OnBootSec=2min
OnUnitActiveSec=5min
AccuracySec=30s

[Install]
WantedBy=timers.target
EOF
      ;;
    gmepay-backupcheck) cat <<'EOF'
[Unit]
Description=GMEPay+ backup capability check (hourly)

[Timer]
OnCalendar=hourly
Persistent=true
RandomizedDelaySec=5min

[Install]
WantedBy=timers.target
EOF
      ;;
  esac
}

units_for_flavour() {
  local u
  for u in $UNITS; do
    [ "$u" = "gmepay-offhost" ] && [ -z "$OFFHOST" ] && continue
    printf '%s\n' "$u"
  done
}

# ---------------------------------------------------------------------------
# print / status / uninstall / install
# ---------------------------------------------------------------------------
if [ "$ACTION" = "print" ]; then
  log "# flavour=$FLAVOUR  target=$TARGET  offhost=${OFFHOST:-<none>}  archive=$ARCHIVE"
  if [ "$FLAVOUR" = "cron" ]; then
    log "# ---- $CRON_FILE ----"; render_cron
  else
    for u in $(units_for_flavour); do
      log "# ---- $UNIT_DIR/$u.service ----"; render_unit "$u"
      log "# ---- $UNIT_DIR/$u.timer ----";   render_timer "$u"
    done
  fi
  exit 0
fi

if [ "$ACTION" = "status" ]; then
  log "installed schedule:"
  if [ -f "$STAMP" ]; then
    log "  stamp $STAMP:"
    sed 's/^/    /' "$STAMP"
  else
    log "  no stamp file — this installer has not run on this host"
  fi
  [ -f "$CRON_FILE" ] && log "  cron: $CRON_FILE present" || log "  cron: $CRON_FILE absent"
  if command -v systemctl >/dev/null 2>&1; then
    for u in $UNITS; do
      if [ -f "$UNIT_DIR/$u.timer" ]; then
        log "  systemd: $u.timer present, enabled=$(systemctl is-enabled "$u.timer" 2>/dev/null || echo no), active=$(systemctl is-active "$u.timer" 2>/dev/null || echo no)"
      fi
    done
  fi
  log ""
  log "whether backups are actually RUNNING is a different question:"
  log "  bash $CHECK_SH --target $TARGET"
  exit 0
fi

[ "$DRY_RUN" -eq 1 ] || [ "$(id -u)" = "0" ] \
  || die "must run as root to write $CRON_FILE / $UNIT_DIR (use --print or --dry-run to inspect)"

if [ "$ACTION" = "uninstall" ]; then
  log "removing only what this installer created:"
  [ -f "$CRON_FILE" ] && { log "  rm $CRON_FILE"; run rm -f "$CRON_FILE"; }
  if command -v systemctl >/dev/null 2>&1; then
    for u in $UNITS; do
      if [ -f "$UNIT_DIR/$u.timer" ]; then
        log "  disable + rm $u.timer/.service"
        run systemctl disable --now "$u.timer" >/dev/null 2>&1 || true
        run rm -f "$UNIT_DIR/$u.timer" "$UNIT_DIR/$u.service"
      fi
    done
    run systemctl daemon-reload
  fi
  [ -f "$STAMP" ] && run rm -f "$STAMP"
  log ""
  log "The WAL receivers are NOT touched by this — they are containers, not units."
  log "Nothing is scheduled now, so the RPO is unbounded until something is."
  log "To also end PITR: bash $PITR_SH --drop-slots --i-understand-this-ends-pitr"
  exit 0
fi

# ---- install -------------------------------------------------------------
existing=""
[ -f "$CRON_FILE" ] && existing="$existing $CRON_FILE"
for u in $UNITS; do
  [ -f "$UNIT_DIR/$u.timer" ] && existing="$existing $u.timer"
done

if [ -n "$existing" ]; then
  if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$(config_signature)" ] && [ "$FORCE" -eq 0 ]; then
    log "ALREADY INSTALLED, identically — nothing changed."
    log "  $(echo "$existing" | tr -s ' ')"
    log ""
    log "This says the schedule EXISTS. Whether it is producing fresh artifacts is a"
    log "different question, and the one that matters:"
    log "  bash $CHECK_SH --target $TARGET"
    exit 0
  fi
  if [ "$FORCE" -eq 0 ]; then
    echo "gmepay-schedule-install: REFUSING to install over an existing schedule." >&2
    echo "  found:$existing" >&2
    if [ -f "$STAMP" ]; then
      echo "  the existing install used:" >&2
      sed 's/^/    /' "$STAMP" >&2
      echo "  you asked for:" >&2
      config_signature | sed 's/^/    /' >&2
    else
      echo "  and there is no stamp file, so it was installed by hand or by an older version." >&2
    fi
    echo "  Two schedules writing to two targets means neither one is 'the backup'." >&2
    echo "  Re-run with --force to replace it, or --uninstall first." >&2
    exit 1
  fi
  log "--force: replacing the existing schedule"
fi

log "installing the $FLAVOUR schedule (target=$TARGET offhost=${OFFHOST:-<none>} archive=$ARCHIVE)"
run mkdir -p "$TARGET" "$ARCHIVE"
[ -n "$OFFHOST" ] || log "WARNING: no --offhost — backups will live on the same host as the data"

if [ "$FLAVOUR" = "cron" ]; then
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "DRY-RUN  would write $CRON_FILE (mode 0644, root):"; render_cron | sed 's/^/    /'
  else
    render_cron > "$CRON_FILE"
    chmod 0644 "$CRON_FILE"; chown root:root "$CRON_FILE" 2>/dev/null || true
    log "  wrote $CRON_FILE"
    command -v crontab >/dev/null 2>&1 || log "  WARNING: no cron implementation found on PATH — install cron or use --flavour systemd"
  fi
else
  for u in $(units_for_flavour); do
    if [ "$DRY_RUN" -eq 1 ]; then
      echo "DRY-RUN  would write $UNIT_DIR/$u.{service,timer} and enable $u.timer"
    else
      render_unit  "$u" > "$UNIT_DIR/$u.service"
      render_timer "$u" > "$UNIT_DIR/$u.timer"
      log "  wrote $UNIT_DIR/$u.service + .timer"
    fi
  done
  run systemctl daemon-reload
  for u in $(units_for_flavour); do
    run systemctl enable --now "$u.timer"
  done
  if [ "$DRY_RUN" -eq 0 ]; then
    log ""
    systemctl list-timers --all $(units_for_flavour | sed 's/$/.timer/' | tr '\n' ' ') 2>/dev/null || true
  fi
fi

if [ "$DRY_RUN" -eq 0 ]; then
  config_signature > "$STAMP"
  chmod 0644 "$STAMP"
fi

cat <<EOF

Installed. THE SCHEDULE IS NOT THE CAPABILITY — three things are still on you:

  1. Enable PITR once (the schedule only KEEPS it running):
       bash $PITR_SH --init
       bash $PITR_SH --basebackup
       bash $PITR_SH --start
     Until then the money DBs' RPO is still 24 h, and the watchdog job has nothing
     to watch.

  2. Prove it is running, now and next week:
       bash $CHECK_SH --target $TARGET${OFFHOST:+ --offhost $OFFHOST}
     Exit 0 = OK, 1 = degraded, 2 = the RPO is not what the runbook claims.

  3. Run ONE full restore drill (Documentation/RUNBOOK_BACKUP_DR.md §3a) and write the
     measured duration into $TARGET/DRILL_LOG.md. Until that happens the ~4 h fleet RTO
     is an estimate, and an estimate is not a recovery objective.
EOF
