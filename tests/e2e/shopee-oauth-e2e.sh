#!/bin/bash
# Shopee OAuth E2E Tests
# Tests the complete OAuth token lifecycle via REST API
# Usage: bash tests/e2e/shopee-oauth-e2e.sh

set -euo pipefail

BASE_URL="http://localhost:8082"
PASS=0
FAIL=0

pass() { echo "✅ $1"; PASS=$((PASS+1)); }
fail() { echo "❌ $1"; FAIL=$((FAIL+1)); }

echo "╔════════════════════════════════════════════════════════════╗"
echo "║  Shopee OAuth E2E Tests                                    ║"
echo "╚════════════════════════════════════════════════════════════╝"

# ── Auth ────────────────────────────────────────────────────────
TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"merchant@test.com","password":"password"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")
if [ -z "$TOKEN" ]; then
  echo "FATAL: Cannot login — aborting tests"
  exit 1
fi
AUTH="Authorization: Bearer $TOKEN"

# ── Get Shopee channel ───────────────────────────────────────────
CHANNEL_ID=$(curl -s "$BASE_URL/api/user/channels" -H "$AUTH" \
  | python3 -c "
import sys,json
channels=json.load(sys.stdin)
shopee=[c for c in channels if c.get('platformId')=='shopee']
print(shopee[0]['id'] if shopee else '')
")
if [ -z "$CHANNEL_ID" ]; then
  echo "FATAL: No shopee channel found — aborting tests"
  exit 1
fi
echo "Using channel: $CHANNEL_ID"
echo ""

# ── T1: GET /channels returns ChannelVO (no raw token) ──────────
VO=$(curl -s "$BASE_URL/api/user/channels/$CHANNEL_ID" -H "$AUTH")
has_masked=$(echo "$VO" | python3 -c "import sys,json; d=json.load(sys.stdin); print('yes' if 'token1Masked' in d else 'no')")
has_raw=$(echo "$VO" | python3 -c "import sys,json; d=json.load(sys.stdin); print('yes' if 'token' in d and 'token1Masked' not in d else 'no')" 2>/dev/null || echo "no")
[ "$has_masked" = "yes" ] && pass "GET /channels returns ChannelVO (has token1Masked)" || fail "GET /channels returns ChannelVO (no token1Masked field)"

# ── T2: oauthStatus field present ───────────────────────────────
STATUS=$(echo "$VO" | python3 -c "import sys,json; print(json.load(sys.stdin).get('oauthStatus','MISSING'))")
[ "$STATUS" != "MISSING" ] && pass "ChannelVO has oauthStatus field (value=$STATUS)" || fail "ChannelVO missing oauthStatus field"

# ── T3: tokenLabels from platform capabilities ───────────────────
LABELS=$(echo "$VO" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('tokenLabels',{})))")
has_token1=$(echo "$LABELS" | python3 -c "import sys,json; d=json.load(sys.stdin); print('yes' if 'token1' in d else 'no')")
[ "$has_token1" = "yes" ] && pass "tokenLabels.token1 present (value=$(echo $LABELS | python3 -c "import sys,json; print(json.load(sys.stdin).get('token1',''))"))" || fail "tokenLabels.token1 missing"

