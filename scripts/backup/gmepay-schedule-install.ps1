<#
.SYNOPSIS
  Installs the GMEPay+ backup / PITR schedule in Windows Task Scheduler. Gap T3-8.

.DESCRIPTION
  The containers run inside the WSL2 distro 'gmepay-docker'. WSL2 does not run cron
  unless the distro happens to be up, so on a workstation-class host the schedule must
  live on the WINDOWS side and start the distro itself — otherwise the backup silently
  does not happen on every day nobody opened a terminal. That is exactly the failure
  T3-8 records: a capability that exists and never runs.

  Every task is a thin `wsl -d <distro> -- bash <script>` call, so there is still ONE
  implementation of the backup logic (the bash scripts next to this file). Nothing here
  duplicates backup behaviour.

  WHAT GETS REGISTERED
    GMEPay-Backup-Nightly     daily 02:00      gmepay-backup.sh
    GMEPay-Offhost-Mirror     daily 02:15      rsync to -Offhost         (only with -Offhost)
    GMEPay-PITR-Basebackup    Sunday 01:00     gmepay-pitr.sh --basebackup
    GMEPay-PITR-Watchdog      every 5 minutes  gmepay-pitr.sh --start    (idempotent restart
                                               of a dead pg_receivewal)
    GMEPay-Backup-Check       hourly           gmepay-backup-check.sh    (exit 2 = the RPO is
                                               not what the runbook claims)

  IDEMPOTENT AND REFUSES TO DOUBLE-INSTALL. Re-running with the same arguments reports
  "already installed" and changes nothing. Existing tasks are never silently replaced —
  pass -Force. -Uninstall removes only the five task names above.

  This registers a SCHEDULE. It does not run a backup, touch a database, or start a
  container. Enabling PITR itself is still one manual step (see the closing notes).

.PARAMETER Target
  Backup target as a LINUX path inside the distro. Default /var/backups/gmepay.
  Use a /mnt path to land on a Windows drive, e.g. /mnt/e/gmepay-backups — and prefer a
  DIFFERENT physical disk from the one holding the Docker volumes.

.PARAMETER Offhost
  Off-host mirror directory (linux path). Omitted = no mirror task, and the check
  script will WARN that backups sit on the same host as the data.

.PARAMETER Archive
  PITR WAL/base archive root (linux path). Default /var/backups/gmepay-wal.

.PARAMETER Check
  Report what is registered and how each task last exited. Installs nothing.

.PARAMETER Uninstall
  Unregister the five tasks. Leaves WAL receivers and replication slots alone.

.EXAMPLE
  # Run in an ELEVATED PowerShell (registering a SYSTEM task requires it).
  .\gmepay-schedule-install.ps1 -Target /mnt/e/gmepay-backups -Offhost /mnt/f/gmepay-backups

.EXAMPLE
  .\gmepay-schedule-install.ps1 -Check
