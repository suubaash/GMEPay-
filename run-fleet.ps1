<#
.SYNOPSIS
  Boot / stop / inspect the full GMEPay+ local fleet with the transparency tracer.

.DESCRIPTION
  Starts the trace-console (.smoke/trace-console.js) plus every backend service and
  simulator as detached JVMs, each on a distinct port with gmepay.trace.enabled=true,
  so the whole platform self-reports to the dashboard at http://localhost:7099.

  Most services run on their H2 / in-memory fallback, so NO Docker infra is required for
  a tracer demo. Kafka-dependent services (notification-webhook, revenue-ledger,
  transaction-mgmt, kyb-adapter) will log background "cannot connect to localhost:9092"
  warnings but still start and serve. api-gateway is reactive and may be flaky without
  Redis/Keycloak.

.PARAMETER Action
  start (default) | stop | status | restart

.PARAMETER Build
  Force a rebuild of all boot jars first. Otherwise only missing jars are built.

.PARAMETER NoTrace
  Start without gmepay.trace.enabled (services run but don't report to the console).

.PARAMETER Xmx
  Optional global heap override (e.g. 160m) applied to every JVM. Default empty =
  use per-tier heaps (JPA 160m / stateless 112m / gateway 144m / sims 80m). Each JVM
  also gets lean flags: SerialGC, TieredStopAtLevel=1, capped code-cache + metaspace,
  -Xss512k, JMX off; plus lean Spring props (lazy-init, Tomcat 20 threads, Hikari 5/1,
  open-in-view off, springdoc off). No functionality is removed — prod/docker-compose
  are untouched; these apply only to this dev launcher.

.EXAMPLE
  .\run-fleet.ps1                 # build-if-missing + start everything + tracer
  .\run-fleet.ps1 -Subset money   # start only the core payment cascade (fits a tight box)
  .\run-fleet.ps1 -Build          # rebuild all jars, then start
  .\run-fleet.ps1 status          # show what's up + tracer component count
  .\run-fleet.ps1 stop            # kill the whole fleet + tracer

.NOTES
  Memory: all 22 JVMs need ~8-10 GB. If services get reaped, use -Subset money or
  lower -Xmx (e.g. -Xmx 224m). Run from any path (uses its own folder as the repo root).
  First run from a new shell may need:  Unblock-File .\run-fleet.ps1
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'status', 'restart')]
    [string]$Action = 'start',
    [switch]$Build,
    [switch]$NoTrace,
    [ValidateSet('all', 'money')]
    [string]$Subset = 'all',
    [string]$Xmx = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$traceEnabled = -not $NoTrace
$logDir = Join-Path $root '.smoke\logs'
$dashUrl = 'http://localhost:7099'

# --- the fleet -------------------------------------------------------------
# type: service jars are <name>-0.1.0.jar under services\<name>; sim jars are
# <name>-*.jar under simulators\<name>. 'args' are extra Spring CLI args.
$fleet = @(
    @{ name = 'config-registry';           type = 'service'; port = 18081 }
    @{ name = 'transaction-mgmt';           type = 'service'; port = 18082 }
    @{ name = 'merchant-qr-data';           type = 'service'; port = 18083 }
    @{ name = 'payment-executor';           type = 'service'; port = 18084 }
    @{ name = 'auth-identity';              type = 'service'; port = 18085 }
    @{ name = 'notification-webhook';       type = 'service'; port = 18086 }
    @{ name = 'reporting-compliance';       type = 'service'; port = 18087 }
    @{ name = 'prefunding';                 type = 'service'; port = 18088 }
    @{ name = 'qr-service';                 type = 'service'; port = 18089 }
    @{ name = 'scheme-adapter-zeropay';     type = 'service'; port = 18090 }
    @{ name = 'smart-router';               type = 'service'; port = 18091 }
    @{ name = 'revenue-ledger';             type = 'service'; port = 18092 }
    @{ name = 'settlement-reconciliation';  type = 'service'; port = 18093 }
    @{ name = 'ops-partner-bff';            type = 'service'; port = 18095 }
    @{ name = 'kyb-adapter';                type = 'service'; port = 18098 }
    @{ name = 'rate-fx';                    type = 'service'; port = 18101 }
    @{ name = 'api-gateway';                type = 'service'; port = 18080 }
    @{ name = 'sim-rate-provider';          type = 'sim';     port = 9101 }
    @{ name = 'sim-scheme';                 type = 'sim';     port = 9102; args = @('--gmepay.sim.scheme.profile=ZEROPAY') }
    @{ name = 'sim-wallet';                 type = 'sim';     port = 9103 }
    @{ name = 'sim-merchant';               type = 'sim';     port = 9104; args = @('--gmepay.sim.merchant.merchant-qr-data-base-url=http://localhost:18083') }
    @{ name = 'sim-gmeremit';               type = 'sim';     port = 9105; args = @('--gmepay.sim.gmeremit.gmepay-base-url=http://localhost:18084') }
)

