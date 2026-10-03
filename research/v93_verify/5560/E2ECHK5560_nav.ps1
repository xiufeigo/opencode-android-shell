param([string]$Tap = '', [string]$TapXY = '', [int]$Skip = 0, [string]$Dump = '', [int]$SleepMs = 1200, [string]$Filter = '', [string]$Shot = '', [string]$Text = '', [int]$TextX = 0, [int]$TextY = 0, [string]$Swipe = '', [int]$WaitMs = 0)
$ErrorActionPreference = 'Continue'
$adb = "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$S = 'emulator-5560'
$d = "C:\sensorsdata\main\program\opencode-android-shell\research\v93_verify\5560"
$xml = "$d\E2ECHK5560_tmp.xml"

function Get-Nodes {
  & $adb -s $S shell uiautomator dump /sdcard/E2ECHK5560.xml | Out-Null
  & $adb -s $S pull /sdcard/E2ECHK5560.xml $xml | Out-Null
  $c = Get-Content $xml -Raw
  $res = @()
  foreach ($n in [regex]::Matches($c, '<node[^>]*>')) {
    $s = $n.Value
    $t = ''; if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
    $cd = ''; if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
    $lb = if ($t.Trim().Length -gt 0) { $t } else { $cd }
    $cls = ''; if ($s -match 'class="([^"]*)"') { $cls = $Matches[1] }
    $pk = ''; if ($s -match 'package="([^"]*)"') { $pk = $Matches[1] }
    $clk = ''; if ($s -match 'clickable="([^"]*)"') { $clk = $Matches[1] }
    if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      $res += [pscustomobject]@{
        Label = $lb; Cls = $cls; Pkg = $pk; Clickable = $clk
        X1 = [int]$Matches[1]; Y1 = [int]$Matches[2]; X2 = [int]$Matches[3]; Y2 = [int]$Matches[4]
        CX = [int](([int]$Matches[1] + [int]$Matches[3])/2); CY = [int](([int]$Matches[2] + [int]$Matches[4])/2)
      }
    }
  }
  return $res
}

function Take-Shot([string]$n) {
  $o = Join-Path $d "$n.png"
  # device-side capture + pull: binary-safe, no adb stdout warnings spliced into the PNG
  & $adb -s $S shell screencap -p /sdcard/E2ECHK5560_shot.png 2>&1 | Out-Null
  & $adb -s $S pull /sdcard/E2ECHK5560_shot.png $o 2>&1 | Out-Null
  Start-Sleep -Milliseconds 300
  $b = [System.IO.File]::ReadAllBytes($o)
  $sig = [BitConverter]::ToString($b[0..3])
  "SHOT $n bytes=$($b.Length) sig=$sig"
}

function Dump-View {
  $nn = @(Get-Nodes)
  if ($Dump -ne '') { Copy-Item $xml (Join-Path $d "E2ECHK5560_$Dump.xml") -Force; "DUMP $Dump nodes=$($nn.Count)" }
  if ($Shot -ne '') { Take-Shot $Shot }
  "--- NODES (pkg opencodeshell + EditText) ---"
  $nn | ForEach-Object {
    if ($_.Pkg -notlike '*opencodeshell*' -and $_.Cls -notlike '*EditText*') { return }
    if ($_.Label.Trim() -eq '' -and $_.Cls -notlike '*EditText*') { return }
    $lb = $_.Label; if ($lb.Trim() -eq '') { $lb = '(no-label)' }
    if ($Filter -ne '' -and $_.Label -notmatch $Filter) { return }
    "{0,-52} {1,-14} c={2,-5} [{3},{4}]-[{5},{6}] c=({7},{8})" -f $lb.Substring(0,[Math]::Min(52,$lb.Length)), ($_.Cls -replace 'android\.widget\.','' -replace 'android\.view\.',''), $_.Clickable, $_.X1, $_.Y1, $_.X2, $_.Y2, $_.CX, $_.CY
  }
}

if ($WaitMs -gt 0) { Start-Sleep -Milliseconds $WaitMs }

if ($Text -ne '') {
  $p = $Text.Split('@')
  $seg = $p[0].Split('|')
  & $adb -s $S shell input tap $p[1] $p[2]
  Start-Sleep -Milliseconds 900
  for ($i = 0; $i -lt $seg.Count; $i++) {
    & $adb -s $S shell input text $seg[$i]
    Start-Sleep -Milliseconds 1500
  }
  "text at $($p[1]),$($p[2]) segs=$($seg.Count)"
  if ($Shot -ne '') { Take-Shot $Shot }
  return
}

if ($Swipe -ne '') {
  $p = $Swipe.Split(',')
  & $adb -s $S shell input swipe $p[0] $p[1] $p[2] $p[3] 220
  Start-Sleep -Milliseconds $SleepMs
  "swipe $Swipe"
  if ($Shot -ne '') { Take-Shot $Shot }
  return
}

if ($TapXY -ne '') {
  $p = $TapXY.Split(',')
  & $adb -s $S shell input tap $p[0] $p[1]
  Start-Sleep -Milliseconds $SleepMs
  "tapXY $TapXY"
  if ($Shot -ne '') { Take-Shot $Shot }
  return
}

if ($Tap -ne '') {
  $n = @(Get-Nodes | Where-Object { $_.Label -match $Tap })
  if ($n.Count -le $Skip) { "!! no node for '$Tap' (found $($n.Count))"; exit 1 }
  $h = $n[$Skip]
  & $adb -s $S shell input tap $h.CX $h.CY
  Start-Sleep -Milliseconds $SleepMs
  "tap '$($h.Label)' [$($h.X1),$($h.Y1)-$($h.X2),$($h.Y2)] -> $($h.CX),$($h.CY)"
  if ($Shot -ne '') { Take-Shot $Shot }
  return
}

if ($Dump -ne '' -or $Shot -ne '') { Dump-View; return }
"nothing to do"
