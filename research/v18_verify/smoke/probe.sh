#!/bin/bash
# probe backend readiness
for i in $(seq 1 20); do
  out=$(timeout 20 curl -s --noproxy "*" -u opencode:opencode http://127.0.0.1:4095/app -o /dev/null -w "%{http_code}" 2>&1)
  rc=$?
  cpu=$(ps -o pcpu= -p "$PIDW" 2>/dev/null | tr -d ' ')
  echo "i$i out=$out rc=$rc cpu=$cpu $(date +%T)"
  if [ "$out" = "200" ]; then echo READY; exit 0; fi
  sleep 12
done
echo NOT_READY
exit 1
