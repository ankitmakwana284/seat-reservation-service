#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
SHOW_NAME="stampede-show-$(date +%s)"
TOTAL_SEATS=50
CONCURRENT_USERS=200
HOT_SEAT="A1"

echo "=========================================================="
echo " Starting Seat Reservation Concurrency Burst Test"
echo " Target URL: $BASE_URL"
echo "=========================================================="

# 1. Health Check
echo "[1/4] Checking target health..."
HEALTH=$(curl -s "$BASE_URL/actuator/health" || true)
if [[ "$HEALTH" != *"UP"* ]]; then
  echo "Target at $BASE_URL is not healthy! Output: $HEALTH"
  exit 1
fi
echo "Service is healthy and ready."

# 2. Create Fresh Show
echo "[2/4] Creating fresh test show with $TOTAL_SEATS seats..."
SEATS_JSON="["
for i in $(seq 1 $TOTAL_SEATS); do
  SEATS_JSON+="\"A$i\""
  if [ "$i" -lt "$TOTAL_SEATS" ]; then SEATS_JSON+=","; fi
done
SEATS_JSON+="]"

CREATE_SHOW_PAYLOAD=$(cat <<EOF
{
  "name": "$SHOW_NAME",
  "seats": $SEATS_JSON,
  "price_paise": 50000,
  "per_user_limit": 4
}
EOF
)

SHOW_RES=$(curl -s -X POST "$BASE_URL/shows" \
  -H "Content-Type: application/json" \
  -d "$CREATE_SHOW_PAYLOAD")

SHOW_ID=$(echo "$SHOW_RES" | grep -o '"id":[0-9]*' | head -n1 | cut -d':' -f2)

if [ -z "$SHOW_ID" ]; then
  echo "Failed to create show: $SHOW_RES"
  exit 1
fi
echo "Created show ID: $SHOW_ID"

# 3. Fire Concurrent Burst targeting HOT SEAT (A1)
echo "[3/4] Firing $CONCURRENT_USERS concurrent requests fighting over hot seat '$HOT_SEAT'..."

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

# Spawn background curls
for i in $(seq 1 "$CONCURRENT_USERS"); do
  (
    USER_ID="burst_user_${i}"
    IDEM_KEY="key_${SHOW_ID}_${USER_ID}"
    
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer $USER_ID" \
      -H "Content-Type: application/json" \
      -d "{\"seats\": [\"$HOT_SEAT\"], \"idempotency_key\": \"$IDEM_KEY\"}")
    
    echo "$STATUS" >> "$TMP_DIR/results.txt"
  ) &

  # Throttle max concurrent active curls to 50
  if (( i % 50 == 0 )); then
    wait
  fi
done

wait

# 4. Tally Outcomes
CONFIRMED_201=$(grep -c "^201$" "$TMP_DIR/results.txt" 2>/dev/null) || CONFIRMED_201=0
CONFLICT_409=$(grep -c "^409$" "$TMP_DIR/results.txt" 2>/dev/null) || CONFLICT_409=0
SERVER_ERR_5XX=$(grep -c "^5" "$TMP_DIR/results.txt" 2>/dev/null) || SERVER_ERR_5XX=0
OTHER_RESPONSES=$(grep -v -E "^(201|409|5[0-9]{2})$" "$TMP_DIR/results.txt" 2>/dev/null | wc -l | tr -d '[:space:]') || OTHER_RESPONSES=0

CONFIRMED_201=$(echo "$CONFIRMED_201" | tr -d '[:space:]')
CONFLICT_409=$(echo "$CONFLICT_409" | tr -d '[:space:]')
SERVER_ERR_5XX=$(echo "$SERVER_ERR_5XX" | tr -d '[:space:]')
echo ""
echo "=========================================================="
echo " Burst Test Results (Hot Seat Contention)"
echo " Total Requests: $CONCURRENT_USERS"
echo " 201 Created (Winner):     $CONFIRMED_201 (Expected: 1)"
echo " 409 Conflict (Declined):   $CONFLICT_409 (Expected: $((CONCURRENT_USERS - 1)))"
echo " 5xx Server Errors:         $SERVER_ERR_5XX (Expected: 0)"
echo " Other Status Codes:        $OTHER_RESPONSES"
echo "=========================================================="

# 5. Verify Show State Reconciliation Invariant
echo ""
echo "[4/4] Verifying reconciliation invariant (available + held + confirmed == total_seats)..."
FINAL_STATE=$(curl -s "$BASE_URL/shows/$SHOW_ID")
echo "Final Show State:"
echo "$FINAL_STATE"
echo ""

AVAIL=$(echo "$FINAL_STATE" | grep -o '"available":[0-9]*' | head -n1 | cut -d':' -f2)
HELD=$(echo "$FINAL_STATE" | grep -o '"held":[0-9]*' | head -n1 | cut -d':' -f2)
CONF=$(echo "$FINAL_STATE" | grep -o '"confirmed":[0-9]*' | head -n1 | cut -d':' -f2)
TOTAL=$(echo "$FINAL_STATE" | grep -o '"total_seats":[0-9]*' | head -n1 | cut -d':' -f2)

CALCULATED_TOTAL=$((AVAIL + HELD + CONF))

echo "Reconciliation check: $AVAIL (available) + $HELD (held) + $CONF (confirmed) = $CALCULATED_TOTAL (expected: $TOTAL)"

if [ "$CONFIRMED_201" -eq 1 ] && [ "$SERVER_ERR_5XX" -eq 0 ] && [ "$CALCULATED_TOTAL" -eq "$TOTAL" ]; then
  echo "SUCCESS: Concurrency guarantees & reconciliation invariant verified!"
else
  echo "FAILURE: Invariant check did not match expected values."
  exit 1
fi