#!/usr/bin/env bash
set -u
cd "$(dirname "$0")"
AUTH="opencode:opencode"
BASE="http://localhost:4095"

SID=$(curl -s -u $AUTH -X POST "$BASE/api/session" -H 'Content-Type: application/json' -d '{}' | python -c "import json,sys; print(json.load(sys.stdin)['data']['id'])")
echo "SID=$SID"
curl -s -u $AUTH -X POST "$BASE/api/session/$SID/model" -H 'Content-Type: application/json' -d '{"model":{"id":"muse-spark-1.3-contributor","providerID":"opencode-go"}}' > /dev/null

curl -sN -u $AUTH "$BASE/api/event" > abort_global.sse 2>&1 &
G=$!
sleep 1
curl -s -u $AUTH -X POST "$BASE/api/session/$SID/prompt" -H 'Content-Type: application/json' -d '{"text":"Count from 1 to 500 slowly, one number per line."}' > /dev/null
sleep 4
echo "--- active before interrupt ---"
curl -s -u $AUTH "$BASE/api/session/active"; echo
echo "--- interrupt ---"
curl -s -u $AUTH -X POST "$BASE/api/session/$SID/interrupt" -w "\nHTTP %{http_code}\n"; echo
sleep 3
echo "--- active after interrupt ---"
curl -s -u $AUTH "$BASE/api/session/active"; echo
sleep 2
kill $G 2>/dev/null
curl -s -u $AUTH -X DELETE "$BASE/api/session/$SID" > /dev/null
echo "done"
