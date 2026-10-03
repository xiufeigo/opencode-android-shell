#!/bin/bash
# read-only: which tables/pages mention the string; look for delete events
DB=/home/xiufeigo/.local/share/opencode/opencode.db
echo "== per-column search =="
for t in $(sqlite3 -readonly "$DB" ".tables"); do
  while IFS='|' read -r _ col typ _; do
    [ -z "$col" ] && continue
    n=$(sqlite3 -readonly "$DB" "select count(*) from $t where \"$col\" like '%E2ECHK_zen_ok%'" 2>/dev/null)
    [ -n "$n" ] && [ "$n" != "0" ] && echo "$t.$col: $n"
  done < <(sqlite3 -readonly "$DB" "pragma table_info($t)")
done
echo "== recent events mentioning session delete/update today =="
sqlite3 -readonly "$DB" "select count(*) from event;" 2>/dev/null
echo "== event schema =="
sqlite3 -readonly "$DB" "pragma table_info(event);" | head
