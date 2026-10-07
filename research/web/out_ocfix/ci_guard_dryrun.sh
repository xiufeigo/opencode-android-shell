#!/usr/bin/env bash
# 本地干跑 CI 的 "Verify APK identity and signature" 步骤（同一段脚本，只换成本机路径）。
# 用法： bash research/web/out_ocfix/ci_guard_dryrun.sh
set -e
BT="${LOCALAPPDATA//\\//}/Android/Sdk/build-tools/35.0.0"
AAPT2="$BT/aapt2.exe"
APK="webapp/build/outputs/apk/release/webapp-release.apk"

"$AAPT2" dump badging "$APK" > /tmp/badging.txt
grep -E "^package:|^application-label:" /tmp/badging.txt | head -3
PKG=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" /tmp/badging.txt)
CODE=$(sed -n "s/^package:.*versionCode='\([0-9]*\)'.*/\1/p" /tmp/badging.txt)
NAME=$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" /tmp/badging.txt)
echo "package=$PKG versionCode=$CODE versionName=$NAME"
test "$PKG" = "com.xiufeigo.opencodeshell"
echo "APK_NAME=opencode-${NAME}.apk"
if [ -z "$NAME" ]; then echo "GUARD BUG: versionName 抽取为空"; exit 9; fi
echo "GUARD OK"
