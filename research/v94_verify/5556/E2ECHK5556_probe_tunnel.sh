#!/system/bin/sh
# Probe the adb reverse tunnel: device 127.0.0.1:2222 -> host 22 (sshd)
# Keep stdin open briefly so nc can read the SSH banner the server sends on connect.
echo "--- probe start ---"
( sleep 6 ) | toybox timeout 12 toybox nc 127.0.0.1 2222 > /data/local/tmp/banner.txt 2>&1 &
NCPID=$!
sleep 8
kill $NCPID 2>/dev/null
wait $NCPID 2>/dev/null
echo "BANNER_RAW:"
cat /data/local/tmp/banner.txt
echo ""
echo "--- banner bytes: $(wc -c < /data/local/tmp/banner.txt) ---"
echo "--- probe end ---"
