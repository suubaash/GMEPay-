#!/bin/bash
# > 작업: 100 wallet txns (50 Nepal QR + 50 ZeroPay QR) via sim-gmeremit, retry until successful / 출처: agent
# Drives POST :9105 /v1/gmeremit/users/{u}/pay. Prints one line per attempt + a final summary.
set -u
NEPAL_QR="00020101021126350011fonepay.com071640897200000017835204541253035245802NP5914SudanMerchant6015AathraiTriveni6206070231630421f2"
ZP_QR="00020101021229330011com.zeropay0114SMOKE_MERCH_015204599953034105405100005802KR5914Smoke Merchant6005Seoul6304E765"
USERS=(user-001 user-002 user-003)
TARGET_NP=50; TARGET_ZP=50
ok_np=0; ok_zp=0; fail=0; attempt=0; MAX_ATTEMPTS=600
declare -A fail_reasons

pay() { # $1=user $2=qr $3=amount -> echo status body
  curl -s -m 30 -X POST "http://localhost:9105/v1/gmeremit/users/$1/pay" \
       -H "Content-Type: application/json" \
       -d "{\"qrPayload\":\"$2\",\"amount\":\"$3\"}"
}

while { [ $ok_np -lt $TARGET_NP ] || [ $ok_zp -lt $TARGET_ZP ]; } && [ $attempt -lt $MAX_ATTEMPTS ]; do
  attempt=$((attempt+1))
  user=${USERS[$(( (attempt-1) % 3 ))]}
  # Alternate corridors, but stick to whichever still needs successes
  if [ $ok_np -lt $TARGET_NP ] && { [ $((attempt % 2)) -eq 0 ] || [ $ok_zp -ge $TARGET_ZP ]; }; then
    corridor=NEPAL; resp=$(pay "$user" "$NEPAL_QR" "100")
  else
    corridor=ZEROPAY; resp=$(pay "$user" "$ZP_QR" "10000")
  fi
  if echo "$resp" | grep -q '"status":"APPROVED"'; then
    [ "$corridor" = NEPAL ] && ok_np=$((ok_np+1)) || ok_zp=$((ok_zp+1))
    total=$((ok_np+ok_zp))
    if [ $((total % 10)) -eq 0 ]; then echo "PROGRESS: $total/100 (NP=$ok_np ZP=$ok_zp fail=$fail)"; fi
  else
    fail=$((fail+1))
    reason=$(echo "$resp" | grep -o '"declineReason":"[^"]*"' | head -1)
    [ -z "$reason" ] && reason="${resp:0:120}"
    fail_reasons["$reason"]=$(( ${fail_reasons["$reason"]:-0} + 1 ))
    echo "FAIL #$fail ($corridor/$user): $reason"
    # Back off briefly on failure so we don't hammer a broken dependency
    sleep 1
    # Abort early if the same failure repeats 15x — something structural is broken
    if [ "${fail_reasons["$reason"]}" -ge 15 ]; then echo "ABORT: repeated failure: $reason"; break; fi
  fi
done

echo "=================================================="
echo "RESULT: NP=$ok_np/$TARGET_NP ZP=$ok_zp/$TARGET_ZP failures=$fail attempts=$attempt"
for r in "${!fail_reasons[@]}"; do echo "  reason ${fail_reasons[$r]}x: $r"; done
[ $ok_np -ge $TARGET_NP ] && [ $ok_zp -ge $TARGET_ZP ] && echo "BATCH_COMPLETE" || echo "BATCH_INCOMPLETE"
