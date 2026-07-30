<#
.SYNOPSIS
  Windows operator wrapper for the GMEPay+ backup/restore scripts.

.DESCRIPTION
  The containers run inside the WSL2 distro 'gmepay-docker' (there is no Windows
  docker CLI on this host), so ALL logic lives in the bash scripts next to this
  file. This wrapper only translates a Windows invocation into a `wsl -d` call and
  propagates the exit code — deliberately thin, so there is exactly one
  implementation of the backup logic to maintain.

  Matches the existing repo convention of PowerShell entry points at the surface
  (run-fleet.ps1, demo.ps1) over bash internals (.smoke/*.sh).

.PARAMETER Action
  backup (default) | restore | list | verify | check-inventory | check | pitr

  check  — gap T3-8: is the schedule installed, how old is the newest GOOD artifact, is the
           off-host copy happening, is WAL streaming up? Exit 0 ok / 1 degraded / 2 the RPO
           is not what the runbook claims.
  pitr   — WAL streaming for the money-critical clusters. Reports status by default; pass
           the action via -ExtraArgs (--init / --basebackup / --start / --stop).
           Installing the SCHEDULE is a separate script: gmepay-schedule-install.ps1.

.PARAMETER Target
  Backup target directory, as a LINUX path inside the distro.
  Default /var/backups/gmepay. To land backups on a Windows drive use a /mnt path,
  e.g. -Target /mnt/d/gmepay-backups.

.PARAMETER Set
  Backup set timestamp, required for restore/verify.

.PARAMETER Db
  Scope for restore/verify: a compose service name (postgres-txn), 'mongo',
  'minio', or 'all'.

.PARAMETER ExtraArgs
  Passed through verbatim to the underlying bash script.

.EXAMPLE
  .\gmepay-backup.ps1
.EXAMPLE
  .\gmepay-backup.ps1 -Action backup -Target /mnt/d/gmepay-backups
.EXAMPLE
  .\gmepay-backup.ps1 -Action list
.EXAMPLE
  .\gmepay-backup.ps1 -Action verify -Set 20260728T020000Z -Db postgres-txn
.EXAMPLE
  # destructive — asks for typed confirmation when -Db all
  .\gmepay-backup.ps1 -Action restore -Set 20260728T020000Z -Db postgres-txn
#>
[CmdletBinding()]
param(
    [ValidateSet('backup', 'restore', 'list', 'verify', 'check-inventory', 'check', 'pitr')]
    [string]$Action = 'backup',

    [string]$Target = '/var/backups/gmepay',
    [string]$Set,
    [string]$Db,
    [string]$Distro = 'gmepay-docker',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ExtraArgs
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command wsl -ErrorAction SilentlyContinue)) {
    Write-Error "wsl.exe not found. The GMEPay+ containers run in the WSL2 distro '$Distro'; this wrapper cannot reach them."
    exit 3
}

# This repo lives on D:\GMEPay+\code, exposed to the distro as /mnt/d/GMEPay+/code.
# Resolve from $PSScriptRoot so a moved checkout still works.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$drive = $repoRoot.Substring(0, 1).ToLower()
$linuxRepo = '/mnt/' + $drive + $repoRoot.Substring(2).Replace('\', '/')
$backupDir = "$linuxRepo/scripts/backup"

switch ($Action) {
    'backup'          { $script = 'gmepay-backup.sh';  $argv = @('--target', $Target) }
    'list'            { $script = 'gmepay-restore.sh'; $argv = @('--target', $Target, '--list') }
    'check-inventory' { $script = 'check-inventory.sh'; $argv = @() }
    # T3-8. `check` answers "is the backup capability actually running, and how old is the
    # newest thing we could restore from" (exit 0 ok / 1 degraded / 2 the RPO is not what
    # the runbook claims). `pitr` reports the WAL-streaming state for the money-critical
    # clusters; pass the action through -ExtraArgs, e.g. -Action pitr -ExtraArgs --init.
    'check'           { $script = 'gmepay-backup-check.sh'; $argv = @('--target', $Target) }
    'pitr'            { $script = 'gmepay-pitr.sh'; $argv = @() }
    'verify' {
        if (-not $Set -or -not $Db) { Write-Error "-Set and -Db are required for verify"; exit 2 }
        $script = 'gmepay-restore.sh'
        $argv = @('--target', $Target, '--set', $Set, '--db', $Db, '--verify-only')
    }
    'restore' {
        if (-not $Set -or -not $Db) { Write-Error "-Set and -Db are required for restore"; exit 2 }
        Write-Warning "This OVERWRITES live data in scope '$Db' from backup set '$Set'."
        $answer = Read-Host "Type RESTORE to continue"
        if ($answer -ne 'RESTORE') { Write-Host "Aborted; nothing was changed."; exit 1 }
        $script = 'gmepay-restore.sh'
        $argv = @('--target', $Target, '--set', $Set, '--db', $Db,
                  '--i-understand-this-destroys-data')
    }
}

if ($ExtraArgs) { $argv += $ExtraArgs }

Write-Host "wsl -d $Distro -- bash $backupDir/$script $($argv -join ' ')"
& wsl -d $Distro -- bash "$backupDir/$script" @argv
$rc = $LASTEXITCODE
if ($rc -ne 0) { Write-Error "gmepay $Action FAILED (exit $rc)" }
exit $rc
