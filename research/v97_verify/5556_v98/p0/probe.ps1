# Top-level helpers (no nesting) — runtime coordinate resolution
$ErrorActionPreference = 'Continue'
$ADB  = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$DEV  = "emulator-5556"
$EV   = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0"
$PKG  = "com.xiufeigo.opencodeshell"
$script:N = $null

function A { & $ADB -s $DEV @args }
function Get-Pid { $r = A shell pidof $PKG; if ($r) { $r.Trim() } else { 'EMPTY' } }
function Get-Focus { ((A shell dumpsys window | Select-String 'mCurrentFocus') -join '').Trim() }
function Assert-Focus { $f = Get-Focus; if ($f -match 'OcActivity') { "FOCUS-OK" } else { "FOCUS-BLOCKED $f"; exit 9 } }
function Get-IMEState {
  $t = (A shell dumpsys input_method) -join "`n"
  $s = if ($t -match 'mInputShown=(\w+)') { $Matches[1] } else { 'NOTFOUND' }
  "mInputShown=$s"
}
function Test-CrashLog {
  $t = (A logcat -d) -join "`n"; $h = @()
  foreach ($k in @('FATAL EXCEPTION','IllegalArgumentException','maxHeight','Force finishing activity','WIN DEATH','has died')) {
    $c = ([regex]::Matches($t, [regex]::Escape($k))).Count; if ($c -gt 0) { $h += "$k=$c" } }
  if ($h.Count -eq 0) { 'CLEAN (0 hits)' } else { $h -join ' ' }
}
function Get-CrashCount {
  $t = (A shell dumpsys activity processes) -join "`n"; $lines = $t -split "`n"
  $last='none'; $since=0; $inBlk=$false
  foreach ($l in $lines) {
    if ($l -match [regex]::Escape($PKG) -and $l -match 'Process com\.') { $inBlk=$true; continue }
    if ($inBlk) {
      if ($l -match 'last crashed\s+(\S+)') { $last=$Matches[1] }
      if ($l -match 'crashes since then:\s*(\d+)') { $since=[int]$Matches[1]; $inBlk=$false }
      elseif ($l -match '^\s*\*APP\*' -or $l -match '^\s*Process com\.') { $inBlk=$false } } }
  "crashes_since_then=$since ; last_crashed=$last"
}
function Dump-UI([string]$tag) {
  $remote = "/sdcard/${tag}.xml"
  A shell uiautomator dump --compressed $remote | Out-Null
  A pull $remote "$EV\$tag.xml" 2>&1 | Out-Null
  A shell rm -f $remote
  $script:N = $null
  [xml]$x = Get-Content "$EV\$tag.xml" -Raw -Encoding utf8
  $script:N = @()
  CollectNodes $x.hierarchy.node
  "$EV\$tag.xml"
}
function CollectNodes($n) {
  foreach ($c in $n.ChildNodes) {
    if ($c.Name -eq 'node') { $script:N += $c; CollectNodes $c }
  }
}
function FindNode([string]$desc) {
  $script:N | Where-Object { $_.GetAttribute('content-desc') -eq $desc } | Select-Object -First 1
}
function TapDesc([string]$desc) {
  $h = FindNode $desc
  if (-not $h) { return "TAP-MISS '$desc'" }
  $b = $h.GetAttribute('bounds')
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $cx = [int](([int]$Matches[1]+[int]$Matches[3])/2); $cy = [int](([int]$Matches[2]+[int]$Matches[4])/2)
    A shell input tap $cx $cy | Out-Null; Start-Sleep -Milliseconds 700
    "TAP-OK '$desc' @ $cx,$cy"
  } else { "BAD-BOUNDS '$desc'" }
}
function TapMatch([string]$m) {
  $h = $script:N | Where-Object { $_.GetAttribute('content-desc') -eq $m -or $_.GetAttribute('text') -eq $m } | Select-Object -First 1
  if (-not $h) { return "TAP-MISS '$m'" }
  $b = $h.GetAttribute('bounds')
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $cx = [int](([int]$Matches[1]+[int]$Matches[3])/2); $cy = [int](([int]$Matches[2]+[int]$Matches[4])/2)
    A shell input tap $cx $cy | Out-Null; Start-Sleep -Milliseconds 800
    "TAP-OK '$m' @ $cx,$cy"
  } else { "BAD-BOUNDS '$m'" }
}
function Shot([string]$tag) {
  A shell screencap -p "/sdcard/${tag}.png" | Out-Null
  A pull "/sdcard/${tag}.png" "$EV\$tag.png" 2>&1 | Out-Null
  A shell rm -f "/sdcard/${tag}.png"; "$EV\$tag.png"
}