#>
[CmdletBinding()]
param(
    [string]$Target = '/var/backups/gmepay',
    [string]$Offhost = '',
    [string]$Archive = '/var/backups/gmepay-wal',
    [string]$Distro = 'gmepay-docker',
    [switch]$Check,
    [switch]$Uninstall,
    [switch]$Force,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$TaskNames = @(
    'GMEPay-Backup-Nightly',
    'GMEPay-Offhost-Mirror',
    'GMEPay-PITR-Basebackup',
    'GMEPay-PITR-Watchdog',
    'GMEPay-Backup-Check'
)

function Get-ExistingTasks {
    $found = @()
    foreach ($n in $TaskNames) {
        try {
            $t = Get-ScheduledTask -TaskName $n -ErrorAction Stop
            $found += $t
        } catch {
            # not registered - not an error here
        }
    }
    return $found
}

# ---------------------------------------------------------------------------
# -Check: report, install nothing
# ---------------------------------------------------------------------------
if ($Check) {
    $existing = Get-ExistingTasks
    if ($existing.Count -eq 0) {
        Write-Output 'NO GMEPay+ backup tasks are registered in Task Scheduler on this host.'
        Write-Output 'Backups therefore run only when a human remembers, so the RPO is unbounded'
        Write-Output 'and unmeasurable regardless of what Documentation/RUNBOOK_BACKUP_DR.md claims.'
        Write-Output 'Fix (elevated): .\gmepay-schedule-install.ps1 -Target <linux path>'
        exit 2
    }
    $worst = 0
    foreach ($t in $existing) {
        $info = Get-ScheduledTaskInfo -TaskName $t.TaskName
        $last = $info.LastTaskResult
        $line = "{0,-24} state={1,-8} lastRun={2} lastResult={3}" -f `
            $t.TaskName, $t.State, $info.LastRunTime, $last
        Write-Output $line
        if ($t.State -eq 'Disabled') {
            Write-Output '    DISABLED - it will never fire'
            if ($worst -lt 2) { $worst = 2 }
        }
        # 267009 = currently running; 267011 = has never run. Neither is a failure.
        if ($last -ne 0 -and $last -ne 267009 -and $last -ne 267011) {
            Write-Output "    NON-ZERO exit ($last) - the job FAILED. Read the log named in the runbook (section 8)."
            if ($worst -lt 2) { $worst = 2 }
        }
        if ($last -eq 267011) {
            Write-Output '    has never run yet'
            if ($worst -lt 1) { $worst = 1 }
        }
    }
    Write-Output ''
    Write-Output 'A registered task proves a SCHEDULE exists, not that artifacts are fresh.'
    Write-Output 'The question that matters is answered by the check script itself:'
    Write-Output "  wsl -d $Distro -- bash /mnt/<...>/scripts/backup/gmepay-backup-check.sh --target $Target"
    exit $worst
}

if (-not (Get-Command wsl -ErrorAction SilentlyContinue)) {
    Write-Error "wsl.exe not found. The GMEPay+ containers run in the WSL2 distro '$Distro'; this schedule cannot reach them."
    exit 3
}

# ---------------------------------------------------------------------------
# -Uninstall
# ---------------------------------------------------------------------------
if ($Uninstall) {
    $existing = Get-ExistingTasks
    if ($existing.Count -eq 0) {
        Write-Output 'Nothing to remove - none of the five tasks are registered.'
        exit 0
    }
    foreach ($t in $existing) {
        Write-Output "unregister $($t.TaskName)"
        if (-not $DryRun) {
            Unregister-ScheduledTask -TaskName $t.TaskName -Confirm:$false
        }
    }
    Write-Output ''
    Write-Output 'The WAL receivers and replication slots are NOT touched - they are containers,'
    Write-Output 'not scheduled tasks. Nothing is scheduled now, so the RPO is unbounded until'
    Write-Output 'something is. To also end PITR:'
    Write-Output "  wsl -d $Distro -- bash <repo>/scripts/backup/gmepay-pitr.sh --drop-slots --i-understand-this-ends-pitr"
    exit 0
}

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------
$existing = Get-ExistingTasks
if ($existing.Count -gt 0 -and -not $Force) {
    Write-Output 'REFUSING to install over an existing schedule. Already registered:'
    foreach ($t in $existing) { Write-Output "  $($t.TaskName) (state=$($t.State))" }
    Write-Output ''
    Write-Output 'Two schedules writing to two targets means neither one is "the backup" and'
    Write-Output 'nobody can say what the RPO is. Re-run with -Force to replace them, or'
    Write-Output '-Uninstall first. Use -Check to see how they last exited.'
    exit 1
}

# This repo lives on D:\GMEPay+\code, exposed to the distro as /mnt/d/GMEPay+/code.
# Resolve from $PSScriptRoot so a moved checkout still works (same rule as gmepay-backup.ps1).
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$drive = $repoRoot.Substring(0, 1).ToLower()
$linuxRepo = '/mnt/' + $drive + $repoRoot.Substring(2).Replace('\', '/')
$backupDir = "$linuxRepo/scripts/backup"

# Every task is: wsl -d <distro> -- bash <script> <args>. `wsl` STARTS the distro if it is
# not running, which is the whole reason the schedule lives on Windows.
function New-WslAction {
    param([string]$ScriptArgs)
    return New-ScheduledTaskAction -Execute 'wsl.exe' `
        -Argument "-d $Distro -- bash $ScriptArgs"
}

$jobs = @()
$jobs += [pscustomobject]@{
    Name    = 'GMEPay-Backup-Nightly'
    Args    = "$backupDir/gmepay-backup.sh --target $Target"
    Trigger = (New-ScheduledTaskTrigger -Daily -At 2:00am)
    Limit   = (New-TimeSpan -Hours 3)
    Desc    = 'GMEPay+ full logical backup of all 15 PostgreSQL DBs + Mongo + MinIO (T3-1/T3-8)'
}
if ($Offhost -ne '') {
    $jobs += [pscustomobject]@{
        Name    = 'GMEPay-Offhost-Mirror'
        Args    = "-c 'rsync -a --delete $Target/ $Offhost/'"
        Trigger = (New-ScheduledTaskTrigger -Daily -At 2:15am)
        Limit   = (New-TimeSpan -Hours 3)
        Desc    = 'GMEPay+ off-host backup mirror - a copy on the data disk is not a backup (T3-8)'
    }
}
$jobs += [pscustomobject]@{
    Name    = 'GMEPay-PITR-Basebackup'
    Args    = "$backupDir/gmepay-pitr.sh --basebackup --archive $Archive"
    Trigger = (New-ScheduledTaskTrigger -Weekly -DaysOfWeek Sunday -At 1:00am)
    Limit   = (New-TimeSpan -Hours 3)
    Desc    = 'GMEPay+ PITR base backup - WAL without a base backup restores NOTHING (T3-8)'
}
$jobs += [pscustomobject]@{
    Name    = 'GMEPay-PITR-Watchdog'
    Args    = "$backupDir/gmepay-pitr.sh --start --archive $Archive"
    Trigger = (New-ScheduledTaskTrigger -Once -At (Get-Date).Date.AddMinutes(3) `
                 -RepetitionInterval (New-TimeSpan -Minutes 5))
    Limit   = (New-TimeSpan -Minutes 10)
    Desc    = 'GMEPay+ WAL receiver watchdog - restarts a dead pg_receivewal; a receiver that dies unnoticed both stops PITR and makes the primary retain WAL (T3-8)'
}
$jobs += [pscustomobject]@{
    Name    = 'GMEPay-Backup-Check'
    Args    = "$backupDir/gmepay-backup-check.sh --target $Target" + $(if ($Offhost -ne '') { " --offhost $Offhost" } else { '' })
    Trigger = (New-ScheduledTaskTrigger -Once -At (Get-Date).Date.AddMinutes(7) `
                 -RepetitionInterval (New-TimeSpan -Hours 1))
    Limit   = (New-TimeSpan -Minutes 20)
    Desc    = 'GMEPay+ backup capability check - LastTaskResult 2 means the RPO is not what the runbook claims (T3-8)'
}

Write-Output "installing $($jobs.Count) scheduled task(s)  target=$Target  archive=$Archive  offhost=$(if ($Offhost -eq '') { '<none>' } else { $Offhost })"
if ($Offhost -eq '') {
    Write-Output 'WARNING: no -Offhost - the backups will live on the same host as the data they protect.'
}

foreach ($j in $jobs) {
    Write-Output "  $($j.Name)  ->  wsl -d $Distro -- bash $($j.Args)"
    if ($DryRun) { continue }
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable `
        -ExecutionTimeLimit $j.Limit -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $j.Name `
        -Action (New-WslAction -ScriptArgs $j.Args) `
        -Trigger $j.Trigger -Settings $settings `
        -User 'SYSTEM' -RunLevel Highest -Description $j.Desc -Force | Out-Null
}

Write-Output ''
Write-Output 'Installed. THE SCHEDULE IS NOT THE CAPABILITY - three things are still on you:'
Write-Output ''
Write-Output '  1. Enable PITR once (the schedule only KEEPS it running):'
Write-Output "       wsl -d $Distro -- bash $backupDir/gmepay-pitr.sh --init"
Write-Output "       wsl -d $Distro -- bash $backupDir/gmepay-pitr.sh --basebackup"
Write-Output "       wsl -d $Distro -- bash $backupDir/gmepay-pitr.sh --start"
Write-Output '     Until then the money DBs RPO is still 24h and the watchdog has nothing to watch.'
Write-Output ''
Write-Output '  2. Prove it is running, now and next week:'
Write-Output '       .\gmepay-schedule-install.ps1 -Check'
Write-Output "       wsl -d $Distro -- bash $backupDir/gmepay-backup-check.sh --target $Target"
Write-Output ''
Write-Output '  3. Run ONE full restore drill (Documentation/RUNBOOK_BACKUP_DR.md section 3a) and'
Write-Output "     write the measured duration into $Target/DRILL_LOG.md. Until that happens the"
Write-Output '     ~4h fleet RTO is an estimate, and an estimate is not a recovery objective.'
