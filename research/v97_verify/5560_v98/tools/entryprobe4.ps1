$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$root = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98"
$ev = "$root\ev4"
$probe = "$root\tools\probe4.ps1"

function Get-Sig([string]$tag) {
  $p = Join-Path $ev "$tag.sig.txt"
  if (-not (Test-Path $p)) { return $null }
  $raw = Get-Content $p -Encoding UTF8
  $i = [array]::IndexOf($raw, "SIG_BEGIN")
  $j = [array]::IndexOf($raw, "SIG_END")
  if ($i -lt 0 -or $j -lt 0) { return $null }
  $raw[($i+1)..($j-1)] | Where-Object { $_.Trim() -ne "" }
}
function Get-Count([string]$tag) {
  $raw = Get-Content (Join-Path $ev "$tag.sig.txt") -Encoding UTF8
  (($raw | Where-Object { $_ -like "NODES_TOTAL=*" }) -split "=")[1]
}
function Get-StripTop([string]$tag) {
  $raw = Get-Content (Join-Path $ev "$tag.sig.txt") -Encoding UTF8
  $s = $raw | Where-Object { $_ -like "*STRIP *clickable=true*" -and $_ -like "*1452*" }
  ($s | ForEach-Object { if ($_ -match "STRIP \[(\d+),(\d+)\]") { [int]$Matches[2] } } | Measure-Object -Minimum).Minimum
}

$base = Get-Sig "B0_closed_baseline"
$baseN = Get-Count "B0_closed_baseline"
$baseStrip = Get-StripTop "B0_closed_baseline"
Write-Host "BASELINE nodes=$baseN striptop=$baseStrip"

$cands = @(
  @{n="C1_SessionTab_tap_250_1500";   act=@{t=@(250,1500)};            note="SessionTab [84,1452][471,1548] body, avoids close btn"},
  @{n="C2_SwipeLeftEdge_fast";        act=@{sw=@(5,800,600,800,90)};   note="left edge -> center, 90ms fast"},
  @{n="C3_SwipeRightEdge_fast";       act=@{sw=@(1195,800,600,800,90)}; note="right edge -> center, 90ms fast"},
  @{n="C4_SwipeUpFromBottom_fast";    act=@{sw=@(600,1400,600,700,90)}; note="bottom -> up, 90ms fast"},
  @{n="C5_SwipeUpOnSessionTab_fast";  act=@{sw=@(250,1500,250,800,90)}; note="swipe up starting ON the SessionTab"},
  @{n="C6_LongPress_SessionTab";      act=@{lp=@(250,1500)};          note="long press 1200ms on SessionTab"},
  @{n="C7_SessionTab_tap_150_1500";   act=@{t=@(150,1500)};            note="SessionTab left part (title area)"},
  @{n="C8_SwipeDown_FromTop_fast";    act=@{sw=@(600,200,600,900,90)}; note="top -> down pull"}
)

$rows = @()
foreach ($c in $cands) {
  $a = $c.act
  if ($a.t)     { & $adb -s emulator-5560 shell input tap $a.t[0] $a.t[1] }
  if ($a.sw)    { & $adb -s emulator-5560 shell input swipe $a.sw[0] $a.sw[1] $a.sw[2] $a.sw[3] $a.sw[4] }
  if ($a.lp)    { & $adb -s emulator-5560 shell input swipe $a.lp[0] $a.lp[1] $a.lp[0] $a.lp[1] 1200 }
  & $probe -Tag $c.n -SettleSec 2 | Out-Null
  $sig = Get-Sig $c.n
  $n2  = Get-Count $c.n
  $st  = Get-StripTop $c.n
  $same = $true
  if ($null -eq $sig) { $same = $false }
  else {
    $diff = Compare-Object $base $sig
    if ($null -ne $diff) { $same = $false }
  }
  $verdict = if ($same) { "NO_CHANGE" } else { "CHANGED" }
  $rows += [pscustomobject]@{
    Cand = $c.n; Action = $c.note; Nodes = $n2; StripTop = $st; Verdict = $verdict
    Diff = if ($same) { 0 } else { (Compare-Object $base $sig | Measure-Object).Count }
  }
  Write-Host ("{0,-32} nodes={1,-4} striptop={2,-5} {3}" -f $c.n, $n2, $st, $verdict)
}
$rows | Export-Csv (Join-Path $ev "entry_probe_table.csv") -NoTypeInformation -Encoding UTF8
Write-Host "TABLE SAVED"
