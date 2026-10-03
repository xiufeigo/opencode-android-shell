#!/bin/sh
. "$(dirname "$0")/_env.sh"
$ADB -s $SER shell rm -f /sdcard/ui.xml
$ADB -s $SER shell uiautomator dump /sdcard/ui.xml 2>&1 | tail -1
$ADB -s $SER pull /sdcard/ui.xml /tmp/uix.xml >/dev/null 2>&1
python "$(dirname "$0")/parse.py"
