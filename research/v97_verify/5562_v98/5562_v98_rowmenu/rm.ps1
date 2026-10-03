# rm.ps1 — v98 row-menu parity helper: dump + dual-quote tolerant parse + runtime coordinate lookup
# Usage:
#   .\rm.ps1 -Tag t01                                  # dump + list text/clickable nodes with bounds
#   .\rm.ps1 -Tag t01 -Find "E2ECHK5562m-1"           # locate nodes matching text, print centers
#   .\rm.ps1 -Tag t01 -Find "E2ECHK5562m-1" -Subtree  # also print descendants of matched node
param(
  [Parameter(Mandatory=$true)][string]$Tag,
  [string]$Find = "",
  [switch]$Subtree,
  [switch]$Raw
)
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5562"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\5562_v98_rowmenu"
$remote = "/sdcard/rm_$Tag.xml"
$local  = Join-Path $dir "rm_$Tag.xml"

& $adb -s $dev shell uiautomator dump --compressed $remote | Out-Null
& $adb -s $dev pull $remote $local | Out-Null
& $adb -s $dev shell "rm -f $remote" | Out-Null

[xml]$x = Get-Content -LiteralPath $local -Raw -Encoding UTF8
$nodes = $x.SelectNodes("//node")

function Attr($n, $k) { $v = $n.GetAttribute($k); if ($null -eq $v) { return "" }; return $v }
function Txt($n) { $t = Attr $n "text"; if (-not $t) { $t = Attr $n "content-desc" }; return $t }
function Center($b) {
  if ($b -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
    $cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)
    return "$cx,$cy"
  }
  return "?"
}

if ($Raw) { Write-Output "NODES=$($nodes.Count)"; foreach ($n in $nodes) { Write-Output ((Attr $n "class") + "|" + (Txt $n) + "|" + (Attr $n "clickable") + "|" + (Attr $n "enabled") + "|" + (Attr $n "bounds")) }; exit }

if ($Find -ne "") {
  $hits = $nodes | Where-Object { (Txt $_) -like "*$Find*" }
  if ($hits.Count -eq 0) { Write-Output "TAG=$Tag  NODES=$($nodes.Count)  FIND='$Find'  => NO MATCH" ; exit }
  Write-Output "TAG=$Tag  NODES=$($nodes.Count)  FIND='$Find'  MATCHES=$($hits.Count)"
  foreach ($n in $hits) {
    $b = Attr $n "bounds"
    Write-Output ("  class={0} clickable={1} enabled={2} bounds={3} center={4} text='{5}'" -f (Attr $n "class"), (Attr $n "clickable"), (Attr $n "enabled"), $b, (Center $b), (Txt $n))
    if ($Subtree) {
      foreach ($d in $n.SelectNodes(".//node")) {
        $db = Attr $d "bounds"
        Write-Output ("      └ class={0} clickable={1} enabled={2} bounds={3} center={4} text='{5}'" -f (Attr $d "class"), (Attr $d "clickable"), (Attr $d "enabled"), $db, (Center $db), (Txt $d))
      }
    }
  }
  exit
}

Write-Output "TAG=$Tag  NODES=$($nodes.Count)  FILE=$local"
foreach ($n in $nodes) {
  $t = Txt $n
  $c = Attr $n "clickable"
  if ($t -or $c -eq "true") {
    $b = Attr $n "bounds"
    Write-Output ("  click={0,-5} en={1,-5} {2} c={3} '{4}'" -f $c, (Attr $n "enabled"), $b, (Center $b), $t)
  }
}
