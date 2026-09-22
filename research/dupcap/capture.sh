#!/usr/bin/env bash
set -u
cd "$(dirname "$0")"
AUTH="opencode:opencode"
BASE="http://localhost:4095"

SID=$(curl -s -u $AUTH -X POST "$BASE/api/session" -H 'Content-Type: application/json' -d '{}' | python -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',d).get('id'))")
echo "SID=$SID"
curl -s -u $AUTH -X POST "$BASE/api/session/$SID/model" -H 'Content-Type: application/json' -d '{"model":{"id":"muse-spark-1.3-contributor","providerID":"opencode-go"}}' > /dev/null

curl -sN -u $AUTH "$BASE/api/event" > global.sse 2>&1 &
G=$!
curl -sN -u $AUTH "$BASE/api/experimental/session/$SID/log?follow=true" > log.sse 2>&1 &
L=$!
sleep 2

curl -s -u $AUTH -X POST "$BASE/api/session/$SID/prompt" -H 'Content-Type: application/json' -d '{"text":"Reply with exactly one short sentence: the sky is blue."}' > prompt_resp.json
sleep 30
kill $G $L 2>/dev/null
echo "done"
wc -l global.sse log.sse
