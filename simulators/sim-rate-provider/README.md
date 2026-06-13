# sim-rate-provider

Fake xe.com-style live FX rate provider. Used by the **rate-fx** service
(`XeRateClient`) to satisfy audit gap B4 without calling a real third-party API.

Runs on **port 9101**. Seeded USD-base mid-rates walk ±0.3 % every 60 s.

## What it fakes

- xe.com commercial rate feed: single-pair and multi-quote endpoints
- Supported currencies (USD base): KRW 1380, MNT 3450, KHR 4100, VND 25400,
  THB 36.5, SGD 1.35, CNY 7.2
- Cross-rates computed via USD (e.g. KRW/MNT = rate(USD→MNT) / rate(USD→KRW))
- Source label: `SIM_XE`
- KST timestamps (Asia/Seoul)

## How to run

```bash
# From the repo root — the root gradlew drives any sub-project via -p
./gradlew -p simulators/sim-rate-provider bootRun

# Or build the fat JAR first then run it
./gradlew -p simulators/sim-rate-provider build
java -jar simulators/sim-rate-provider/build/libs/sim-rate-provider-0.0.1-SNAPSHOT.jar
```

On Windows use `gradlew.bat` or invoke via PowerShell:
```powershell
C:\Users\GME\.claude\GMEPay+\code\gradlew.bat -p simulators/sim-rate-provider bootRun
```

## Endpoint list

| Method | Path | Params | Description |
|--------|------|--------|-------------|
| GET | `/v1/rates` | `base`, `quote` | Single mid-rate (base+quote required) |
| GET | `/v1/rates` | `base` only | All quote currencies off base |
| GET | `/v1/rates/pairs` | — | List of all supported pairs |
| GET | `/index.html` | — | Live rate table (auto-refresh 5 s) |

## Example curl

```bash
# Single pair
curl "http://localhost:9101/v1/rates?base=USD&quote=KRW"
# {"base":"USD","quote":"KRW","rate":"1380.000000","asOf":"2026-06-13T...+09:00","source":"SIM_XE"}

# All quotes off USD
curl "http://localhost:9101/v1/rates?base=USD"

# Cross rate
curl "http://localhost:9101/v1/rates?base=KRW&quote=MNT"

# Supported pairs
curl "http://localhost:9101/v1/rates/pairs"
```