# Running all 22 JVMs at once needs ~8-10 GB RAM; on a tight box the OS may reap some.
# -Subset money boots just the core payment cascade (~14 components) which fits comfortably.
$moneyNames = @('config-registry', 'transaction-mgmt', 'payment-executor', 'scheme-adapter-zeropay',
    'rate-fx', 'prefunding', 'ops-partner-bff', 'merchant-qr-data', 'qr-service',
    'sim-scheme', 'sim-merchant', 'sim-gmeremit', 'sim-wallet', 'sim-rate-provider')
if ($Subset -eq 'money') { $fleet = @($fleet | Where-Object { $moneyNames -contains $_.name }) }

# Stateless / web-only services (no JPA/Kafka/Redis) get a smaller heap tier.
$statelessNames = @('smart-router', 'reporting-compliance', 'ops-partner-bff', 'kyb-adapter')

# Inter-service wiring. Each backend service defaults its peer base-URLs to Docker
# hostnames (e.g. http://merchant-qr-data:8080) which do NOT resolve when the fleet
# runs as bare localhost JVMs. We rewrite every gmepay.<service>.base-url to the peer's
# actual fleet port so the cross-service cascade (payment-executor -> merchant-qr-data /
# scheme-adapter / transaction-mgmt / revenue-ledger, ops-partner-bff -> its backends, ...)
# works locally. Property names are shared per-peer across all services, so one map is
# correct fleet-wide; passing a base-url a given service doesn't read is harmless.
# NB: scheme-adapter-zeropay reaches sim-scheme via gmepay.scheme.zeropay.base-url
# (defaults to http://localhost:9102) — a different property we intentionally leave alone.
$downstreamArgs = @()
foreach ($peer in $fleet) {
    if ($peer.type -eq 'service') {
        $downstreamArgs += "--gmepay.$($peer.name).base-url=http://localhost:$($peer.port)"
    }
}

# --- helpers ---------------------------------------------------------------
function Resolve-Jar($c) {
    $dir = if ($c.type -eq 'service') { Join-Path $root "services\$($c.name)\build\libs" }
           else { Join-Path $root "simulators\$($c.name)\build\libs" }
    if (-not (Test-Path $dir)) { return $null }
    Get-ChildItem $dir -Filter "$($c.name)-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*plain*' } | Select-Object -First 1 -ExpandProperty FullName
}

function Free-Port($port) {
    $owner = (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1).OwningProcess
    if ($owner) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 300; return $true }
    return $false
}

