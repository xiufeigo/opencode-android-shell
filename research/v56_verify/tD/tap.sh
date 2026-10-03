#!/bin/sh
. "$(dirname "$0")/_env.sh"
$ADB -s $SER shell input tap $1 $2
