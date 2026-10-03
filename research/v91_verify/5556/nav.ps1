param([string]$Tap = '', [string]$TapXY = '', [int]$Skip = 0, [string]$Dump = '', [int]$SleepMs = 1200, [string]$Filter = '', [string]$Shot = '', [string]$Text = '', [int]$TextX = 0, [int]$TextY = 0)
$ErrorActionPreference = 'Continue'
$adb = "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$S = 'emulator-5556'
$d = "C:\sensorsdata\main\program\opencode-android-shell\research\v91_verify\5556"
$xml = "$d\_tmp.xml"

function Get-Nodes {
  & $adb -s $S shell uiautomator dump /sdcard/_v91.xml | Out-Null
  & $adb -s $S pull /sdcard/_v91.xml $xml | Out-Null
  $c = Get-Content $xml -Raw
  $res = @()
  foreach ($n in [regex]::Matches($c, '<node[^>]*>')) {
    $s = $n.Value
    $t = ''; if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
    $cd = ''; if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
    $lb = if ($t.Trim().Length -gt 0) { $t } else { $cd }
    $cls = ''; if ($s -match 'class="([^"]*)"') { $cls = $Matches[1] }
    $pk = ''; if ($s -match 'package="([^"]*)"') { $pk = $Matches[1] }
    if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      $res += [pscustomobject]@{
        Label = $lb; Cls = $cls; Pkg = $pk
        X1 = [int]$Matches[1]; Y1 = [int]$Matches[2]; X2 = [int]$Matches[3]; Y2 = [int]$Matches[4]
        CX = [int](([int]$Matches[1] + [int]$Matches[3])/2); CY = [int](([int]$Matches[2] + [int]$Matches[4])/2)
      }
    }
  }
  return $res
}

function Take-Shot([string]$n) {
  $o = Join-Path $d "$n.png"
  cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$o`""
  Start-Sleep -Milliseconds 350
  "SHOT $n bytes=$((Get-Item $o).Length)"
}

if ($Text -ne '') {
  $seg = $Text.Split('|')
  for ($i = 0; $i -lt $seg.Count; $i++) {
    & $adb -s $S shell input text $seg[$i]
    Start-Sleep -Milliseconds 1500
  }
  "text '$Text' -> ${TextX},${TextY} segs=$($seg.Count)"
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

if ($Dump -ne '' -or $Shot -ne '') {
  $nn = @(Get-Nodes)
  if ($Dump -ne '') { Copy-Item $xml (Join-Path $d "ui_$Dump.xml") -Force; "DUMP $Dump nodes=$($nn.Count)" }
  if ($Shot -ne '') { Take-Shot $Shot }
  "--- NODES ---"
  $nn | ForEach-Object {
    if ($_.Pkg -notlike '*opencodeshell*' -and $_.Cls -notlike '*EditText*') { return }
    if ($_.Label.Trim() -eq '') { return }
    if ($Filter -ne '' -and $_.Label -notmatch $Filter) { return }
    "{0,-46} {1,-16} [{2},{3}]-[{4},{5}] c=({6},{7})" -f $_.Label.Substring(0,[Math]::Min(46,$_.Label.Length)), ($_.Cls -replace 'android\.widget\.','' -replace 'android\.view\.',''), $_.X1, $_.Y1, $_.X2, $_.Y2, $_.CX, $_.CY
  }
}
