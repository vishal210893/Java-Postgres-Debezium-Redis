#!/bin/bash
# =============================================================================
# CDC Pipeline End-to-End Test Script
# =============================================================================
# Tests CREATE, READ, UPDATE, PATCH, DELETE operations and verifies
# CDC propagation from PostgreSQL → Redis via the active pipeline.
#
# Usage:
#   ./scripts/test-cdc-pipeline.sh              # Run against localhost:8082
#   ./scripts/test-cdc-pipeline.sh 8082         # Specify port
# =============================================================================

set -e

BASE_URL="http://localhost:${1:-8082}"
PASS=0
FAIL=0
TOTAL=0

# --- Helpers ---

green()  { printf "\033[32m%s\033[0m\n" "$1"; }
red()    { printf "\033[31m%s\033[0m\n" "$1"; }
yellow() { printf "\033[33m%s\033[0m\n" "$1"; }
bold()   { printf "\033[1m%s\033[0m\n" "$1"; }

assert_eq() {
    local label="$1" expected="$2" actual="$3"
    TOTAL=$((TOTAL + 1))
    if [ "$expected" = "$actual" ]; then
        green "  PASS: $label (expected=$expected)"
        PASS=$((PASS + 1))
    else
        red "  FAIL: $label (expected=$expected, got=$actual)"
        FAIL=$((FAIL + 1))
    fi
}

assert_contains() {
    local label="$1" expected="$2" actual="$3"
    TOTAL=$((TOTAL + 1))
    if echo "$actual" | grep -q "$expected"; then
        green "  PASS: $label (contains '$expected')"
        PASS=$((PASS + 1))
    else
        red "  FAIL: $label (expected to contain '$expected', got=$actual)"
        FAIL=$((FAIL + 1))
    fi
}

wait_for_cdc() {
    local key="$1" field="$2" expected="$3" max_wait="${4:-15}"
    for i in $(seq 1 "$max_wait"); do
        val=$(redis-cli -p 6379 GET "$key" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('$field',''))" 2>/dev/null || true)
        if [ "$val" = "$expected" ]; then
            return 0
        fi
        sleep 1
    done
    return 1
}

wait_for_key_deleted() {
    local key="$1" max_wait="${2:-15}"
    for i in $(seq 1 "$max_wait"); do
        exists=$(redis-cli -p 6379 EXISTS "$key" 2>/dev/null || echo "1")
        if [ "$exists" = "0" ]; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# --- Pre-flight checks ---

bold "=== CDC Pipeline End-to-End Test ==="
echo "Base URL: $BASE_URL"
echo ""

bold "Pre-flight checks..."
health=$(curl -s --max-time 10 "$BASE_URL/actuator/health" 2>/dev/null || true)
if echo "$health" | grep -q '"status":"UP"'; then
    green "  App is UP"
else
    red "  App is not running at $BASE_URL"
    exit 1
fi

cdc_mode=$(curl -s --max-time 10 "$BASE_URL/api/health/cdc" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['cdcMode'])" 2>/dev/null || echo "unknown")
echo "  CDC Mode: $cdc_mode"

redis_ping=$(redis-cli -p 6379 ping 2>/dev/null || echo "FAIL")
assert_eq "Redis reachable" "PONG" "$redis_ping"
echo ""

# --- Step 0: Clean up test data from previous runs ---

bold "Step 0: Clean up previous test data"
redis-cli -p 6379 KEYS "user:*" 2>/dev/null | xargs -r redis-cli -p 6379 DEL 2>/dev/null || true
redis-cli -p 6379 KEYS "order:*" 2>/dev/null | xargs -r redis-cli -p 6379 DEL 2>/dev/null || true
kubectl exec deploy/cdc-demo-postgres -- psql -U postgres -d cdc_demo -c "TRUNCATE users, orders RESTART IDENTITY CASCADE;" 2>/dev/null || \
    kubectl exec deploy/postgres -- psql -U postgres -d cdc_demo -c "TRUNCATE users, orders RESTART IDENTITY CASCADE;" 2>/dev/null || \
    echo "  Warning: Could not truncate tables (may cause duplicate key errors)"
green "Test data cleaned"
echo ""

# --- Step 1: CREATE ---

bold "Step 1: CREATE users and orders"

r=$(curl -s --max-time 10 -X POST "$BASE_URL/api/users" -H "Content-Type: application/json" \
    -d '{"username":"alice","email":"alice@test.com","role":"ADMIN"}')
user1_id=$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
assert_eq "Create user alice" "true" "$(echo "$r" | python3 -c "import sys,json; print(str(json.load(sys.stdin)['success']).lower())" 2>/dev/null)"

r=$(curl -s --max-time 10 -X POST "$BASE_URL/api/users" -H "Content-Type: application/json" \
    -d '{"username":"bob","email":"bob@test.com","role":"USER"}')
user2_id=$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
assert_eq "Create user bob" "true" "$(echo "$r" | python3 -c "import sys,json; print(str(json.load(sys.stdin)['success']).lower())" 2>/dev/null)"

r=$(curl -s --max-time 10 -X POST "$BASE_URL/api/orders" -H "Content-Type: application/json" \
    -d "{\"userId\":$user1_id,\"amount\":299.99,\"description\":\"Laptop\"}")
order1_id=$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
assert_eq "Create order Laptop" "PENDING" "$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['status'])" 2>/dev/null)"

