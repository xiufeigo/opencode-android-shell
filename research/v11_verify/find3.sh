#!/bin/bash
DB=/home/xiufeigo/.local/share/opencode/opencode.db
ls -la "$DB" "$DB-wal" "$DB-shm"
echo "--- raw string occurrences in wal ---"
for s in "Exact OK response" "E2ECHK_zen_ok" "E2ECHK-composer-test" "Hello normal queue" "Reply with exactly: OK"; do
  n=$(grep -c -- "$s" "$DB-wal" 2>/dev/null || echo 0)
  echo "$s => $n"
done
echo "--- table counts ---"
sqlite3 -readonly "$DB" "select 'session', count(*) from session union all select 'message', count(*) from message union all select 'part', count(*) from part union all select 'session_message', count(*) from session_message;"
echo "--- sessions containing 'OK response' or 'Untitled' ---"
sqlite3 -readonly "$DB" "select id, substr(title,1,50), time_created from session where title like '%OK response%' or title like '%Untitled%' or title like '%E2ECHK%' limit 20;"