# ── T4: oauthStatus=CONNECTED when token set with future expiry ──
# Set a token4 8h in future
FUTURE=$(python3 -c "from datetime import datetime, timedelta, timezone; print((datetime.now(timezone.utc)+timedelta(hours=8)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
curl -s -X PUT "$BASE_URL/api/user/channels/$CHANNEL_ID" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"token\":\"test_access_token\",\"token2\":\"test_refresh_token\",\"token3\":\"12345678\",\"token4\":\"$FUTURE\"}" > /dev/null
VO4=$(curl -s "$BASE_URL/api/user/channels/$CHANNEL_ID" -H "$AUTH")
STATUS4=$(echo "$VO4" | python3 -c "import sys,json; print(json.load(sys.stdin).get('oauthStatus',''))")
[ "$STATUS4" = "CONNECTED" ] && pass "oauthStatus=CONNECTED for token expires in 8h" || fail "oauthStatus=CONNECTED check failed (got=$STATUS4)"

# ── T5: oauthStatus=EXPIRING_SOON when ≤60min ───────────────────
SOON=$(python3 -c "from datetime import datetime, timedelta, timezone; print((datetime.now(timezone.utc)+timedelta(minutes=45)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
curl -s -X PUT "$BASE_URL/api/user/channels/$CHANNEL_ID" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"token4\":\"$SOON\"}" > /dev/null
VO5=$(curl -s "$BASE_URL/api/user/channels/$CHANNEL_ID" -H "$AUTH")
STATUS5=$(echo "$VO5" | python3 -c "import sys,json; print(json.load(sys.stdin).get('oauthStatus',''))")
[ "$STATUS5" = "EXPIRING_SOON" ] && pass "oauthStatus=EXPIRING_SOON (45min)" || fail "oauthStatus=EXPIRING_SOON check failed (got=$STATUS5)"

# ── T6: oauthStatus=EXPIRED when past date ───────────────────────
PAST=$(python3 -c "from datetime import datetime, timedelta, timezone; print((datetime.now(timezone.utc)-timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
curl -s -X PUT "$BASE_URL/api/user/channels/$CHANNEL_ID" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"token4\":\"$PAST\"}" > /dev/null
VO6=$(curl -s "$BASE_URL/api/user/channels/$CHANNEL_ID" -H "$AUTH")
STATUS6=$(echo "$VO6" | python3 -c "import sys,json; print(json.load(sys.stdin).get('oauthStatus',''))")
[ "$STATUS6" = "EXPIRED" ] && pass "oauthStatus=EXPIRED (past date)" || fail "oauthStatus=EXPIRED check failed (got=$STATUS6)"

# ── T7: /callback/shopee is public (no JWT required) ────────────
HTTP_CB=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/callback/shopee?code=test&shop_id=123&state=fake")
[ "$HTTP_CB" != "401" ] && [ "$HTTP_CB" != "403" ] && pass "/callback/shopee is public (got=$HTTP_CB, not 401/403)" || fail "/callback/shopee should be public (got=$HTTP_CB)"

# ── T8: /callback/shopee returns HTML with postMessage ───────────
HTML=$(curl -s "$BASE_URL/callback/shopee?code=test&shop_id=123&state=fake")
pm_count=$(echo "$HTML" | grep -c "postMessage" || true)
[ "$pm_count" -ge 1 ] && pass "/callback/shopee returns HTML with postMessage (count=$pm_count)" || fail "/callback/shopee missing postMessage (count=$pm_count)"

# ── T9: GET /shopee/auth-url endpoint reachable ───────────────────
HTTP_AU=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/user/channels/$CHANNEL_ID/shopee/auth-url" -H "$AUTH")
[ "$HTTP_AU" = "200" ] && pass "GET /shopee/auth-url returns 200" || fail "GET /shopee/auth-url failed (got=$HTTP_AU)"

# ── T10: PUT /channels/{id} updates token fields ─────────────────
RESP_PUT=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE_URL/api/user/channels/$CHANNEL_ID" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"channelName":"Shopee Test Channel"}')
[ "$RESP_PUT" = "200" ] && pass "PUT /channels/{id} returns 200" || fail "PUT /channels/{id} failed (got=$RESP_PUT)"

# ── T11: POST /shopee/disconnect returns 200 ─────────────────────
HTTP_DIS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/user/channels/$CHANNEL_ID/shopee/disconnect" -H "$AUTH")
[ "$HTTP_DIS" = "200" ] && pass "POST /disconnect returns 200" || fail "POST /disconnect failed (got=$HTTP_DIS)"

# ── T12: oauthStatus=NOT_CONNECTED after disconnect ──────────────
VO_DIS=$(curl -s "$BASE_URL/api/user/channels/$CHANNEL_ID" -H "$AUTH")
STATUS_DIS=$(echo "$VO_DIS" | python3 -c "import sys,json; print(json.load(sys.stdin).get('oauthStatus',''))")
[ "$STATUS_DIS" = "NOT_CONNECTED" ] && pass "oauthStatus=NOT_CONNECTED after disconnect" || fail "oauthStatus=NOT_CONNECTED check failed (got=$STATUS_DIS)"

# ── Summary ──────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════"
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] && echo "Status: ALL PASS ✅" || echo "Status: SOME FAILED ❌"
echo "═══════════════════════════════════════"
exit $FAIL
