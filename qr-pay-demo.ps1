<#
.SYNOPSIS
  End-to-end demo: a wallet scans a merchant QR -> a transaction is created -> the ZeroPay scheme
  confirms it paid the merchant -> we print ALL the values that created the txn.

.DESCRIPTION
  Boots the minimal domestic (GMEREMIT KRW) payment cascade as 5 detached JVMs on localhost, wiring the
  cross-service base-URLs explicitly (run-fleet.ps1 leaves these at their docker DNS defaults, which do
  not resolve on a plain box). No Docker / Postgres / Mongo needed: merchant-qr-data uses its seeded
  in-memory store, transaction-mgmt + scheme-adapter fall back to H2.

  Flow driven over HTTP:
    1. register the demo merchant (SMOKE_MERCH_01) in sim-scheme          (POST  :9102 /v1/scheme/merchants)
    2. WALLET SCANS + PAYS                                                 (POST  :18084 /v1/pay)
       payment-executor -> merchant-qr-data (resolve) -> scheme-adapter -> sim-scheme (APPROVED) -> txn
    3. VIEW ALL THE VALUES that created the txn                           (GET   :18082 /v1/transactions/{txnRef})

  The merchant QR (.smoke/05_qr_payload.txt) resolves in BOTH merchant-qr-data (seeded SMOKE_MERCH_01)
  and sim-scheme (registered in step 1), so one scanned QR drives the whole flow.

  Self-terminating: every JVM it starts is stopped in the finally block.

.PARAMETER Build
  Force a rebuild of the needed boot jars first. Otherwise only missing jars are built.

.EXAMPLE
  .\qr-pay-demo.ps1            # build-if-missing, boot, run one wallet payment, print values, tear down
  .\qr-pay-demo.ps1 -Build     # rebuild the 5 jars first
#>
[CmdletBinding()]
param([switch]$Build)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

# ---- the QR a wallet scans (EMVCo; merchant id "SMOKE_MERCH_01" in sub-tag 01; amount 10000 KRW) ----
$QR        = '00020101021229330011com.zeropay0114SMOKE_MERCH_015204599953034105405100005802KR5914Smoke Merchant6005Seoul6304E765'
$AMOUNT    = '10000'   # matches the amount embedded in the QR

# ---- components: name | kind | port | extra Spring args (localhost base-url wiring) ----
$comps = @(
    @{ name='merchant-qr-data';       kind='service'; port=18083; args=@() }
    @{ name='transaction-mgmt';       kind='service'; port=18082; args=@() }
    @{ name='sim-scheme';             kind='sim';     port=9102;  args=@('--gmepay.sim.scheme.profile=ZEROPAY') }
    @{ name='scheme-adapter-zeropay'; kind='service'; port=18090; args=@('--gmepay.scheme.zeropay.base-url=http://localhost:9102/v1/scheme') }
    @{ name='payment-executor';       kind='service'; port=18084; args=@(
            '--gmepay.merchant-qr-data.base-url=http://localhost:18083'
            '--gmepay.scheme-adapter-zeropay.base-url=http://localhost:18090'
            '--gmepay.transaction-mgmt.base-url=http://localhost:18082'
            '--gmepay.payment.merchant-validation=lenient') }
)

function Resolve-Jar($c) {
    $dir = if ($c.kind -eq 'sim') { Join-Path $root "simulators\$($c.name)\build\libs" }
           else                   { Join-Path $root "services\$($c.name)\build\libs" }
    Get-ChildItem $dir -Filter "$($c.name)-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1 -ExpandProperty FullName
}

function Wait-Port([int]$port, [int]$timeoutSec = 90) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('localhost', $port); $c.Close(); return $true }
        catch { Start-Sleep -Milliseconds 1000 }
    }
    return $false
}

function Invoke-WithRetry([scriptblock]$call, [int]$tries = 20) {
    for ($i = 0; $i -lt $tries; $i++) {
        try { return & $call } catch { Start-Sleep -Milliseconds 1500 }
    }
    throw "call did not succeed after $tries attempts"
}

