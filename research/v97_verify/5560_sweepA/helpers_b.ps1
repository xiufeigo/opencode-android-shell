# v97b helpers for 5560 sweepA. ASCII-only (PS 5.1 parses non-ASCII .ps1 as ANSI).
# Rules: no exec-out, no %s (space -> keyevent 62), no BACK (4), no ESC (111) inside sheets/forms.
$ErrorActionPreference = "Stop"
$ADB = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$SER = "emulator-5560"
$EVID = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_sweepA"

function Shot([string]$name) {
  $remote = "/sdcard/$name.png"
  & $ADB -s $SER shell screencap -p $remote | Out-Null
  $local = Join-Path $EVID "$name.png"
  & $ADB -s $SER pull $remote $local 2>&1 | Out-Null
  Write-Host "shot -> $name.png"
}

# Type text in chunks of <=8 chars. Literal trailing spaces are dropped by adb,
# and %s is forbidden, so spaces are emitted as keyevent 62 between chunks.
function TypeText([string]$text) {
  $parts = $text -split ' '
  for ($p = 0; $p -lt $parts.Count; $p++) {
    $w = $parts[$p]
    if ($w.Length -gt 0) {
      for ($i = 0; $i -lt $w.Length; $i += 8) {
        $len = [Math]::Min(8, $w.Length - $i)
        & $ADB -s $SER shell input text $w.Substring($i, $len) | Out-Null
        Start-Sleep -Milliseconds 1500
      }
    }
    if ($p -lt $parts.Count - 1) {
      & $ADB -s $SER shell input keyevent 62 | Out-Null
      Start-Sleep -Milliseconds 700
    }
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

# Convenience: text nodes present in a dumped xml
function XmlText([string]$name) {
  $p = Join-Path $EVID "$name.xml"
  if (-not (Test-Path $p)) { Write-Host "MISSING $name.xml"; return }
  $x = [xml](Get-Content $p -Raw)
  $x.SelectNodes("//node[@text!='']") | ForEach-Object { $_.text }
}

# Find clickable/enabled attributes of nodes whose text or desc matches a pattern
function XmlFind([string]$name, [string]$pattern) {
  $p = Join-Path $EVID "$name.xml"
  if (-not (Test-Path $p)) { Write-Host "MISSING $name.xml"; return }
  $x = [xml](Get-Content $p -Raw)
  $x.SelectNodes("//node") | Where-Object {
    $_.text -match $pattern -or $_."content-desc" -match $pattern
  } | ForEach-Object {
    "[{0},{1}][{2},{3}] text='{4}' desc='{5}' clickable={6} enabled={7} focused={8} class={9}" -f `
      $_.bounds.Split(",")[0], $_.bounds.Split(",")[1], $_.bounds.Split(",")[2], $_.bounds.Split(",")[3], `
      $_.text, $_."content-desc", $_.clickable, $_.enabled, $_.focused, $_.class
  }
}
