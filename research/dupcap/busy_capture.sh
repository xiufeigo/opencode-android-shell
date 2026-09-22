#!/usr/bin/env bash
set -u
cd "$(dirname "$0")"
AUTH="opencode:opencode"
BASE="http://localhost:4095"

SID=$(curl -s -u $AUTH -X POST "$BASE/api/session" -H 'Content-Type: application/json' -d '{}' | python -c "import json,sys; print(json.load(sys.stdin)['data']['id'])")
echo "SID=$SID"
curl -s -u $AUTH -X POST "$BASE/api/session/$SID/model" -H 'Content-Type: application/json' -d '{"model":{"id":"muse-spark-1.3-contributor","providerID":"opencode-go"}}' > /dev/null

curl -sN -u $AUTH "$BASE/api/event" > busy_global.sse 2>&1 &
G=$!
sleep 1
curl -s -u $AUTH -X POST "$BASE/api/session/$SID/prompt" -H 'Content-Type: application/json' -d '{"text":"Use the shell tool to run: sleep 45 && echo done. Execute in the FOREGROUND (background=false) and wait for it."}' > /dev/null

# wait for permission ask, then approve always
for i in $(seq 1 20); do
  sleep 2
  RID=$(grep -o '"type":"permission.asked"[^}]*' busy_global.sse | tail -1 | grep -o '"id":"per_[^"]*"' | head -1 | cut -d'"' -f4)
  if [ -z "${RID:-}" ]; then
    RID=$(python - <<'EOF'
import json
rid=""
for line in open("busy_global.sse"):
    line=line.strip()
    if not line.startswith("data:"): continue
    try: f=json.loads(line[5:])
    except Exception: continue
    if f.get("type")=="permission.asked":
        rid=f.get("data",{}).get("id","")
print(rid)
EOF
)
  fi
  if [ -n "${RID:-}" ]; then echo "permission ask: $RID"; break; fi
done
if [ -n "${RID:-}" ]; then
  curl -s -u $AUTH -X POST "$BASE/api/session/$SID/permissions/$RID" -H 'Content-Type: application/json' -d '{"decision":"always"}' | head -c 200; echo
fi
echo "--- active while busy ---"
sleep 3
curl -s -u $AUTH "$BASE/api/session/active"; echo
sleep 50
echo "--- active after settle ---"
curl -s -u $AUTH "$BASE/api/session/active"; echo
kill $G 2>/dev/null
curl -s -u $AUTH -X DELETE "$BASE/api/session/$SID" > /dev/null
echo "done"
