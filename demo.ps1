# GMEPay+ — build, unit-test, and smoke-test the running services.
# Usage:  cd C:\Users\GME\.claude\GMEPay+\code ;  .\demo.ps1
$ErrorActionPreference = "Stop"
$code = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $code

Write-Host "=== 1) Build + unit tests ===" -ForegroundColor Cyan
& .\gradlew.bat build --console=plain
if ($LASTEXITCODE -ne 0) { Write-Host "Build/tests FAILED" -ForegroundColor Red; exit 1 }
Write-Host "All unit tests passed." -ForegroundColor Green

function Demo-Service {
    param([string]$name, [string]$jar, [int]$port, [scriptblock]$call)
    Write-Host "`n=== Booting $name on port $port ===" -ForegroundColor Cyan
    if (-not (Test-Path $jar)) { Write-Host "  jar not found: $jar" -ForegroundColor Yellow; return }
    $proc = Start-Process java -ArgumentList "-jar", "`"$jar`"", "--server.port=$port" -PassThru -WindowStyle Hidden
    try {
        $ok = $false
        for ($i = 0; $i -lt 40; $i++) {
            Start-Sleep -Milliseconds 1500
            try { & $call; $ok = $true; break } catch { }
        }
        if (-not $ok) { Write-Host "  (service did not respond in time)" -ForegroundColor Yellow }
    } finally {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        Write-Host "  stopped $name"
    }
}

$v = "0.1.0"

Demo-Service "rate-fx" "services\rate-fx\build\libs\rate-fx-$v.jar" 8091 {
    $body = @{ targetPayout = 13500; collectionCurrency = "MNT"; settleACurrency = "MNT";
        settleBCurrency = "KRW"; payoutCurrency = "KRW"; costRateColl = 3500; costRatePay = 1350;
        mA = 0.01; mB = 0.01; serviceCharge = 500 } | ConvertTo-Json
    $r = Invoke-RestMethod "http://localhost:8091/v1/rates" -Method Post -Body $body -ContentType "application/json"
    Write-Host "  rate quote (MNT->KRW):" -ForegroundColor Green
    $r | Format-List
}

Demo-Service "smart-router" "services\smart-router\build\libs\smart-router-$v.jar" 8092 {
    $r = Invoke-RestMethod "http://localhost:8092/v1/route?country=KR"
    Write-Host "  KR routes to: $r" -ForegroundColor Green
}

Demo-Service "config-registry" "services\config-registry\build\libs\config-registry-$v.jar" 8093 {
    $rule = @{ partnerId = "SENDMN"; schemeId = "ZEROPAY"; direction = "INBOUND";
        settleACurrency = "MNT"; settleBCurrency = "KRW"; mA = 0.01; mB = 0.01; serviceCharge = 500 } | ConvertTo-Json
    $r = Invoke-RestMethod "http://localhost:8093/v1/rules/validate" -Method Post -Body $rule -ContentType "application/json"
    Write-Host "  rule validation: $r" -ForegroundColor Green
}

Write-Host "`n=== Done ===" -ForegroundColor Cyan
