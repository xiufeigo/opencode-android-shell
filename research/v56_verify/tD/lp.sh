#!/bin/sh
. "$(dirname "$0")/_env.sh"
$ADB -s $SER shell input swipe $1 $2 $1 $2 800