function Test-Up($port) {
    try { Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$port/v1/_probe" -TimeoutSec 2 | Out-Null; return $true }
    catch { return [bool]$_.Exception.Response }   # any HTTP status = serving
}

function Build-Fleet($items) {
    $svc = $items | Where-Object { $_.type -eq 'service' }
    if ($svc) {
        $tasks = $svc | ForEach-Object { ":services:$($_.name):bootJar" }
        Write-Host "  building services: $($svc.name -join ', ')" -ForegroundColor DarkGray
        & (Join-Path $root 'gradlew.bat') -p $root @tasks --console=plain 2>$null | Out-Null
    }
    foreach ($s in ($items | Where-Object { $_.type -eq 'sim' })) {
        Write-Host "  building sim: $($s.name)" -ForegroundColor DarkGray
        & (Join-Path $root 'gradlew.bat') -p (Join-Path $root "simulators\$($s.name)") bootJar --console=plain 2>$null | Out-Null
    }
}

function Start-TraceConsole {
    if (Get-NetTCPConnection -LocalPort 7099 -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "trace-console already running on 7099" -ForegroundColor DarkGray; return
    }
    $js = Join-Path $root '.smoke\trace-console.js'
    if (-not (Test-Path $js)) { Write-Warning "trace-console.js not found at $js — skipping tracer"; return }
    Start-Process -FilePath 'node' -ArgumentList @($js) -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDir 'trace-console.out.log') `
        -RedirectStandardError  (Join-Path $logDir 'trace-console.err.log')
    Write-Host "started trace-console -> $dashUrl" -ForegroundColor Green
}

function Get-JvmFlags($c) {
    # Lean JVM tax: SerialGC (no G1 region overhead on small heaps), C1-only JIT
    # (small code cache, faster start), capped code-cache/metaspace, small stacks,
    # JMX off. Heaps are STATIC per-tier — never MaxRAMPercentage (each of 23 JVMs
    # would claim a % of the whole 16GB and over-commit instantly).
    $common = @(
        '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=64m',
        '-Xss512k', '-XX:+ExitOnOutOfMemoryError',
        '-XX:+HeapDumpOnOutOfMemoryError', "-XX:HeapDumpPath=$logDir",
        '-Dspring.jmx.enabled=false', '-Dcom.sun.management.jmxremote=false'
    )
    # NB: no -Xms floor — let the heap start small and grow only as needed, so an IDLE
    # service commits little; -Xmx caps the ceiling, SerialGC returns freed memory to the OS.
    if ($Xmx) { return $common + @("-Xmx$Xmx", '-XX:MaxMetaspaceSize=128m') }   # global override
    if ($c.type -eq 'sim')              { return $common + @('-Xmx80m',  '-XX:MaxMetaspaceSize=96m') }
    if ($c.name -eq 'api-gateway')      { return $common + @('-Xmx144m', '-XX:MaxMetaspaceSize=128m') }
    if ($statelessNames -contains $c.name) { return $common + @('-Xmx112m', '-XX:MaxMetaspaceSize=96m') }
    return $common + @('-Xmx160m', '-XX:MaxMetaspaceSize=128m')                 # JPA tier
}

function Start-Component($c) {
    $jar = Resolve-Jar $c
    if (-not $jar) { Write-Warning "[$($c.name)] no jar (run with -Build) — skipped"; return }
    [void](Free-Port $c.port)
    # Lean Spring props: defer eager init, cap pools/threads, drop swagger generation.
    # All harmless on services that lack the relevant autoconfig (Spring just ignores them).
    # No functionality removed — springdoc/JPA/Kafka stay on the classpath.
    $spring = @(
        "--server.port=$($c.port)", "--spring.application.name=$($c.name)",
        '--spring.main.lazy-initialization=true',
        '--server.tomcat.threads.max=20', '--server.tomcat.threads.min-spare=2',
        '--spring.datasource.hikari.maximum-pool-size=5', '--spring.datasource.hikari.minimum-idle=1',
        '--spring.jpa.open-in-view=false', '--spring.task.scheduling.pool.size=1',
        '--spring.kafka.listener.concurrency=1',
        '--springdoc.api-docs.enabled=false', '--springdoc.swagger-ui.enabled=false'
    )
    if ($traceEnabled) { $spring += '--gmepay.trace.enabled=true' }
    # Point every gmepay.<peer>.base-url at the peer's localhost fleet port (services only;
    # sims keep their own per-sim properties via $c.args below).
    if ($c.type -eq 'service') { $spring += $downstreamArgs }
    if ($c.args) { $spring += $c.args }
    $a = (Get-JvmFlags $c) + @('-jar', $jar) + $spring
    Start-Process -FilePath 'java' -ArgumentList $a -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDir "$($c.name).out.log") `
        -RedirectStandardError  (Join-Path $logDir "$($c.name).err.log")
}