r=$(curl -s --max-time 10 -X POST "$BASE_URL/api/orders" -H "Content-Type: application/json" \
    -d "{\"userId\":$user2_id,\"amount\":49.99,\"status\":\"CONFIRMED\",\"description\":\"Mouse\"}")
order2_id=$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
assert_eq "Create order Mouse" "CONFIRMED" "$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['status'])" 2>/dev/null)"
echo ""

# --- Step 2: Wait for CDC and verify READ ---

bold "Step 2: Wait for CDC propagation and verify READ API"

yellow "  Waiting for user:$user1_id to appear in Redis..."
if wait_for_cdc "user:$user1_id" "username" "alice"; then
    green "  CDC propagated for user:$user1_id"
else
    red "  CDC timeout for user:$user1_id"
fi

r=$(curl -s --max-time 10 "$BASE_URL/api/users/$user1_id")
assert_eq "GET /api/users/$user1_id" "alice" "$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['username'])" 2>/dev/null)"

r=$(curl -s --max-time 10 "$BASE_URL/api/users")
user_count=$(echo "$r" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))" 2>/dev/null)
assert_eq "GET /api/users count" "2" "$user_count"

r=$(curl -s --max-time 10 "$BASE_URL/api/orders/$order1_id")
assert_eq "GET /api/orders/$order1_id" "Laptop" "$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['description'])" 2>/dev/null)"

r=$(curl -s --max-time 10 "$BASE_URL/api/orders/user/$user1_id")
alice_orders=$(echo "$r" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))" 2>/dev/null)
assert_eq "GET /api/orders/user/$user1_id count" "1" "$alice_orders"
echo ""

# --- Step 3: UPDATE ---

bold "Step 3: UPDATE user and verify CDC propagation"

curl -s --max-time 10 -X PUT "$BASE_URL/api/users/$user1_id" -H "Content-Type: application/json" \
    -d '{"username":"alice","email":"alice@test.com","role":"SUPERADMIN"}' > /dev/null

yellow "  Waiting for user:$user1_id role=SUPERADMIN in Redis..."
if wait_for_cdc "user:$user1_id" "role" "SUPERADMIN"; then
    green "  CDC propagated UPDATE"
else
    red "  CDC timeout for UPDATE"
fi

r=$(curl -s --max-time 10 "$BASE_URL/api/users/$user1_id")
assert_eq "GET user role after UPDATE" "SUPERADMIN" "$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['role'])" 2>/dev/null)"
echo ""

# --- Step 4: PATCH order status ---

bold "Step 4: PATCH order status and verify CDC propagation"

curl -s --max-time 10 -X PATCH "$BASE_URL/api/orders/$order1_id/status" -H "Content-Type: application/json" \
    -d '{"status":"SHIPPED"}' > /dev/null

yellow "  Waiting for order:$order1_id status=SHIPPED in Redis..."
if wait_for_cdc "order:$order1_id" "status" "SHIPPED"; then
    green "  CDC propagated PATCH"
else
    red "  CDC timeout for PATCH"
fi

r=$(curl -s --max-time 10 "$BASE_URL/api/orders/$order1_id")
assert_eq "GET order status after PATCH" "SHIPPED" "$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['status'])" 2>/dev/null)"
echo ""

# --- Step 5: DELETE ---

bold "Step 5: DELETE user and verify CDC propagation"

curl -s --max-time 10 -X DELETE "$BASE_URL/api/users/$user2_id" > /dev/null

yellow "  Waiting for user:$user2_id to be deleted from Redis..."
if wait_for_key_deleted "user:$user2_id"; then
    green "  CDC propagated DELETE"
else
    red "  CDC timeout for DELETE"
fi

r=$(curl -s --max-time 10 "$BASE_URL/api/users/$user2_id")
assert_eq "GET deleted user returns 404 message" "User with id $user2_id not found" "$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin)['message'])" 2>/dev/null)"
echo ""

# --- Step 6: Validation errors ---

bold "Step 6: Validation error handling"

r=$(curl -s --max-time 10 -X POST "$BASE_URL/api/users" -H "Content-Type: application/json" \
    -d '{"email":"test@test.com","role":"USER"}')
assert_eq "Missing username returns error" "false" "$(echo "$r" | python3 -c "import sys,json; print(str(json.load(sys.stdin)['success']).lower())" 2>/dev/null)"

r=$(curl -s --max-time 10 -X POST "$BASE_URL/api/orders" -H "Content-Type: application/json" \
    -d '{"userId":1,"amount":0,"description":"Free"}')
assert_eq "Zero amount returns error" "false" "$(echo "$r" | python3 -c "import sys,json; print(str(json.load(sys.stdin)['success']).lower())" 2>/dev/null)"

r=$(curl -s --max-time 10 -X PUT "$BASE_URL/api/users/99999" -H "Content-Type: application/json" \
    -d '{"username":"ghost","email":"ghost@test.com","role":"USER"}')
assert_contains "Non-existent user returns not found" "not found" "$r"
echo ""

# --- Step 7: Redis state ---

bold "Step 7: Final Redis state"
echo "  Keys:"
redis-cli -p 6379 KEYS "*" 2>/dev/null | grep -v cdc_demo | sort | while read key; do
    echo "    $key"
done
echo ""

# --- Summary ---

bold "=== Test Summary ==="
echo "  Total: $TOTAL"
green "  Passed: $PASS"
if [ "$FAIL" -gt 0 ]; then
    red "  Failed: $FAIL"
    exit 1
else
    green "  All tests passed!"
fi
