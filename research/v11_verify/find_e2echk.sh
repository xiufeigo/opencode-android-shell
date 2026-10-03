#!/bin/bash
# read-only: locate which table holds the E2ECHK_zen_ok session rows
DB=/home/xiufeigo/.local/share/opencode/opencode.db
for t in $(sqlite3 -readonly "$DB" ".tables"); do
  cols=$(sqlite3 -readonly "$DB" "pragma table_info($t)" | awk -F'|' '{print $2}' | tr '\n' ',' | sed 's/,$//')
  [ -z "$cols" ] && continue
  q="select count(*) from $t where cast($cols as text) like '%E2ECHK_zen_ok%'"
  n=$(sqlite3 -readonly "$DB" "$q" 2>/dev/null)
  [ -n "$n" ] && [ "$n" != "0" ] && echo "$t: $n"
done
echo "---- sessions in last 6h ----"
sqlite3 -readonly -header -column "$DB" "select id, substr(title,1,44) as title, substr(project_id,1,12) as proj, time_created from session where time_created > $(( $(date +%s) - 21600 ))000 order by time_created desc limit 30;"
echo "---- inbox rows ----"
sqlite3 -readonly -header -column "$DB" "select count(*) from session_inbox;"
sqlite3 -readonly "$DB" "pragma table_info(session_inbox);" | head -20