# ---- 1) build jars (if missing or -Build) ----
$needBuild = $Build -or ($comps | Where-Object { -not (Resolve-Jar $_) })
if ($needBuild) {
    Write-Host '=== Building boot jars ===' -ForegroundColor Cyan
    $tasks = ($comps | ForEach-Object {
        if ($_.kind -eq 'sim') { ":simulators:$($_.name):bootJar" } else { ":services:$($_.name):bootJar" }
    })
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { Write-Host 'Build FAILED' -ForegroundColor Red; exit 1 }
}

$procs = @()
try {
    # ---- 2) boot the cascade ----
    Write-Host "`n=== Booting the domestic payment cascade (5 JVMs) ===" -ForegroundColor Cyan
    foreach ($c in $comps) {
        $jar = Resolve-Jar $c
        if (-not $jar) { throw "jar not found for $($c.name) — run with -Build" }
        $spring = @("--server.port=$($c.port)", "--spring.application.name=$($c.name)") + $c.args
        $argList = @('-Xmx256m', '-jar', "`"$jar`"") + $spring
        $p = Start-Process java -ArgumentList $argList -PassThru -WindowStyle Hidden
        $procs += $p
        Write-Host ("  starting {0,-24} :{1}" -f $c.name, $c.port)
    }

    Write-Host "`n=== Waiting for services to listen ===" -ForegroundColor Cyan
    foreach ($c in $comps) {
        if (Wait-Port $c.port) { Write-Host "  up: $($c.name) :$($c.port)" -ForegroundColor Green }
        else { throw "$($c.name) did not come up on :$($c.port)" }
    }
    Start-Sleep -Seconds 5   # let Spring finish wiring after the port opens

    # ---- 3) register the merchant in sim-scheme (so authorize approves) ----
    Write-Host "`n=== 1. Register merchant SMOKE_MERCH_01 in the ZeroPay scheme (sim) ===" -ForegroundColor Cyan
    $reg = @{ merchantId='SMOKE_MERCH_01'; name='Smoke Merchant'; city='Seoul'; mcc='5999' } | ConvertTo-Json
    Invoke-WithRetry { Invoke-RestMethod 'http://localhost:9102/v1/scheme/merchants' -Method Post -Body $reg -ContentType 'application/json' } | Out-Null
    Write-Host '  merchant registered.' -ForegroundColor Green

    # ---- 4) WALLET SCANS THE QR AND PAYS ----
    Write-Host "`n=== 2. Wallet scans the merchant QR and pays (POST /v1/pay) ===" -ForegroundColor Cyan
    $payBody = @{ qrPayload=$QR; amountKrw=$AMOUNT; partner='GMEREMIT'; userRef='demo-wallet-1' } | ConvertTo-Json
    $pay = Invoke-WithRetry { Invoke-RestMethod 'http://localhost:18084/v1/pay' -Method Post -Body $payBody -ContentType 'application/json' }
    Write-Host '  WALLET RESPONSE (scheme confirmed the merchant was paid):' -ForegroundColor Green
    $pay | Format-List | Out-String | Write-Host

    if (-not $pay.txnRef) { throw "no txnRef on the wallet response — payment did not complete" }

    # ---- 5) VIEW ALL THE VALUES THAT CREATED THE TXN ----
    Write-Host "=== 3. All the values that created the txn (GET /v1/transactions/$($pay.txnRef)) ===" -ForegroundColor Cyan
    $txn = Invoke-WithRetry { Invoke-RestMethod "http://localhost:18082/v1/transactions/$($pay.txnRef)" }
    $txn | Format-List | Out-String | Write-Host

    Write-Host '=== DONE — transaction completed end to end ===' -ForegroundColor Green
    Write-Host ("  status={0}  schemeTxnRef={1}  approval={2}  merchant={3}" -f `
        $txn.status, $txn.schemeTxnRef, $txn.schemeApprovalCode, $txn.merchantId) -ForegroundColor Green
}
finally {
    Write-Host "`n=== Stopping services ===" -ForegroundColor Cyan
    foreach ($p in $procs) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    Write-Host '  all demo JVMs stopped.'
}
