# Reusable helpers for 5560 sweepA. Keep ASCII-only (PS 5.1 parses non-ASCII .ps1 as ANSI).
$ErrorActionPreference = "Stop"
$ADB = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$SER = "emulator-5560"
$EVID = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_sweepA"

function Shot([string]$name) {
  $remote = "/sdcard/$name.png"
  & $ADB -s $SER shell screencap -p $remote | Out-Null
  $local = Join-Path $EVID "$name.png"
  & $ADB -s $SER pull $remote $local 2>&1 | Out-Null
  Write-Host "shot -> $local"
}

# Type text in chunks of <=8 chars, sleeping 1.5s between chunks.
# Space is unreliable via `input text` -> use %s
function TypeText([string]$text) {
  $t = $text -replace ' ', '%s'
  for ($i = 0; $i -lt $t.Length; $i += 8) {
    $len = [Math]::Min(8, $t.Length - $i)
    $chunk = $t.Substring($i, $len)
    & $ADB -s $SER shell input text $chunk | Out-Null
    Start-Sleep -Milliseconds 1500
  }
}

function Tap([int]$x, [int]$y) {
  & $ADB -s $SER shell input tap $x $y | Out-Null
  Start-Sleep -Milliseconds 900
}

function Key([int]$code) {
  & $ADB -s $SER shell input keyevent $code | Out-Null
  Start-Sleep -Milliseconds 700
}

function Swipe([int]$x1, [int]$y1, [int]$x2, [int]$y2, [int]$ms) {
  & $ADB -s $SER shell input swipe $x1 $y1 $x2 $y2 $ms | Out-Null
  Start-Sleep -Milliseconds 900
}

function DumpXml([string]$name) {
  & $ADB -s $SER shell uiautomator dump /sdcard/win.xml 2>&1 | Out-Null
  & $ADB -s $SER pull /sdcard/win.xml (Join-Path $EVID "$name.xml") 2>&1 | Out-Null
  Write-Host "xml -> $name.xml"
}