function Show-Status {
    $rows = foreach ($c in $fleet) {
        [pscustomobject]@{ Component = $c.name; Port = $c.port; Up = (Test-Up $c.port) }
    }
    $rows | Sort-Object Up, Component | Format-Table -AutoSize | Out-String -Width 120 | Write-Host
    $upCount = ($rows | Where-Object Up).Count
    Write-Host ("services/sims serving: {0}/{1}" -f $upCount, $fleet.Count) -ForegroundColor Cyan
    $tracerUp = [bool](Get-NetTCPConnection -LocalPort 7099 -State Listen -ErrorAction SilentlyContinue)
    Write-Host ("trace-console: " + $(if ($tracerUp) { "UP -> $dashUrl" } else { 'DOWN' })) -ForegroundColor Cyan
    if ($tracerUp) {
        try {
            $arr = (Invoke-RestMethod -UseBasicParsing -Uri "$dashUrl/api/calls?since=0" -TimeoutSec 4).calls
            $seen = $arr | Group-Object callee | Where-Object { $_.Name -ne '-' -and $_.Name -notmatch '^kafka:' } |
                Select-Object -ExpandProperty Name -Unique
            Write-Host ("components seen in tracer: {0}" -f ($seen | Measure-Object).Count) -ForegroundColor Cyan
        } catch { }
    }
}

function Stop-Fleet {
    Write-Host 'stopping fleet...' -ForegroundColor Yellow
    $ports = @(7099) + ($fleet | ForEach-Object { $_.port })
    $killed = 0
    foreach ($p in $ports) { if (Free-Port $p) { $killed++ } }
    Write-Host "stopped $killed listener(s)." -ForegroundColor Yellow
}

# --- main ------------------------------------------------------------------
foreach ($cmd in @('java', 'node')) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "$cmd not found on PATH" }
}
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

switch ($Action) {
    'stop'   { Stop-Fleet; break }
    'status' { Show-Status; break }
    default  {
        if ($Action -eq 'restart') { Stop-Fleet; Start-Sleep -Seconds 2 }

        $needBuild = if ($Build) { $fleet } else { $fleet | Where-Object { -not (Resolve-Jar $_) } }
        if ($needBuild) { Write-Host 'building jars...' -ForegroundColor Yellow; Build-Fleet $needBuild }

        Start-TraceConsole
        Write-Host "starting $($fleet.Count) components (trace=$traceEnabled, -Xmx$Xmx)..." -ForegroundColor Yellow
        foreach ($c in $fleet) { Start-Component $c; Write-Host "  -> $($c.name) :$($c.port)" -ForegroundColor DarkGray }

        Write-Host "`nwaiting up to 180s for services to come up (heavy: many JVMs + H2)..." -ForegroundColor Yellow
        $deadline = (Get-Date).AddSeconds(180)
        do {
            Start-Sleep -Seconds 6
            $up = ($fleet | Where-Object { Test-Up $_.port }).Count
            Write-Host ("  ... {0}/{1} serving" -f $up, $fleet.Count) -ForegroundColor DarkGray
        } while ($up -lt $fleet.Count -and (Get-Date) -lt $deadline)

        Write-Host ''
        Show-Status
        Write-Host "`nlogs: $logDir   |   dashboard: $dashUrl   |   stop: .\run-fleet.ps1 stop" -ForegroundColor Green
    }
}
