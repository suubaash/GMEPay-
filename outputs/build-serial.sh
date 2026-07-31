#!/bin/bash
# Build GMEPay+ service images ONE AT A TIME to fit the 5.8GB WSL memory cap
# (parallel builds OOM -> BuildKit grpc cancellation). Dep cache is reused via
# the sharing=locked gradle cache mount. Then start the full stack.
cd /mnt/d/GMEPay+/code
SVCS="config-registry rate-fx prefunding smart-router qr-service auth-identity \
transaction-mgmt payment-executor merchant-qr-data scheme-adapter-zeropay \
sim-nepal-qr scheme-adapter-nepal notification-webhook settlement-reconciliation \
revenue-ledger reporting-compliance ops-partner-bff api-gateway"

fail=""
for s in $SVCS; do
  echo ">>> building $s ..."
  if docker compose --profile full build "$s" >/tmp/build-$s.log 2>&1; then
    echo "    OK $s"
  else
    echo "    FAIL $s"; fail="$fail $s"; tail -12 /tmp/build-$s.log
  fi
done

echo "=================================="
if [ -n "$fail" ]; then
  echo "FAILED:$fail"
else
  echo "ALL 18 BUILT — starting stack"
  docker compose --profile full up -d
fi
