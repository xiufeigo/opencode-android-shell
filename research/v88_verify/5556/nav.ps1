param([string]$Tap = '', [string]$TapXY = '', [int]$Skip = 0, [string]$Dump = '', [int]$SleepMs = 1200, [string]$Filter = '')
$ErrorActionPreference = 'Continue'
$adb = "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$S = 'emulator-5556'
$d = "C:\sensorsdata\main\program\opencode-android-shell\research\v88_verify\5556"
$xml = "$d\_tmp.xml"

function Get-Nodes {
  & $adb -s $S shell uiautomator dump /sdcard/ui.xml | Out-Null
  & $adb -s $S pull /sdcard/ui.xml $xml | Out-Null
  $c = Get-Content $xml -Raw
  $res = @()
  foreach ($n in [regex]::Matches($c, '<node[^>]*>')) {
    $s = $n.Value
    $t = ''; if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
    $cd = ''; if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
    $lb = if ($t.Trim() -ne '') { $t } else { $cd }
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

if ($Dump -ne '') {
  $null = Get-Nodes
  Copy-Item $xml (Join-Path $d $Dump) -Force
  $nn = Get-Nodes
  "--- NODES ($($nn.Count)) ---"
  $nn | ForEach-Object {
    if ($_.Pkg -notlike '*opencodeshell*' -and $_.Cls -notlike '*EditText*') { return }
    if ($_.Label.Trim() -eq '') { return }
    if ($Filter -ne '' -and $_.Label -notmatch $Filter) { return }
    "{0,-44} {1,-18} [{2},{3}]-[{4},{5}]" -f $_.Label.Substring(0,[Math]::Min(44,$_.Label.Length)), ($_.Cls -replace 'android\.widget\.','' -replace 'android\.view\.',''), $_.X1, $_.Y1, $_.X2, $_.Y2
  }
  return
}

if ($TapXY -ne '') {
  $p = $TapXY.Split(',')
  & $adb -s $S shell input tap $p[0] $p[1]
  Start-Sleep -Milliseconds $SleepMs
  "tapXY $TapXY"
  return
}

if ($Tap -ne '') {
  $n = Get-Nodes | Where-Object { $_.Label -match $Tap }
  if ($n.Count -le $Skip) { "!! no node for '$Tap' (found $($n.Count))"; exit 1 }
  $h = $n[$Skip]
  & $adb -s $S shell input tap $h.CX $h.CY
  Start-Sleep -Milliseconds $SleepMs
  "tap '$($h.Label)' [$($h.X1),$($h.Y1)-$($h.X2),$($h.Y2)] -> $($h.CX),$($h.CY)"
}
