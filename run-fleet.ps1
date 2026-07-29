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
  Memory: all ~28 JVMs need ~9-11 GB. If services get reaped, use -Subset money or
  lower -Xmx (e.g. -Xmx 224m). Run from any path (uses its own folder as the repo root).
  First run from a new shell may need:  Unblock-File .\run-fleet.ps1

  ENVIRONMENT AN OPERATOR SHOULD EXPORT BEFORE `start`
  ----------------------------------------------------
  Both are optional here — this script supplies dev defaults so a bare `.\run-fleet.ps1`
  still works — but both MUST be set explicitly for anything shared or tunnelled:

    $env:GMEPAY_INTERNAL_AUTH_SECRET = '<random 32+ bytes>'
        The shared service-to-service token behind the X-Gme-Internal header (#90 / T0-2 / T0-5).
        FAIL-CLOSED: auth-identity, prefunding, scheme-adapter-zeropay and rate-fx REFUSE TO START
        without it, and any caller that omits it is answered 401. Exported to every child JVM below
        so both sides of every gated edge agree. Default = a clearly-non-production dev literal
        (same idiom as docker-compose.yml's x-internal-auth-secret anchor; gap-register item T0-6).

    $env:OIDC_ISSUER_URI = 'http://localhost:8097/realms/gmepay'
        Browser-facing Keycloak issuer for the two resource servers (ops-partner-bff, api-gateway).
        Their Java default is still the stale :8090 — which is scheme-adapter-zeropay's port — so
        without this a host-run BFF 401s every request (gap T1-2). Default = the canonical local
        topology asserted by `node docker/keycloak/check-topology.mjs`. Keycloak itself is NOT
        started by this script: run `docker compose --profile core up -d keycloak` (host port 8097).

  Full env-var matrix (service x compose/Helm/run-fleet): docs/COMPOSE.md, section
  "Internal-auth secret (GMEPAY_INTERNAL_AUTH_SECRET)".
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

# --- shared config every child JVM inherits ---------------------------------
# Start-Process inherits this process's environment, so setting these once here reaches every
# service below. Passing them as env (not --spring CLI args) is deliberate: each service reads a
# DIFFERENT property name off the same variable (gmepay.internal-auth.secret,
# gmepay.auth-identity.internal-secret, spring.security...issuer-uri), and Spring's relaxed binding
# resolves all of them from the env var. One assignment, no per-service arg lists to keep in sync.

# Service-to-service internal-auth token (#90 / T0-2 / T0-5). FAIL-CLOSED: auth-identity,
# prefunding, scheme-adapter-zeropay and rate-fx refuse to START without it; payment-executor,
# qr-service, config-registry, ops-partner-bff, settlement-reconciliation and api-gateway must
# present the SAME value or their calls are refused 401. The dev default is a clearly-non-production
# marker, matching docker-compose.yml's x-internal-auth-secret anchor (gap-register item T0-6).
$internalAuthSecret = $env:GMEPAY_INTERNAL_AUTH_SECRET
if (-not $internalAuthSecret) {
    $internalAuthSecret = 'dev-internal-svc-secret-not-for-prod'
    Write-Host "GMEPAY_INTERNAL_AUTH_SECRET not set - using the dev default (NOT for any shared or tunnelled host)" -ForegroundColor DarkYellow
}
$env:GMEPAY_INTERNAL_AUTH_SECRET = $internalAuthSecret

# OIDC issuer for the two resource servers (ops-partner-bff :18095, api-gateway :18080). Their Java
# default is the stale :8090 (= scheme-adapter-zeropay), so a host-run BFF 401s everything without
# this (gap T1-2). Canonical local topology, asserted by docker/keycloak/check-topology.mjs.
if (-not $env:OIDC_ISSUER_URI) { $env:OIDC_ISSUER_URI = 'http://localhost:8097/realms/gmepay' }

# --- the fleet -------------------------------------------------------------
# type: service jars are <name>-0.1.0.jar under services\<name>; sim jars are
# <name>-*.jar under simulators\<name>. 'args' are extra Spring CLI args.
$fleet = @(
    # Partner ACTIVATION issues real credentials (gap T1-1): the auth-identity + notification-webhook
    # clients default to `rest`, so the fleet must point them at the 18xxx band or activation 502s
    # against the compose-internal hostnames. auth-identity = 18085, notification-webhook = 18086.
    @{ name = 'config-registry';           type = 'service'; port = 18081; args = @(
            '--gmepay.auth-identity.client=rest'
            '--gmepay.auth-identity.base-url=http://localhost:18085'
            '--gmepay.notification-webhook.client=rest'
            '--gmepay.notification-webhook.base-url=http://localhost:18086') }
    # transaction-mgmt persists to the REAL dockerized Postgres (txndb, host port 5434)
    # instead of throwaway H2, so transactions survive fleet restarts; outbox events
    # publish to the real Kafka (host EXTERNAL listener 29092).
    @{ name = 'transaction-mgmt';           type = 'service'; port = 18082; args = @(
            '--spring.datasource.url=jdbc:postgresql://localhost:5434/txndb'
            '--spring.datasource.driver-class-name=org.postgresql.Driver'
            '--spring.datasource.username=gmepay'
            '--spring.datasource.password=gmepay'
            '--spring.kafka.bootstrap-servers=localhost:29092') }
    @{ name = 'merchant-qr-data';           type = 'service'; port = 18083 }
    @{ name = 'payment-executor';           type = 'service'; port = 18084; args = @(
            '--gmepay.config-registry.base-url=http://localhost:18081'
            '--gmepay.rate-fx.base-url=http://localhost:18101'
            '--gmepay.prefunding.base-url=http://localhost:18088'
            '--gmepay.merchant-qr-data.base-url=http://localhost:18083'
            '--gmepay.scheme-adapter-zeropay.base-url=http://localhost:18090'
            '--gmepay.transaction-mgmt.base-url=http://localhost:18082'
            '--gmepay.revenue-ledger.base-url=http://localhost:18092'
            '--gmepay.scheme-adapters.NEPAL.base-url=http://localhost:18094'
            '--gmepay.scheme-adapters.SENDMN.base-url=http://localhost:18096'
            '--gmepay.self.base-url=http://localhost:18084') }
    @{ name = 'auth-identity';              type = 'service'; port = 18085 }
    @{ name = 'notification-webhook';       type = 'service'; port = 18086 }
    @{ name = 'reporting-compliance';       type = 'service'; port = 18087 }
    @{ name = 'prefunding';                 type = 'service'; port = 18088 }
    @{ name = 'qr-service';                 type = 'service'; port = 18089 }
    @{ name = 'scheme-adapter-zeropay';     type = 'service'; port = 18090; args = @('--gmepay.scheme.zeropay.base-url=http://localhost:9102/v1/scheme') }
    @{ name = 'scheme-adapter-nepal';       type = 'service'; port = 18094; args = @('--gmepay.scheme.nepal.base-url=http://localhost:9106') }
    # SendMN (Mongolia) + 9Pay (Vietnam) scheme edges — QR_SCHEME_ACCOMMODATION_PLAN Phase 5.
    # Adapter default ports (8093/8096) stay for standalone runs; the fleet uses the 18xxx band.
    @{ name = 'scheme-adapter-sendmn';      type = 'service'; port = 18096; args = @('--sendmn.base-url=http://localhost:9108') }
    @{ name = 'scheme-adapter-ninepay';     type = 'service'; port = 18097; args = @('--gmepay.scheme.ninepay.base-url=http://localhost:9107') }
    @{ name = 'smart-router';               type = 'service'; port = 18091 }
    @{ name = 'revenue-ledger';             type = 'service'; port = 18092 }
    @{ name = 'settlement-reconciliation';  type = 'service'; port = 18093 }
    # Every Partner Portal page must read the service that owns the fact (gap T1-3), so all five
    # upstream selectors are 'rest' and each base-url is pinned to the 18xxx host band:
    #   transaction-mgmt   -> Transactions page AND the CSV statement (RestStatementClient)
    #   auth-identity      -> API Keys page (RestApiKeyClient) + sandbox key issuance + RBAC
    #   config-registry    -> Profile page (real go_live_at) + the partner-code -> numeric-id
    #                         resolution every other portal read depends on (PartnerDirectory)
    #   prefunding         -> Overview + Balance pages for REAL partner codes, not just
    #                         partner_test_001..003 (the stub's only rows)
    #   notification-webhook -> Webhooks page (RestPortalWebhookClient)
    @{ name = 'ops-partner-bff';            type = 'service'; port = 18095; args = @(
            '--gmepay.transaction-mgmt.client=rest'
            '--gmepay.transaction-mgmt.base-url=http://localhost:18082'
            '--gmepay.auth-identity.client=rest'
            '--gmepay.auth-identity.base-url=http://localhost:18085'
            '--gmepay.config-registry.client=rest'
            '--gmepay.config-registry.base-url=http://localhost:18081'
            '--gmepay.prefunding.client=rest'
            '--gmepay.prefunding.base-url=http://localhost:18088'
            '--gmepay.notification-webhook.client=rest'
            '--gmepay.notification-webhook.base-url=http://localhost:18086') }
    @{ name = 'kyb-adapter';                type = 'service'; port = 18098 }
    @{ name = 'rate-fx';                    type = 'service'; port = 18101 }
    @{ name = 'api-gateway';                type = 'service'; port = 18080 }
    @{ name = 'sim-rate-provider';          type = 'sim';     port = 9101 }
    @{ name = 'sim-scheme';                 type = 'sim';     port = 9102; args = @('--gmepay.sim.scheme.profile=ZEROPAY') }
    @{ name = 'sim-wallet';                 type = 'sim';     port = 9103 }
    @{ name = 'sim-merchant';               type = 'sim';     port = 9104; args = @('--gmepay.sim.merchant.merchant-qr-data-base-url=http://localhost:18083') }
    @{ name = 'sim-gmeremit';               type = 'sim';     port = 9105; args = @(
            '--gmepay.sim.gmeremit.gmepay-base-url=http://localhost:18084'
            '--gmepay.sim.nepal-qr.base-url=http://localhost:9106') }
    @{ name = 'sim-nepal-qr';               type = 'sim';     port = 9106 }
    # sim-sendmn's application.yml default is 9106, but that is sim-nepal-qr's fleet port —
    # the fleet pins 9108 via --server.port instead (scheme-adapter-sendmn above points there).
    # fx-push.url targets the sendmn adapter's partner-hosted FX endpoint (push is off by
    # default; trigger manually with POST /sim/fx-rate/push).
    @{ name = 'sim-sendmn';                 type = 'sim';     port = 9108; args = @('--gmepay.sim.sendmn.fx-push.url=http://localhost:18096/partner-hosted/fx-rate') }
    # sim-ninepay pushes terminal-status IPNs back into the ninepay adapter's inbound edge.
    @{ name = 'sim-ninepay';                type = 'sim';     port = 9107; args = @('--sim.ninepay.ipn-url=http://localhost:18097/scheme/ipn') }
)

# Running all ~28 JVMs at once needs ~9-11 GB RAM; on a tight box the OS may reap some.
# -Subset money boots just the core payment cascade (~14 components) which fits comfortably.
# auth-identity is in the money subset because ops-partner-bff and config-registry BOTH point their
# rest clients at it (--gmepay.auth-identity.client=rest): without it the RBAC page reads nothing and
# partner activation 502s instead of minting a verifiable API key (gap T1-1).
$moneyNames = @('config-registry', 'transaction-mgmt', 'payment-executor', 'scheme-adapter-zeropay',
    'scheme-adapter-nepal', 'rate-fx', 'prefunding', 'ops-partner-bff', 'merchant-qr-data', 'qr-service',
    'auth-identity',
    'sim-scheme', 'sim-merchant', 'sim-gmeremit', 'sim-wallet', 'sim-nepal-qr', 'sim-rate-provider')
if ($Subset -eq 'money') { $fleet = @($fleet | Where-Object { $moneyNames -contains $_.name }) }

# Stateless / web-only services (no JPA/Kafka/Redis) get a smaller heap tier.
$statelessNames = @('smart-router', 'reporting-compliance', 'ops-partner-bff', 'kyb-adapter', 'scheme-adapter-nepal')

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
        # cmd /c so gradle's stderr WARNINGS never become PowerShell NativeCommandErrors
        # (with $ErrorActionPreference='Stop', a bare 2>$null on a native command turns any
        # stderr line — even a deprecation warning — into a script-killing exception).
        cmd /c "`"$(Join-Path $root 'gradlew.bat')`" -p `"$root`" $($tasks -join ' ') --console=plain 2>nul" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "service jar build failed (exit $LASTEXITCODE)" }
    }
    foreach ($s in ($items | Where-Object { $_.type -eq 'sim' })) {
        Write-Host "  building sim: $($s.name)" -ForegroundColor DarkGray
        cmd /c "`"$(Join-Path $root 'gradlew.bat')`" -p `"$(Join-Path $root "simulators\$($s.name)")`" bootJar --console=plain 2>nul" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "sim jar build failed: $($s.name) (exit $LASTEXITCODE)" }
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
    # JMX off. Heaps are STATIC per-tier — never MaxRAMPercentage (each of ~28 JVMs
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
    # NB: skip any key the component already sets explicitly in $c.args — Spring
    # comma-joins repeated --key=value CLI args into "v1,v2", which breaks URI props
    # (payment-executor merchant resolve failed with "unsupported URI http://...,http:/...").
    if ($c.type -eq 'service') {
        $explicitKeys = @()
        if ($c.args) { $explicitKeys = @($c.args | ForEach-Object { ($_ -split '=', 2)[0] }) }
        $spring += @($downstreamArgs | Where-Object { $explicitKeys -notcontains (($_ -split '=', 2)[0]) })
    }
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
        Write-Host ("  internal-auth secret: {0}  |  OIDC issuer: {1}" -f `
            $(if ($internalAuthSecret -eq 'dev-internal-svc-secret-not-for-prod') { 'dev default' } else { 'from environment' }),
            $env:OIDC_ISSUER_URI) -ForegroundColor DarkGray
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
